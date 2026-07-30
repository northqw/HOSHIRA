package dev.aniliberty.desktop.data

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Resolves supported YummyAnime provider pages into direct HLS streams.
 *
 * CVH uses its public JSON API. Kodik exposes a player endpoint whose response
 * can be decoded without executing the provider page.
 */
internal class HlsStreamResolver(
    private val httpClient: HlsHttpClient = UrlConnectionHlsHttpClient,
    private val debug: (String) -> Unit = {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun supports(url: String?): Boolean {
        return inspect(url).supported
    }

    fun inspect(url: String?): HlsSourceDiagnostic {
        val uri = url?.toHttpUri()
            ?: return HlsSourceDiagnostic(HlsProvider.INVALID, supported = false)
        return when {
            uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true) ->
                HlsSourceDiagnostic(HlsProvider.DIRECT, supported = true)
            uri.toVideoHubReference() != null ->
                HlsSourceDiagnostic(HlsProvider.CVH, supported = true)
            uri.toKodikReference() != null ->
                HlsSourceDiagnostic(HlsProvider.KODIK, supported = true)
            uri.isAllohaPlayer() ->
                HlsSourceDiagnostic(
                    provider = HlsProvider.ALLOHA,
                    supported = false,
                    detail = "requires-js-websocket-and-request-headers",
                )
            else ->
                HlsSourceDiagnostic(HlsProvider.UNKNOWN, supported = false)
        }
    }

    fun resolve(
        url: String,
        preferredVoice: String? = null,
    ): PlaybackSource.DirectMedia {
        debug("resolver input ${url.hlsDebugUrl()}")
        val uri = url.toHttpUri()
            ?: throw HlsResolutionException("Источник вернул некорректную ссылку.")
        if (uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)) {
            debug("direct m3u8 accepted")
            return PlaybackSource.DirectMedia(uri.toASCIIString())
        }

        uri.toKodikReference()?.let { reference ->
            return resolveKodik(reference)
        }

        val reference = uri.toVideoHubReference()
            ?: throw unsupportedProvider(uri)
        val voiceHints = listOfNotNull(preferredVoice)
            .plus(reference.voiceHints)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        debug(
            "iframe parsed anime=${reference.animeId} episode=${reference.episode} " +
                "voiceHints=${voiceHints.size}",
        )
        val playlistUrl = buildString {
            append("$VIDEO_HUB_API/player/sv/playlist")
            append("?pub=$VIDEO_HUB_PUBLISHER")
            append("&id=${reference.animeId.urlEncode()}")
            append("&aggr=$VIDEO_HUB_AGGREGATOR")
        }
        val playlist = request<VideoHubPlaylist>(playlistUrl)
        val matchingEpisode = playlist.items
            .filter { it.episode == reference.episode }
        debug(
            "playlist items=${playlist.items.size} episodeMatches=${matchingEpisode.size}",
        )
        if (matchingEpisode.isEmpty()) {
            throw HlsResolutionException(
                "VideoHub не нашёл HLS-поток для ${reference.episode}-й серии.",
            )
        }

        val preferredVoiceHint = preferredVoice
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val eligibleItems = if (preferredVoiceHint == null) {
            matchingEpisode
        } else {
            matchingEpisode.filter {
                it.matchScore(listOf(preferredVoiceHint)) > 0
            }.ifEmpty {
                throw HlsResolutionException(
                    "CVH не нашёл выбранную озвучку для ${reference.episode}-й серии.",
                )
            }
        }
        val item = eligibleItems.maxByOrNull { candidate ->
            candidate.matchScore(voiceHints)
        } ?: eligibleItems.first()
        val voiceScore = item.matchScore(voiceHints)
        val videoId = item.vkId.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw HlsResolutionException("VideoHub вернул серию без идентификатора видео.")
        debug("video selected id=${videoId.take(12)} voiceScore=$voiceScore")
        val video = request<VideoHubVideo>(
            "$VIDEO_HUB_API/player/sv/video/${videoId.urlEncode()}",
        )
        val hlsUrl = video.sources?.hlsUrl
            ?.toHttpUri()
            ?.toASCIIString()
            ?: throw HlsResolutionException("VideoHub не вернул доступный HLS-поток.")
        debug("hls resolved ${hlsUrl.hlsDebugUrl()}")

        return PlaybackSource.DirectMedia(hlsUrl)
    }

    private fun resolveKodik(reference: KodikReference): PlaybackSource.DirectMedia {
        debug(
            "Kodik iframe parsed host=${reference.origin.host} " +
                "type=${reference.type} id=${reference.id}",
        )
        val pageUrl = reference.source.toASCIIString()
        val browserHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/json",
            "Referer" to "$YUMMY_ANIME_ORIGIN/",
            "User-Agent" to BROWSER_USER_AGENT,
        )
        val page = requestText(pageUrl, browserHeaders, "Kodik player page")
        val secureData = parseKodikSecureData(page, reference.id)
        debug(
            "Kodik secure data parsed refPresent=${secureData.ref.isNotBlank()}",
        )
        val playerPath = KODIK_PLAYER_SCRIPT_REGEX.find(page)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw HlsResolutionException(
                "Kodik не вернул адрес служебного скрипта. Возможно, источник недоступен в этом регионе.",
            )
        debug("Kodik player script path=${playerPath.substringBefore('?')}")
        val scriptUrl = reference.origin.resolve(playerPath).toASCIIString()
        val playerScript = requestText(scriptUrl, browserHeaders, "Kodik player script")
        val encodedEndpoint = KODIK_ENDPOINT_REGEX.find(playerScript)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf(String::isNotEmpty)
            ?: throw HlsResolutionException("Kodik изменил формат служебного запроса.")
        val endpoint = runCatching {
            String(Base64.getDecoder().decode(encodedEndpoint), StandardCharsets.UTF_8)
        }.getOrNull()
            ?.takeIf { it.startsWith('/') && !it.startsWith("//") }
            ?: throw HlsResolutionException("Kodik вернул некорректный служебный адрес.")
        debug("Kodik endpoint=$endpoint")
        val response = requestJson<KodikPlayerResponse>(
            url = reference.origin.resolve(endpoint).toASCIIString(),
            providerName = "Kodik",
            body = mapOf(
                "d" to secureData.d,
                "d_sign" to secureData.dSign,
                "pd" to secureData.pd,
                "pd_sign" to secureData.pdSign,
                "ref" to secureData.ref.urlDecodeOrSelf(),
                "ref_sign" to secureData.refSign,
                "type" to reference.type,
                "hash" to reference.hash,
                "id" to reference.id,
                "bad_user" to "false",
                "info" to "{}",
                "cdn_is_working" to "true",
            ),
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Origin" to reference.origin.toASCIIString().trimEnd('/'),
                "Referer" to reference.playerReferer(),
                "User-Agent" to BROWSER_USER_AGENT,
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )
        val encodedLink = KODIK_QUALITY_ORDER
            .asSequence()
            .flatMap { quality -> response.links[quality].orEmpty().asSequence() }
            .map(KodikLink::src)
            .firstOrNull(String::isNotBlank)
            ?: throw HlsResolutionException("Kodik не вернул доступный HLS-поток.")
        val hlsUrl = decodeKodikLink(encodedLink)
            ?: throw HlsResolutionException("Не удалось декодировать HLS-поток Kodik.")
        debug("Kodik hls resolved ${hlsUrl.hlsDebugUrl()}")
        return PlaybackSource.DirectMedia(hlsUrl)
    }

    private fun parseKodikSecureData(
        page: String,
        videoId: String,
    ): KodikSecureData {
        val marker = Regex(
            """(?:videoId\s*=\s*["']${Regex.escape(videoId)}["']|""" +
                """serialId\s*=\s*Number\(\s*${Regex.escape(videoId)}\s*\))""",
        )
        val secureScript = KODIK_INLINE_SCRIPT_REGEX
            .findAll(page)
            .map { it.groupValues[1] }
            .firstOrNull(marker::containsMatchIn)
            ?: throw HlsResolutionException(
                "Kodik не вернул подписанные параметры текущего видео.",
            )
        val secureJson = KODIK_SECURE_JSON_REGEX.find(secureScript)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw HlsResolutionException(
                "Kodik изменил формат подписанных параметров.",
            )
        return try {
            json.decodeFromString<KodikSecureData>(secureJson)
        } catch (error: Exception) {
            throw HlsResolutionException(
                "Kodik вернул некорректные подписанные параметры.",
                error,
            )
        }
    }

    private inline fun <reified T> request(url: String): T {
        return requestJson(
            url = url,
            providerName = "VideoHub",
            headers = mapOf(
                "Accept" to "application/json",
                "Origin" to YUMMY_ANIME_ORIGIN,
                "Referer" to "$YUMMY_ANIME_ORIGIN/",
                "User-Agent" to "Hoshira/0.4",
            ),
        )
    }

    private inline fun <reified T> requestJson(
        url: String,
        providerName: String,
        headers: Map<String, String>,
        body: Map<String, String>? = null,
    ): T {
        val response = try {
            if (body == null) {
                httpClient.get(url, headers)
            } else {
                httpClient.postForm(url, body, headers)
            }
        } catch (error: HlsResolutionException) {
            throw error
        } catch (error: Exception) {
            throw HlsResolutionException(
                "Не удалось получить HLS-поток: ${error.message ?: "ошибка сети"}.",
                error,
            )
        }
        return try {
            json.decodeFromString<T>(response).also {
                debug(
                    "${if (body == null) "GET" else "POST"} ${url.hlsDebugUrl()} " +
                        "ok provider=$providerName bytes=${response.length}",
                )
            }
        } catch (error: Exception) {
            throw HlsResolutionException("$providerName вернул неожиданный ответ.", error)
        }
    }

    private fun requestText(
        url: String,
        headers: Map<String, String>,
        providerName: String,
    ): String = try {
        httpClient.get(url, headers).also {
            debug("GET ${url.hlsDebugUrl()} ok provider=$providerName bytes=${it.length}")
        }
    } catch (error: HlsResolutionException) {
        throw error
    } catch (error: Exception) {
        throw HlsResolutionException(
            "Не удалось открыть $providerName: ${error.message ?: "ошибка сети"}.",
            error,
        )
    }
}

