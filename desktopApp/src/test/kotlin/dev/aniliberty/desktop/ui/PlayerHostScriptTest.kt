package dev.aniliberty.desktop.ui

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlayerHostScriptTest {
    @Test
    fun `host script installs custom controls and video adapter`() {
        val script = playerHostScript(
            playerUrl = "https://player.example/episode",
            chrome = EmbeddedPlayerChrome(
                title = "Название",
                subtitle = "1 серия · Озвучка",
                position = "1 из 12",
                hasPrevious = false,
                hasNext = true,
                sources = listOf(
                    EmbeddedPlayerSource(
                        episodeId = "episode-1",
                        label = "Kodik",
                        selected = true,
                    ),
                ),
            ),
        )

        val decodedPayloads = Regex("""decode\("([^"]+)"\)""")
            .findAll(script)
            .map { match ->
                String(
                    Base64.getDecoder().decode(match.groupValues[1]),
                    StandardCharsets.UTF_8,
                )
            }
            .toList()

        assertEquals(3, decodedPayloads.size)
        val css = decodedPayloads[1]
        val markup = decodedPayloads[2]

        listOf(
            "play-toggle",
            "rewind",
            "forward",
            "seek-range",
            "mute-toggle",
            "volume-range",
            "quality-toggle",
            "quality-options",
            "fullscreen-toggle",
            "player-loading",
        ).forEach { id ->
            assertContains(markup, """id="$id"""")
        }
        assertContains(css, ".player-controls")
        assertContains(css, "background: #090a0c")
        assertContains(css, "width: 38px")
        assertContains(markup, "Загружаем плеер…")
        assertFalse(markup.contains("loading-emblem"))
        assertContains(script, ".allplay__controls")
        assertContains(script, ".vjs-control-bar")
        assertContains(script, "querySelectorAll('video')")
        assertContains(script, "new MutationObserver(scheduleScan)")
        assertContains(script, "activeVideo.controls = false")
        assertContains(script, "bootstrapAllohaPlayback")
        assertContains(script, "element.shadowRoot")
        assertContains(script, "collectAccessibleRoots")
        assertContains(script, "discoverQualityOptions")
        assertContains(script, "activateProviderQuality")
        assertContains(script, "hidePlayerLoading")
    }

    @Test
    fun `Kodik ad matcher blocks advertising resources but keeps media`() {
        kotlin.test.assertTrue(
            shouldBlockKodikRequest("https://ads.adfox.ru/preroll/vast?slot=42"),
        )
        kotlin.test.assertTrue(
            shouldBlockKodikRequest("https://kodikplayer.com/assets/preroll/config.json"),
        )
        kotlin.test.assertFalse(
            shouldBlockKodikRequest("https://video.kodik-cdn.example/episode/master.m3u8"),
        )
    }
}
