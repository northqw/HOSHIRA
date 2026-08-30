package app.hoshira.desktop.ui

import android.view.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import app.hoshira.desktop.STREAMING_MEDIA_CACHE_MAX_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerMediaPolicyTest {
    @Test
    fun `streaming cache is capped at fifty MiB`() {
        assertEquals(50L * 1024L * 1024L, STREAMING_MEDIA_CACHE_MAX_BYTES)
    }

    @Test
    fun `cache key keeps signed query parameters`() {
        val signed = "https://cdn.example/video/segment.ts?token=abc&expires=123"

        assertEquals(signed, stableMediaCacheKey(signed))
    }

    @Test
    fun `expired and forbidden responses require fresh resolution`() {
        listOf(400, 401, 403, 404, 410).forEach { status ->
            assertTrue(shouldReresolveMediaSource(status), "status=$status")
        }
        listOf(408, 429, 500, 502, 503, 504).forEach { status ->
            assertFalse(shouldReresolveMediaSource(status), "status=$status")
        }
    }

    @Test
    fun `hidden controls reduce UI and persistence polling`() {
        assertEquals(250L, playerUiRefreshIntervalMillis(controlsVisible = true))
        assertEquals(1_500L, playerUiRefreshIntervalMillis(controlsVisible = false))
        assertEquals(1_000L, playbackReportIntervalMillis(controlsVisible = true))
        assertEquals(5_000L, playbackReportIntervalMillis(controlsVisible = false))
    }

    @Test
    fun `tv timeline sends dpad down to episode actions instead of seeking`() {
        assertTrue(
            shouldMoveTimelineFocusToEpisodeActions(
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                eventType = KeyEventType.KeyDown,
                isTelevision = true,
                hasEpisodeActions = true,
            ),
        )
        assertFalse(
            shouldMoveTimelineFocusToEpisodeActions(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                eventType = KeyEventType.KeyDown,
                isTelevision = true,
                hasEpisodeActions = true,
            ),
        )
        assertFalse(
            shouldMoveTimelineFocusToEpisodeActions(
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                eventType = KeyEventType.KeyDown,
                isTelevision = false,
                hasEpisodeActions = true,
            ),
        )
    }
}