internal interface HlsHttpClient {
    fun get(url: String, headers: Map<String, String>): String

    fun postForm(
        url: String,
        body: Map<String, String>,
        headers: Map<String, String>,
    ): String {
        throw UnsupportedOperationException("POST is not implemented")
    }
}

private object UrlConnectionHlsHttpClient : HlsHttpClient {
    override fun get(url: String, headers: Map<String, String>): String {
        return request(url, "GET", headers, null)
    }

    override fun postForm(
        url: String,
        body: Map<String, String>,
        headers: Map<String, String>,
    ): String {
        val encodedBody = body.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }.toByteArray(StandardCharsets.UTF_8)
        return request(
            url = url,
            method = "POST",
            headers = headers + (
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            ),
            body = encodedBody,
        )
    }

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): String {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            headers.forEach(::setRequestProperty)
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body) }
            }
            val statusCode = connection.responseCode
            val response = (if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                throw HlsResolutionException(
                    "${URI(url).host ?: "Источник"} вернул HTTP $statusCode.",
                )
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}

internal enum class HlsProvider(val displayName: String) {
    DIRECT("Прямой HLS"),
    CVH("CVH"),
    KODIK("Kodik"),
    ALLOHA("Alloha"),
    UNKNOWN("Неизвестный"),
    INVALID("Некорректная ссылка"),
}

