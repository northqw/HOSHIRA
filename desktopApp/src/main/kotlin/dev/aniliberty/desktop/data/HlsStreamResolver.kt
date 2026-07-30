package dev.aniliberty.desktop.data

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Resolves the lightweight YummyAnime VideoHub iframe into a direct HLS stream.
 *
 * The provider's own player performs the same two public JSON requests. Keeping
 * that exchange here means the Windows player never has to execute a web page.
 */
internal class HlsStreamResolver(
    private val httpClient: HlsHttpClient = UrlConnectionHlsHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun supports(url: String?): Boolean {
        val uri = url?.toHttpUri() ?: return false
        return uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true) ||
            uri.toVideoHubReference() != null
    }

    fun resolve(url: String): PlaybackSource.DirectMedia {
        val uri = url.toHttpUri()
            ?: throw HlsResolutionException("Источник вернул некорректную ссылку.")
        if (uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)) {
            return PlaybackSource.DirectMedia(uri.toASCIIString())
        }

        val reference = uri.toVideoHubReference()
            ?: throw HlsResolutionException(
                "Этот источник пока не поддерживает прямое HLS-воспроизведение.",
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
        if (matchingEpisode.isEmpty()) {
            throw HlsResolutionException(
                "VideoHub не нашёл HLS-поток для ${reference.episode}-й серии.",
            )
        }

        val item = matchingEpisode.maxByOrNull { candidate ->
            candidate.matchScore(reference.voiceHints)
        } ?: matchingEpisode.first()
        val videoId = item.vkId.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw HlsResolutionException("VideoHub вернул серию без идентификатора видео.")
        val video = request<VideoHubVideo>(
            "$VIDEO_HUB_API/player/sv/video/${videoId.urlEncode()}",
        )
        val hlsUrl = video.sources?.hlsUrl
            ?.toHttpUri()
            ?.toASCIIString()
            ?: throw HlsResolutionException("VideoHub не вернул доступный HLS-поток.")

        return PlaybackSource.DirectMedia(hlsUrl)
    }

    private inline fun <reified T> request(url: String): T {
        val response = try {
            httpClient.get(
                url,
                mapOf(
                    "Accept" to "application/json",
                    "Origin" to YUMMY_ANIME_ORIGIN,
                    "Referer" to "$YUMMY_ANIME_ORIGIN/",
                    "User-Agent" to "Hoshira/0.4",
                ),
            )
        } catch (error: HlsResolutionException) {
            throw error
        } catch (error: Exception) {
            throw HlsResolutionException(
                "Не удалось получить HLS-поток: ${error.message ?: "ошибка сети"}.",
                error,
            )
        }
        return try {
            json.decodeFromString<T>(response)
        } catch (error: Exception) {
            throw HlsResolutionException("Источник вернул неожиданный ответ.", error)
        }
    }
}

internal fun interface HlsHttpClient {
    fun get(url: String, headers: Map<String, String>): String
}

private object UrlConnectionHlsHttpClient : HlsHttpClient {
    override fun get(url: String, headers: Map<String, String>): String {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            headers.forEach(::setRequestProperty)
        }
        try {
            val statusCode = connection.responseCode
            val response = (if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                throw HlsResolutionException("VideoHub вернул HTTP $statusCode.")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}

internal class HlsResolutionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

private data class VideoHubReference(
    val animeId: String,
    val episode: Int,
    val voiceHints: List<String>,
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

private fun VideoHubPlaylistItem.matchScore(hints: List<String>): Int {
    if (hints.isEmpty()) return 0
    val candidateValues = listOfNotNull(voiceStudio, voiceType)
        .map(String::normalizedVoiceName)
        .filter(String::isNotEmpty)
    return hints.maxOf { hint ->
        val normalizedHint = hint.normalizedVoiceName()
        candidateValues.maxOfOrNull { candidate ->
            when {
                candidate == normalizedHint -> 100
                candidate.contains(normalizedHint) || normalizedHint.contains(candidate) -> 50
                else -> 0
            }
        } ?: 0
    }
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

private const val VIDEO_HUB_API = "https://plapi.cdnvideohub.com/api/v1"
private const val VIDEO_HUB_PUBLISHER = 745
private const val VIDEO_HUB_AGGREGATOR = "mali"
private const val YUMMY_ANIME_ORIGIN = "https://ru.yummyani.me"
private const val CONNECT_TIMEOUT_MS = 12_000
private const val READ_TIMEOUT_MS = 24_000
