package dev.aniliberty.desktop.data

import java.nio.charset.StandardCharsets
import java.util.Base64
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

    @Test
    fun `Kodik iframe resolves through its player endpoint`() {
        val expectedUrl = "https://cdn.example/episode/720.mp4:hls:manifest.m3u8"
        val encodedUrl = Base64.getEncoder().encodeToString(
            expectedUrl.toByteArray(StandardCharsets.UTF_8),
        ).map { character ->
            when (character) {
                in 'a'..'z' -> 'a' + ((character - 'a' + 8) % 26)
                in 'A'..'Z' -> 'A' + ((character - 'A' + 8) % 26)
                else -> character
            }
        }.joinToString("")
        val client = RecordingHlsClient(
            responses = mapOf(
                "kodikplayer.com/season/" to
                    """
                    <script>
                      const serialId = Number(999999);
                      const secure = '{"d":"kodikplayer.com","d_sign":"d-sign","pd":"kodikplayer.com","pd_sign":"pd-sign","ref":"%2Fwatch%3Fepisode%3D1","ref_sign":"ref-sign"}';
                    </script>
                    <script src="/assets/js/app.player_single.test.js"></script>
                    """.trimIndent(),
                "/assets/js/app.player_single.test.js" to
                    """$.ajax({type:"POST",url:atob("L2Z0b3I=")})""",
            ),
            postResponse =
                """{"links":{"720":[{"src":"$encodedUrl","type":"application/x-mpegURL"}]}}""",
        )
        val resolver = HlsStreamResolver(client)

        val source = resolver.resolve(
            "https://kodikplayer.com/season/116621/" +
                "abcdef0123456789abcdef0123456789/720p?episode=1",
        )

        assertEquals(expectedUrl, source.url)
        assertEquals(1, client.postRequests.size)
        assertEquals("season", client.postRequests.single().body["type"])
        assertEquals("116621", client.postRequests.single().body["id"])
        assertEquals(
            "abcdef0123456789abcdef0123456789",
            client.postRequests.single().body["hash"],
        )
        assertEquals("d-sign", client.postRequests.single().body["d_sign"])
        assertEquals("pd-sign", client.postRequests.single().body["pd_sign"])
        assertEquals("ref-sign", client.postRequests.single().body["ref_sign"])
        assertEquals("/watch?episode=1", client.postRequests.single().body["ref"])
        assertEquals("false", client.postRequests.single().body["bad_user"])
        assertEquals("true", client.postRequests.single().body["cdn_is_working"])
        assertEquals(
            "https://kodikplayer.com/season/116621/" +
                "abcdef0123456789abcdef0123456789/360p",
            client.postRequests.single().headers["Referer"],
        )
    }

    @Test
    fun `Alloha is identified separately from an unknown provider`() {
        val diagnostic = HlsStreamResolver().inspect(
            "https://alloha.yani.tv/?token_movie=secret&token=secret",
        )

        assertEquals(HlsProvider.ALLOHA, diagnostic.provider)
        assertTrue(!diagnostic.supported)
        assertEquals(
            "requires-js-websocket-and-request-headers",
            diagnostic.detail,
        )
    }

    @Test
    fun `preferred voice overrides the voice from a fallback iframe`() {
        val client = RecordingHlsClient(
            mapOf(
                "/player/sv/playlist" to
                    """
                    {
                      "items": [
                        {"vkId":"iframe-voice","voiceStudio":"Fallback Voice","episode":4},
                        {"vkId":"preferred","voiceStudio":"Requested Voice","episode":4}
                      ]
                    }
                    """.trimIndent(),
                "/player/sv/video/preferred" to
                    """{"sources":{"hlsUrl":"https://cdn.example/preferred.m3u8"}}""",
            ),
        )

        val source = HlsStreamResolver(client).resolve(
            "https://ru.yummyani.me/iframeCVH.html" +
                "?anime_id=12&episode=4&dubbing=Fallback+Voice",
            preferredVoice = "Озвучка Requested Voice",
        )

        assertEquals("https://cdn.example/preferred.m3u8", source.url)
    }

    @Test
    fun `debug URL exposes query keys but not values`() {
        val debugUrl = "https://media.example/master.m3u8?token=secret-value&expires=123"
            .hlsDebugUrl()

        assertEquals(
            "https://media.example/master.m3u8?<token,expires>",
            debugUrl,
        )
        assertTrue("secret-value" !in debugUrl)
    }
}

private class RecordingHlsClient(
    private val responses: Map<String, String>,
    private val postResponse: String? = null,
) : HlsHttpClient {
    val requests = mutableListOf<String>()
    val postRequests = mutableListOf<PostRequest>()

    override fun get(url: String, headers: Map<String, String>): String {
        requests += url
        return responses.entries
            .firstOrNull { (suffix, _) -> url.contains(suffix) }
            ?.value
            ?: error("Unexpected request: $url")
    }

    override fun postForm(
        url: String,
        body: Map<String, String>,
        headers: Map<String, String>,
    ): String {
        postRequests += PostRequest(url, body, headers)
        return postResponse ?: error("Unexpected POST request: $url")
    }
}

private data class PostRequest(
    val url: String,
    val body: Map<String, String>,
    val headers: Map<String, String>,
)