internal data class HlsSourceDiagnostic(
    val provider: HlsProvider,
    val supported: Boolean,
    val detail: String? = null,
)

internal class HlsResolutionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

private data class VideoHubReference(
    val animeId: String,
    val episode: Int,
    val voiceHints: List<String>,
)

private data class KodikReference(
    val source: URI,
    val origin: URI,
    val type: String,
    val id: String,
    val hash: String,
)

@Serializable
private data class KodikSecureData(
    val d: String,
    @SerialName("d_sign")
    val dSign: String,
    val pd: String,
    @SerialName("pd_sign")
    val pdSign: String,
    val ref: String,
    @SerialName("ref_sign")
    val refSign: String,
)

@Serializable
private data class VideoHubPlaylist(
    val items: List<VideoHubPlaylistItem> = emptyList(),
)

@Serializable
private data class VideoHubPlaylistItem(
    @SerialName("vkId")
    val vkId: JsonPrimitive,
    val voiceStudio: String? = null,
    val voiceType: String? = null,
    val episode: Int? = null,
)

@Serializable
private data class VideoHubVideo(
    val sources: VideoHubSources? = null,
)

@Serializable
private data class VideoHubSources(
    val hlsUrl: String? = null,
)

@Serializable
private data class KodikPlayerResponse(
    val links: Map<String, List<KodikLink>> = emptyMap(),
)

