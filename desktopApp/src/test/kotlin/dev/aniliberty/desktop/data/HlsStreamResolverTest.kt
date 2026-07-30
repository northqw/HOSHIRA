package dev.aniliberty.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HlsStreamResolverTest {
    @Test
    fun `direct m3u8 URL passes through without a network request`() {
        val client = RecordingHlsClient(emptyMap())
        val resolver = HlsStreamResolver(client)

        val source = resolver.resolve("https://media.example/episode/master.m3u8")

        assertEquals("https://media.example/episode/master.m3u8", source.url)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `VideoHub iframe resolves the requested episode and voice`() {
        val client = RecordingHlsClient(
            mapOf(
                "/player/sv/playlist" to
                    """
                    {
                      "items": [
                        {"vkId":"wrong-episode","voiceStudio":"Moon","episode":2},
                        {"vkId":"other-voice","voiceStudio":"Sun","episode":3},
                        {"vkId":"wanted","voiceStudio":"Star Voice","episode":3}
                      ]
                    }
                    """.trimIndent(),
                "/player/sv/video/wanted" to
                    """{"sources":{"hlsUrl":"https://cdn.example/show/master.m3u8"}}""",
            ),
        )
        val resolver = HlsStreamResolver(client)

        val source = resolver.resolve(
            "https://ru.yummyani.me/iframeCVH.html" +
                "?anime_id=57658&episode=3&dubbing=%D0%9E%D0%B7%D0%B2%D1%83%D1%87%D0%BA%D0%B0%20Star%20Voice",
        )

        assertEquals("https://cdn.example/show/master.m3u8", source.url)
        assertTrue(client.requests.first().contains("pub=745"))
        assertTrue(client.requests.first().contains("id=57658"))
        assertTrue(client.requests.first().contains("aggr=mali"))
        assertTrue(client.requests.last().endsWith("/player/sv/video/wanted"))
    }

    @Test
    fun `resolver falls back to the first stream for the requested episode`() {
        val client = RecordingHlsClient(
            mapOf(
                "/player/sv/playlist" to
                    """
                    {
                      "items": [
                        {"vkId":42,"voiceStudio":"First Voice","episode":7},
                        {"vkId":"second","voiceStudio":"Second Voice","episode":7}
                      ]
                    }
                    """.trimIndent(),
                "/player/sv/video/42" to
                    """{"sources":{"hlsUrl":"https://cdn.example/fallback.m3u8"}}""",
            ),
        )

        val source = HlsStreamResolver(client).resolve(
            "https://ru.yummyani.me/iframeCVH.html?anime_id=12&episode=7",
        )

        assertEquals("https://cdn.example/fallback.m3u8", source.url)
    }

    @Test
    fun `unsupported iframe is rejected before a network request`() {
        val client = RecordingHlsClient(emptyMap())
        val resolver = HlsStreamResolver(client)

        assertFailsWith<HlsResolutionException> {
            resolver.resolve("https://kodik.example/player/123")
        }
        assertTrue(client.requests.isEmpty())
    }
}

private class RecordingHlsClient(
    private val responses: Map<String, String>,
) : HlsHttpClient {
    val requests = mutableListOf<String>()

    override fun get(url: String, headers: Map<String, String>): String {
        requests += url
        return responses.entries
            .firstOrNull { (suffix, _) -> url.contains(suffix) }
            ?.value
            ?: error("Unexpected request: $url")
    }
}
