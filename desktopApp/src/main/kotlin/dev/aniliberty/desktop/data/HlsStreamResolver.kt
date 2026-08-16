package dev.aniliberty.desktop.data

import java.net.HttpURLConnection
import java.net.CookieManager
import java.net.CookiePolicy
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
        preferredQuality: String? = null,
        preferHls: Boolean = false,
    ): PlaybackSource.DirectMedia {
        debug("resolver input ${url.hlsDebugUrl()}")
        val uri = url.toHttpUri()
            ?: throw HlsResolutionException("Источник вернул некорректную ссылку.")
        if (uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)) {
            debug("direct m3u8 accepted")
            return PlaybackSource.DirectMedia(uri.toASCIIString())
        }

        uri.toKodikReference()?.let { reference ->
            return resolveKodik(reference, preferredQuality)
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
        val source = video.sources
            ?.toDirectMedia(preferredQuality, preferHls)
            ?: throw HlsResolutionException("VideoHub не вернул доступный медиапоток.")
        debug(
            "CVH media resolved quality=${source.quality ?: "adaptive"} " +
                "available=${source.availableQualities.joinToString(",")} " +
                source.url.hlsDebugUrl(),
        )

        return source
    }

    private fun resolveKodik(
        reference: KodikReference,
        preferredQuality: String?,
    ): PlaybackSource.DirectMedia {
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
        val videoInfo = parseKodikVideoInfo(page, reference)
        debug(
            "Kodik vInfo parsed type=${videoInfo.type} id=${videoInfo.id} " +
                "pathMatch=${videoInfo.matches(reference)} " +
                "linkPresent=${videoInfo.link != null} " +
                "secretPresent=${videoInfo.secret != null} " +
                "uidPresent=${videoInfo.uid != null}",
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
        val endpointUrl = reference.origin.resolve(endpoint).toASCIIString()
        val playerHeaders = mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Origin" to reference.origin.toASCIIString().trimEnd('/'),
            "Referer" to pageUrl,
            "User-Agent" to BROWSER_USER_AGENT,
            "X-Requested-With" to "XMLHttpRequest",
        )
        val currentRequestUrl = buildString {
            append(endpointUrl)
            append(if ('?' in endpointUrl) '&' else '?')
            append("type=${videoInfo.type.urlEncode()}")
            append("&id=${videoInfo.id.urlEncode()}")
            append("&hash=${videoInfo.hash.urlEncode()}")
        }
        debug("Kodik request mode=GET queryKeys=type,id,hash")
        val response = try {
            requestJson<KodikPlayerResponse>(
                url = currentRequestUrl,
                providerName = "Kodik",
                headers = playerHeaders,
            )
        } catch (currentError: HlsResolutionException) {
            debug(
                "Kodik GET failed type=${currentError::class.simpleName} " +
                    "message=${currentError.message}; trying legacy POST",
            )
            val legacyBody = linkedMapOf(
                "d" to secureData.d,
                "d_sign" to secureData.dSign,
                "pd" to secureData.pd,
                "pd_sign" to secureData.pdSign,
                "ref" to secureData.ref.decodeURIComponentOrSelf(),
                "ref_sign" to secureData.refSign,
                "type" to videoInfo.type,
                "hash" to videoInfo.hash,
                "id" to videoInfo.id,
                "bad_user" to "false",
                "cdn_is_working" to "true",
            ).apply {
                videoInfo.link?.let { put("link", it) }
                videoInfo.secret?.let { put("secret", it) }
                videoInfo.uid?.let { put("uid", it) }
            }
            debug("Kodik request mode=POST bodyKeys=${legacyBody.keys.joinToString(",")}")
            requestJson<KodikPlayerResponse>(
                url = endpointUrl,
                providerName = "Kodik",
                body = legacyBody,
                headers = playerHeaders,
            )
        }
        val availableQualityKeys = response.links
            .filterValues { links -> links.any { it.src.isNotBlank() } }
            .keys
            .sortedWith(
                compareByDescending<String> { it.toIntOrNull() ?: Int.MIN_VALUE }
                    .thenByDescending { it },
            )
        val preferredQualityKey = preferredQuality.normalizedQualityKey()
        val qualityOrder = listOfNotNull(preferredQualityKey)
            .plus(KODIK_QUALITY_ORDER)
            .plus(availableQualityKeys)
            .distinct()
        val selectedQualityKey = qualityOrder.firstOrNull { quality ->
            response.links[quality].orEmpty().any { it.src.isNotBlank() }
        }
            ?: throw HlsResolutionException("Kodik не вернул доступный HLS-поток.")
        val encodedLink = response.links[selectedQualityKey]
            .orEmpty()
            .asSequence()
            .map(KodikLink::src)
            .firstOrNull(String::isNotBlank)
            ?: throw HlsResolutionException("Kodik не вернул доступный HLS-поток.")
        val hlsUrl = decodeKodikLink(encodedLink)
            ?: throw HlsResolutionException("Не удалось декодировать HLS-поток Kodik.")
        val selectedQuality = selectedQualityKey.qualityLabel()
        val availableQualities = availableQualityKeys.map(String::qualityLabel)
        debug(
            "Kodik hls resolved quality=$selectedQuality " +
                "available=${availableQualities.joinToString(",")} ${hlsUrl.hlsDebugUrl()}",
        )
        return PlaybackSource.DirectMedia(
            url = hlsUrl,
            quality = selectedQuality,
            availableQualities = availableQualities,
        )
    }

    private fun parseKodikSecureData(
        page: String,
        videoId: String,
    ): KodikSecureData {
        val marker = Regex(
            """(?:videoId\s*=\s*["']${Regex.escape(videoId)}["']|""" +
                """serialId\s*=\s*Number\(\s*${Regex.escape(videoId)}\s*\))""",
        )
        val scripts = KODIK_INLINE_SCRIPT_REGEX
            .findAll(page)
            .map { it.groupValues[1] }
            .toList()
        val prioritizedScripts = scripts
            .filter(marker::containsMatchIn)
            .plus(scripts)
            .distinct()
        for (script in prioritizedScripts) {
            for (match in KODIK_SECURE_JSON_REGEX.findAll(script)) {
                val secureJson = match.groupValues
                    .getOrNull(1)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: continue
                val secureData = runCatching {
                    json.decodeFromString<KodikSecureData>(secureJson)
                }.getOrNull()
                if (secureData != null) {
                    return secureData
                }
            }
        }
        throw HlsResolutionException(
            "Kodik не вернул подписанные параметры текущего видео.",
        )
    }

    private fun parseKodikVideoInfo(
        page: String,
        fallback: KodikReference,
    ): KodikVideoInfo {
        val objectBody = KODIK_VINFO_OBJECT_REGEX.find(page)
            ?.groupValues
            ?.getOrNull(1)
        fun field(name: String): String? {
            val escapedName = Regex.escape(name)
            val assignment = Regex(
                """\bvInfo(?:\.$escapedName|\[\s*["']$escapedName["']\s*])""" +
                    """\s*=\s*$KODIK_JS_VALUE_PATTERN""",
            ).find(page)
            if (assignment != null) {
                return assignment.kodikJavaScriptValue()
            }
            if (objectBody == null) return null
            return Regex(
                """(?:^|,)\s*["']?$escapedName["']?\s*:\s*""" +
                    KODIK_JS_VALUE_PATTERN,
            ).find(objectBody)?.kodikJavaScriptValue()
        }

        return KodikVideoInfo(
            type = field("type")?.takeIf(String::isNotBlank) ?: fallback.type,
            id = field("id")?.takeIf(String::isNotBlank) ?: fallback.id,
            hash = field("hash")?.takeIf(String::isNotBlank) ?: fallback.hash,
            link = field("link")?.takeIf(String::isNotBlank),
            secret = field("secret")?.takeIf(String::isNotBlank),
            uid = field("uid")?.takeIf(String::isNotBlank),
        )
    }

    private inline fun <reified T> request(url: String): T {
        return requestJson(
            url = url,
            providerName = "VideoHub",
            headers = mapOf(
                "Accept" to "application/json",
                "Origin" to YUMMY_ANIME_ORIGIN,
                "Referer" to "$YUMMY_ANIME_ORIGIN/",
                "User-Agent" to VIDEO_HUB_USER_AGENT,
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
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

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
        val uri = URI(url)
        val sessionHeaders = synchronized(cookieManager) {
            cookieManager.get(uri, emptyMap())
        }
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            sessionHeaders.forEach { (name, values) ->
                values.forEach { value -> addRequestProperty(name, value) }
            }
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
            synchronized(cookieManager) {
                cookieManager.put(
                    uri,
                    connection.headerFields.entries
                        .filter { it.key != null }
                        .associate { it.key to it.value },
                )
            }
            val response = (if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                val responseType = connection.contentType
                    ?.substringBefore(';')
                    ?.takeIf(String::isNotBlank)
                    ?: "unknown"
                throw HlsResolutionException(
                    "${URI(url).host ?: "Источник"} вернул HTTP $statusCode " +
                        "($method, type=$responseType, bytes=${response.length}).",
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

private data class KodikVideoInfo(
    val type: String,
    val id: String,
    val hash: String,
    val link: String?,
    val secret: String?,
    val uid: String?,
) {
    fun matches(reference: KodikReference): Boolean =
        type == reference.type &&
            id == reference.id &&
            hash == reference.hash
}

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
    val mpeg4kUrl: String? = null,
    val mpegQhdUrl: String? = null,
    val mpegFullHdUrl: String? = null,
    val mpegHighUrl: String? = null,
    val mpegMediumUrl: String? = null,
    val mpegLowUrl: String? = null,
    val mpegLowestUrl: String? = null,
    val mpegTinyUrl: String? = null,
)

private fun VideoHubSources.toDirectMedia(
    preferredQuality: String?,
    preferHls: Boolean = false,
): PlaybackSource.DirectMedia? {
    // VideoHub signs media URLs for the same user agent that requested the API.
    // Sending Media3's own user agent makes the CDN reject an otherwise valid URL with HTTP 400.
    val playbackHeaders = mapOf(
        "User-Agent" to VIDEO_HUB_USER_AGENT,
    )
    val hls = hlsUrl?.toHttpUri()?.toASCIIString()
    if (preferHls && hls != null) {
        return PlaybackSource.DirectMedia(
            url = hls,
            headers = playbackHeaders,
        )
    }
    val mp4ByQuality = linkedMapOf(
        "2160" to mpeg4kUrl,
        "1440" to mpegQhdUrl,
        "1080" to mpegFullHdUrl,
        "720" to mpegHighUrl,
        "480" to mpegMediumUrl,
        "360" to mpegLowUrl,
        "240" to mpegLowestUrl,
        "144" to mpegTinyUrl,
    ).mapValues { (_, url) ->
        url?.toHttpUri()?.toASCIIString()
    }.filterValues { it != null }
    if (mp4ByQuality.isNotEmpty()) {
        val preferred = preferredQuality.normalizedQualityKey()
        val selectedQuality = listOfNotNull(preferred)
            .plus(VIDEO_HUB_QUALITY_ORDER)
            .firstOrNull(mp4ByQuality::containsKey)
            ?: mp4ByQuality.keys.first()
        return PlaybackSource.DirectMedia(
            url = requireNotNull(mp4ByQuality[selectedQuality]),
            headers = playbackHeaders,
            quality = selectedQuality.qualityLabel(),
            availableQualities = mp4ByQuality.keys.map(String::qualityLabel),
        )
    }
    return hls?.let {
        PlaybackSource.DirectMedia(
            url = it,
            headers = playbackHeaders,
        )
    }
}

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

private fun String?.normalizedQualityKey(): String? = this
    ?.trim()
    ?.lowercase(Locale.ROOT)
    ?.removeSuffix("p")
    ?.takeIf { it.isNotBlank() && it != "auto" && it.all(Char::isDigit) }

private fun String.qualityLabel(): String = if (all(Char::isDigit)) "${this}p" else this

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

private fun String.decodeURIComponentOrSelf(): String =
    runCatching {
        URLDecoder.decode(
            replace("+", "%2B"),
            StandardCharsets.UTF_8,
        )
    }.getOrDefault(this)

private fun MatchResult.kodikJavaScriptValue(): String? {
    val stringValue = groups[1]?.value ?: groups[2]?.value
    if (stringValue != null) {
        return stringValue.decodeJavaScriptString()
    }
    return groups[3]?.value ?: groups[4]?.value
}

private fun String.decodeJavaScriptString(): String {
    val result = StringBuilder(length)
    var index = 0
    while (index < length) {
        val current = this[index++]
        if (current != '\\' || index >= length) {
            result.append(current)
            continue
        }
        val escaped = this[index++]
        when (escaped) {
            '\\', '/', '"', '\'' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u', 'x' -> {
                val digitCount = if (escaped == 'u') 4 else 2
                val end = index + digitCount
                val decoded = takeIf { end <= length }
                    ?.substring(index, end)
                    ?.takeIf { digits ->
                        digits.all {
                            it in '0'..'9' ||
                                it in 'a'..'f' ||
                                it in 'A'..'F'
                        }
                    }
                    ?.toIntOrNull(16)
                if (decoded == null) {
                    result.append('\\').append(escaped)
                } else {
                    result.append(decoded.toChar())
                    index = end
                }
            }
            '\n' -> Unit
            '\r' -> if (index < length && this[index] == '\n') index++
            else -> result.append(escaped)
        }
    }
    return result.toString()
}

private const val VIDEO_HUB_API = "https://plapi.cdnvideohub.com/api/v1"
private const val VIDEO_HUB_PUBLISHER = 745
private const val VIDEO_HUB_AGGREGATOR = "mali"
private const val VIDEO_HUB_USER_AGENT = "Hoshira/0.4"
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
    """'\s*(\{[^']+\})\s*'""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val KODIK_VINFO_OBJECT_REGEX = Regex(
    """\bvInfo\s*=\s*\{([\s\S]*?)\}\s*;""",
)
private const val KODIK_JS_VALUE_PATTERN =
    """(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|Number\(\s*(\d+)\s*\)|(\d+))"""
private val KODIK_ENDPOINT_REGEX = Regex(
    """url:\s*atob\(["']([A-Za-z0-9+/=]+)["']\)""",
)
private val KODIK_QUALITY_ORDER = listOf("720", "480", "360")
private val VIDEO_HUB_QUALITY_ORDER = listOf(
    "2160",
    "1440",
    "1080",
    "720",
    "480",
    "360",
    "240",
    "144",
)
private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
private const val CONNECT_TIMEOUT_MS = 12_000
private const val READ_TIMEOUT_MS = 24_000