@Serializable
private data class KodikLink(
    val src: String = "",
)

private fun URI.toVideoHubReference(): VideoHubReference? {
    val isYummyAnimeHost = host
        ?.lowercase(Locale.ROOT)
        ?.let { it == "yummyani.me" || it.endsWith(".yummyani.me") }
        ?: false
    if (!isYummyAnimeHost || !path.endsWith("/iframeCVH.html", ignoreCase = true)) {
        return null
    }

    val parameters = runCatching {
        rawQuery
            ?.split('&')
            ?.mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator < 0) return@mapNotNull null
                pair.substring(0, separator).urlDecode() to
                    pair.substring(separator + 1).urlDecode()
            }
            ?.groupBy({ it.first }, { it.second })
            .orEmpty()
    }.getOrNull() ?: return null
    val animeId = parameters["anime_id"]
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val episode = parameters["episode"]
        ?.firstOrNull()
        ?.toDoubleOrNull()
        ?.toInt()
        ?.takeIf { it > 0 }
        ?: return null
    val voiceHints = listOf("dubbing", "dubbing_code")
        .flatMap { parameters[it].orEmpty() }
        .map(String::trim)
        .filter(String::isNotEmpty)

    return VideoHubReference(animeId, episode, voiceHints)
}

private fun URI.toKodikReference(): KodikReference? {
    val normalizedHost = host?.lowercase(Locale.ROOT) ?: return null
    if (normalizedHost !in KODIK_HOSTS) return null
    val parts = path.orEmpty()
        .trim('/')
        .split('/')
        .filter(String::isNotBlank)
    if (parts.size < 3) return null
    val type = parts[0].lowercase(Locale.ROOT)
        .takeIf { it.matches(KODIK_TYPE_REGEX) }
        ?: return null
    val id = parts[1].takeIf { it.all(Char::isDigit) } ?: return null
    val hash = parts[2].lowercase(Locale.ROOT)
        .takeIf { it.matches(KODIK_HASH_REGEX) }
        ?: return null
    val origin = URI(scheme.lowercase(Locale.ROOT), null, host, port, "/", null, null)
    return KodikReference(
        source = this,
        origin = origin,
        type = type,
        id = id,
        hash = hash,
    )
}

private fun KodikReference.playerReferer(): String =
    origin.resolve("/$type/$id/$hash/360p").toASCIIString()

private fun URI.isAllohaPlayer(): Boolean {
    val normalizedHost = host?.lowercase(Locale.ROOT) ?: return false
    return normalizedHost == ALLOHA_HOST || normalizedHost.endsWith(".$ALLOHA_HOST")
}

private fun unsupportedProvider(uri: URI): HlsResolutionException {
    return if (uri.isAllohaPlayer()) {
        HlsResolutionException(
            "Найден Alloha, но его HLS требует выполнения JavaScript/WebSocket " +
                "и динамических заголовков. Подробности записаны в диагностике.",
        )
    } else {
        HlsResolutionException(
            "Этот источник пока не поддерживает прямое HLS-воспроизведение.",
        )
    }
}

private fun decodeKodikLink(encoded: String): String? {
    val direct = encoded.trim().let {
        when {
            it.startsWith("//") -> "https:$it"
            it.startsWith("https://", ignoreCase = true) -> it
            it.startsWith("http://", ignoreCase = true) -> it
            else -> null
        }
    }
    if (direct != null) {
        return direct.toHttpUri()?.toASCIIString()
    }
    for (shift in 0..26) {
        val shifted = buildString(encoded.length) {
            encoded.forEach { character ->
                append(
                    when (character) {
                        in 'a'..'z' ->
                            'a' + ((character - 'a' - shift + 26) % 26)
                        in 'A'..'Z' ->
                            'A' + ((character - 'A' - shift + 26) % 26)
                        else -> character
                    },
                )
            }
        }
        val padded = shifted.padEnd((shifted.length + 3) / 4 * 4, '=')
        val decoded = runCatching {
            String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
        }.getOrNull()?.trim() ?: continue
        val normalized = when {
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("https://", ignoreCase = true) -> decoded
            decoded.startsWith("http://", ignoreCase = true) -> decoded
            else -> continue
        }
        val uri = normalized.toHttpUri() ?: continue
        if (
            uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true) ||
            normalized.contains(":hls:manifest.m3u8", ignoreCase = true)
        ) {
            return uri.toASCIIString()
        }
    }
    return null
}

