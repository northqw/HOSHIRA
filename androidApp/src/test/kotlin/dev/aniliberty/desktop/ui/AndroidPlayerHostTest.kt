package dev.aniliberty.desktop.ui

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidPlayerHostTest {
    @Test
    fun `host document embeds exact provider URL without document rewrite race`() {
        val playerUrl =
            "https://kodikplayer.com/season/120935/test/720p?episode=2&only_episode=true"
        val document = androidPlayerHostDocument(
            AndroidPlayerHostConfig(
                playerUrl = playerUrl,
                title = "Test",
                subtitle = "2 серия",
                position = "2 из 12",
                sources = emptyList(),
                resumeSeconds = 0.0,
                startupVolume = 1f,
                preferredQuality = "720p",
                hasPrevious = true,
                hasNext = true,
                controlsHideDelayMs = 3_000,
                showLoading = true,
            ),
        )
        val encodedUrl = Base64.getEncoder().encodeToString(
            playerUrl.toByteArray(StandardCharsets.UTF_8),
        )

        assertTrue(document.contains("id=\"hoshira-provider\""))
        assertTrue(document.contains("id=\"gesture-layer\""))
        assertTrue(document.contains("const playerUrl = decode(\"$encodedUrl\")"))
        assertTrue(document.contains("iframe.src = playerUrl"))
        assertTrue(document.contains("handleSingleTap"))
        assertTrue(document.contains("handleDoubleTap"))
        assertTrue(document.contains(".gesture-layer.active { pointer-events: auto; }"))
        assertTrue(document.contains("HoshiraAndroid.systemVolume()"))
        assertTrue(document.contains("HoshiraAndroid.setSystemVolume"))
        assertTrue(document.contains("class=\"volume-icon\""))
        assertTrue(document.contains("@media (min-width: 1500px)"))
        assertFalse(document.contains("document.open()"))
        assertFalse(document.contains("document.write("))
    }

    @Test
    fun `mobile and desktop share Kodik advertising request policy`() {
        assertTrue(shouldBlockKodikRequest("https://ads.adfox.ru/preroll/vast?slot=42"))
        assertTrue(shouldBlockKodikRequest("https://kodikplayer.com/assets/preroll/video.mp4"))
        assertFalse(
            shouldBlockKodikRequest("https://kodikplayer.com/assets/preroll/config.json"),
        )
        assertFalse(
            shouldBlockKodikRequest("https://video.kodik-cdn.example/episode/master.m3u8"),
        )
    }
}
