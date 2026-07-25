package dev.aniliberty.desktop.ui

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlayerHostScriptTest {
    @Test
    fun `Alloha is preferred when a dubbing has multiple player sources`() {
        val sources = listOf("Kodik", "Sibnet", "Alloha")

        assertEquals(
            listOf("Alloha", "Kodik", "Sibnet"),
            sources.sortedBy(::playerSourcePriority),
        )
    }

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
            "loading-label",
            "loading-note",
        ).forEach { id ->
            assertContains(markup, """id="$id"""")
        }
        assertContains(css, ".player-controls")
        assertFalse(css.contains("border-radius: 26px"))
        assertFalse(css.contains(".quality-badge"))
        assertFalse(markup.contains("quality-badge"))
        assertContains(css, "background: #090a0c")
        assertContains(css, "width: 38px")
        assertContains(markup, "Загружаем плеер…")
        assertFalse(markup.contains("loading-emblem"))
        assertContains(script, ".allplay__controls")
        assertContains(script, ".vjs-control-bar")
        assertContains(script, "querySelectorAll('video')")
        assertContains(script, "new MutationObserver(() => {")
        assertContains(script, "activeVideo.controls = false")
        assertContains(script, "bootstrapAllohaPlayback")
        assertContains(script, "element.shadowRoot")
        assertContains(script, "collectAccessibleRoots")
        assertContains(script, "discoverQualityOptions")
        assertContains(script, "activateProviderQuality")
        assertContains(script, "postHostAction('fullscreen:' + requestedState)")
        assertContains(script, "event.code === 'Escape'")
        assertContains(script, "window.hoshiraSetFullscreenState")
        assertContains(script, "hidePlayerLoading")
        assertContains(script, "window.setInterval(scheduleScan, 160)")
        assertContains(script, "if (activeVideo?.isConnected)")
        assertContains(script, "providerRootsDirty")
        assertContains(script, "Загрузка может занять больше времени из-за обхода встроенной рекламы")
        assertContains(script, "discoverQualityOptions(lastProviderRoots)")
        assertFalse(script.contains("discoverQualityOptions(roots)"))
        assertFalse(script.contains("Ожидание видео от источника"))
        assertContains(script, "window.setTimeout(() => {")
        assertContains(script, "}, isKodikProvider ? 3000 : 6000);")
        assertFalse(script.contains("}, 6000);"))
        assertFalse(script.contains("}, 12000);"))
        assertFalse(script.contains("if (isKodikProvider && !activeVideo)"))
    }

    @Test
    fun `Kodik ad matcher blocks advertising resources but keeps media`() {
        kotlin.test.assertTrue(KODIK_WEB_RESOURCE_FILTERS.size > 1)
        kotlin.test.assertFalse(KODIK_WEB_RESOURCE_FILTERS.contains("*"))
        kotlin.test.assertTrue(KODIK_WEB_RESOURCE_FILTERS.any { "preroll" in it })
        kotlin.test.assertTrue(
            shouldBlockKodikRequest("https://ads.adfox.ru/preroll/vast?slot=42"),
        )
        kotlin.test.assertFalse(
            shouldBlockKodikRequest("https://kodikplayer.com/assets/preroll/config.json"),
        )
        kotlin.test.assertFalse(
            shouldBlockKodikRequest("https://video.kodik-cdn.example/episode/master.m3u8"),
        )
        kotlin.test.assertTrue(
            shouldBlockKodikRequest("https://kodikplayer.com/assets/preroll/video.mp4"),
        )
    }
}
