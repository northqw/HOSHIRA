package app.hoshira.desktop.ui

internal data class PlayerBufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
)

internal fun playerBufferProfile(isLowRamDevice: Boolean): PlayerBufferProfile =
    if (isLowRamDevice) LOW_RAM_BUFFER_PROFILE else DEFAULT_BUFFER_PROFILE

private val DEFAULT_BUFFER_PROFILE = PlayerBufferProfile(
    minBufferMs = 10_000,
    maxBufferMs = 30_000,
    bufferForPlaybackMs = 1_500,
    bufferForPlaybackAfterRebufferMs = 3_000,
    targetBufferBytes = 24 * MEBIBYTE,
)

private val LOW_RAM_BUFFER_PROFILE = PlayerBufferProfile(
    minBufferMs = 5_000,
    maxBufferMs = 15_000,
    bufferForPlaybackMs = 1_000,
    bufferForPlaybackAfterRebufferMs = 2_000,
    targetBufferBytes = 16 * MEBIBYTE,
)

private const val MEBIBYTE = 1024 * 1024
