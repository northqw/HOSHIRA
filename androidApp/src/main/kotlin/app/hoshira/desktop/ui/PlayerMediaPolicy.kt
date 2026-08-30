package app.hoshira.desktop.ui

internal fun stableMediaCacheKey(url: String): String = url

internal fun shouldReresolveMediaSource(statusCode: Int): Boolean =
    statusCode in EXPIRED_MEDIA_HTTP_STATUS_CODES

internal fun playerUiRefreshIntervalMillis(controlsVisible: Boolean): Long =
    if (controlsVisible) VISIBLE_PLAYER_UI_REFRESH_MILLIS else HIDDEN_PLAYER_UI_REFRESH_MILLIS

internal fun playbackReportIntervalMillis(controlsVisible: Boolean): Long =
    if (controlsVisible) VISIBLE_PLAYBACK_REPORT_MILLIS else HIDDEN_PLAYBACK_REPORT_MILLIS

private val EXPIRED_MEDIA_HTTP_STATUS_CODES = setOf(400, 401, 403, 404, 410)
private const val VISIBLE_PLAYER_UI_REFRESH_MILLIS = 250L
private const val HIDDEN_PLAYER_UI_REFRESH_MILLIS = 1_500L
private const val VISIBLE_PLAYBACK_REPORT_MILLIS = 1_000L
private const val HIDDEN_PLAYBACK_REPORT_MILLIS = 5_000L
