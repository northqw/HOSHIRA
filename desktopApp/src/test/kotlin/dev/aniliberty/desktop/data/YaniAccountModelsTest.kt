package dev.aniliberty.desktop.data

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class YaniAccountModelsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `login request uses documented JSON fields`() {
        val payload = json.encodeToString(
            YaniLoginRequest(
                login = "user@example.com",
                password = "secret",
                needJson = true,
            ),
        )

        assertContains(payload, "\"login\":\"user@example.com\"")
        assertContains(payload, "\"need_json\":true")
        assertFalse(payload.contains("recaptcha_response"))
    }

    @Test
    fun `anime membership response supports list and favorite`() {
        val response = json.decodeFromString<YaniResponse<YaniAnimeListStateDto>>(
            """{"response":{"list":5,"is_favorite":true}}""",
        )

        assertEquals(5, response.response.list)
        assertEquals(true, response.response.isFavorite)
        assertEquals(AnimeListKind.Postponed, AnimeListKind.fromId(response.response.list))
    }

    @Test
    fun `user list accepts numeric rating returned by Yani`() {
        val response = json.decodeFromString<YaniResponse<List<YaniUserListAnimeDto>>>(
            """
            {
              "response": [{
                "anime_id": 42,
                "anime_url": "test-anime",
                "title": "Тестовое аниме",
                "rating": 8.75,
                "poster": {"medium": "//static.yani.tv/poster.webp"}
              }]
            }
            """.trimIndent(),
        )

        assertEquals(42, response.response.single().animeId)
        assertEquals(8.75, response.response.single().rating)
    }

    @Test
    fun `platform session store encrypts token at rest`() = runBlocking {
        val directory = Files.createTempDirectory("hoshira-account-test")
        val file = directory.resolve("session.bin")
        val store = AccountSessionStore(file)
        val session = StoredAccountSession(
            token = "sensitive-test-token",
            refreshedAt = 123456789L,
        )

        try {
            store.save(session)

            assertEquals(session, store.load())
            val raw = Files.readAllBytes(file).toString(StandardCharsets.UTF_8)
            assertFalse(raw.contains(session.token))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
