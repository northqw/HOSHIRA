package dev.aniliberty.desktop.data

/**
 * Источники намеренно разделены: iframe-страница не является медиапотоком и
 * не должна незаметно попадать в нативный движок.
 */
sealed interface PlaybackSource {
    data class DirectMedia(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : PlaybackSource

    data class ExternalPlayerPage(
        val url: String,
    ) : PlaybackSource
}

/**
 * Платформенная граница будущего плеера. Desktop сможет использовать libmpv,
 * Android/TV — Media3, не меняя экран и модель эпизода.
 */
interface PlayerEngine {
    fun supports(source: PlaybackSource): Boolean
    suspend fun play(source: PlaybackSource)
    suspend fun stop()
}

object PendingDesktopPlayer : PlayerEngine {
    override fun supports(source: PlaybackSource): Boolean = false

    override suspend fun play(source: PlaybackSource) {
        throw PlayerUnavailableException
    }

    override suspend fun stop() = Unit
}

data object PlayerUnavailableException :
    IllegalStateException("Для нативного desktop-плеера нужен прямой HLS/MP4-поток")