private fun VideoHubPlaylistItem.matchScore(hints: List<String>): Int {
    if (hints.isEmpty()) return 0
    val candidateValues = listOfNotNull(voiceStudio, voiceType)
        .map(String::normalizedVoiceName)
        .filter(String::isNotEmpty)
    return hints.mapIndexed { index, hint ->
        val normalizedHint = hint.normalizedVoiceName()
        val nameScore = candidateValues.maxOfOrNull { candidate ->
            when {
                candidate == normalizedHint -> 100
                candidate.contains(normalizedHint) || normalizedHint.contains(candidate) -> 50
                else -> 0
            }
        } ?: 0
        if (nameScore == 0) {
            0
        } else {
            nameScore + (hints.size - index) * 1_000
        }
    }.maxOrNull() ?: 0
}

private fun String.normalizedVoiceName(): String = lowercase(Locale.ROOT)
    .replace(Regex("""^(озвучка|субтитры)\s*"""), "")
    .filter(Char::isLetterOrDigit)

private fun String.toHttpUri(): URI? = runCatching { URI(trim()) }
    .getOrNull()
    ?.takeIf {
        (it.scheme.equals("http", true) || it.scheme.equals("https", true)) &&
            !it.host.isNullOrBlank()
    }

private fun String.urlDecode(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8)

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8)

internal fun String.hlsDebugUrl(): String {
    val uri = toHttpUri() ?: return "<invalid-url>"
    val queryKeys = uri.rawQuery
        ?.split('&')
        ?.map { it.substringBefore('=').urlDecodeSafely() }
        ?.filter(String::isNotBlank)
        ?.distinct()
        .orEmpty()
    return buildString {
        append(uri.scheme.lowercase(Locale.ROOT))
        append("://")
        append(uri.host.lowercase(Locale.ROOT))
        uri.port.takeIf { it >= 0 }?.let { append(":$it") }
        append(uri.rawPath.orEmpty())
        if (queryKeys.isNotEmpty()) {
            append("?<")
            append(queryKeys.joinToString(","))
            append('>')
        }
    }
}

private fun String.urlDecodeSafely(): String =
    runCatching(::urlDecode).getOrDefault("<invalid>")

private fun String.urlDecodeOrSelf(): String =
    runCatching(::urlDecode).getOrDefault(this)

private const val VIDEO_HUB_API = "https://plapi.cdnvideohub.com/api/v1"
private const val VIDEO_HUB_PUBLISHER = 745
private const val VIDEO_HUB_AGGREGATOR = "mali"
private const val YUMMY_ANIME_ORIGIN = "https://ru.yummyani.me"
private const val ALLOHA_HOST = "alloha.yani.tv"
private val KODIK_HOSTS = setOf("kodikplayer.com", "kodik.info", "aniqit.com")
private val KODIK_TYPE_REGEX = Regex("""[a-z][a-z0-9_-]*""")
private val KODIK_HASH_REGEX = Regex("""[a-z0-9]{16,128}""")
private val KODIK_PLAYER_SCRIPT_REGEX = Regex(
    """<script[^>]+src=["'](/assets/js/app\.player_single[^"']+)["']""",
    RegexOption.IGNORE_CASE,
)
private val KODIK_INLINE_SCRIPT_REGEX = Regex(
    """<script\b[^>]*>([\s\S]*?)</script>""",
    RegexOption.IGNORE_CASE,
)
private val KODIK_SECURE_JSON_REGEX = Regex(
    """'\s*(\{[^']+})\s*'""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val KODIK_ENDPOINT_REGEX = Regex(
    """url:\s*atob\(["']([A-Za-z0-9+/=]+)["']\)""",
)
private val KODIK_QUALITY_ORDER = listOf("720", "480", "360")
private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
private const val CONNECT_TIMEOUT_MS = 12_000
private const val READ_TIMEOUT_MS = 24_000
