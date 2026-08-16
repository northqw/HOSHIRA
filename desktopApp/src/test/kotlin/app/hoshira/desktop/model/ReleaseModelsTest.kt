package app.hoshira.desktop.model

import app.hoshira.desktop.data.YaniAnimeDto
import app.hoshira.desktop.data.YaniResponse
import app.hoshira.desktop.data.YaniScreenshotDto
import app.hoshira.desktop.data.YaniScreenshotSizesDto
import app.hoshira.desktop.data.YaniVideoDto
import app.hoshira.desktop.data.toRelease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ReleaseModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `protocol relative Yani media URL becomes HTTPS`() {
        assertEquals(
            "https://static.yani.tv/posters/full/poster.jpg",
            "//static.yani.tv/posters/full/poster.jpg".asAbsoluteYaniUrl(),
        )
    }

    @Test
    fun `relative Yani media URL uses website origin`() {
        assertEquals(
            "https://yummyani.me/img/default-poster.jpg",
            "/img/default-poster.jpg".asAbsoluteYaniUrl(),
        )
    }

    @Test
    fun `absolute player URL preserves query and fragment`() {
        val url = "https://alloha.yani.tv/?token=test%2Ftoken&episode=1#player"

        assertEquals(url, url.asAbsoluteYaniUrl())
    }

    @Test
    fun `landscape image prefers desktop compatible preview over AVIF`() {
        val image = ImageDto(
            src = "https://static.yani.tv/screenshots/1/full.avif",
            preview = "https://static.yani.tv/screenshots/1/preview.webp",
        )

        assertEquals(
            "https://static.yani.tv/screenshots/1/preview.webp",
            image.bestLandscapePath,
        )
    }

    @Test
    fun `backdrop falls back to full JPEG poster`() {
        val release = ReleaseDto(
            id = 1,
            alias = "test",
            name = ReleaseNameDto(main = "Тест"),
            poster = ImageDto(
                src = "//static.yani.tv/posters/full/1.jpg",
                preview = "//static.yani.tv/posters/mega/1.avif",
            ),
        )

        assertEquals(
            "https://static.yani.tv/posters/full/1.jpg",
            release.backdropUrl,
        )
    }

    @Test
    fun `Yani anime response maps to UI release`() {
        val response = json.decodeFromString<YaniResponse<YaniAnimeDto>>(
            """
            {
              "response": {
                "anime_id": 13271,
                "anime_url": "mushoku-tensei-iii",
                "title": "Реинкарнация безработного 3",
                "description": "Описание",
                "year": 2026,
                "poster": {
                  "fullsize": "//static.yani.tv/posters/full/1.jpg",
                  "medium": "//static.yani.tv/posters/medium/1.webp"
                },
                "anime_status": {"title": "онгоинг", "alias": "ongoing"},
                "other_titles": ["Mushoku Tensei III"],
                "random_screenshots": [{
                  "episode": "1",
                  "sizes": {"full": "https://i.kodikres.com/1.jpg"}
                }],
                "videos": [{
                  "video_id": 42,
                  "number": "1",
                  "iframe_url": "//kodikplayer.com/player/42",
                  "duration": 1420,
                  "data": {"dubbing": "Дубляж"}
                }]
              }
            }
            """.trimIndent(),
        )

        val release = response.response.toRelease()

        assertEquals(13271, release.id)
        assertEquals("Mushoku Tensei III", release.name.english)
        assertEquals("https://static.yani.tv/posters/full/1.jpg", release.posterUrl)
        assertEquals("https://i.kodikres.com/1.jpg", release.backdropUrl)
        assertEquals("https://kodikplayer.com/player/42", release.episodes.single().externalPlayerUrl)
        assertTrue(release.isOngoing)
    }

    @Test
    fun `episode screenshots match normalized number without unrelated fallback`() {
        val release = YaniAnimeDto(
            animeId = 1,
            animeUrl = "test",
            title = "Тест",
            randomScreenshots = listOf(
                YaniScreenshotDto(
                    episode = "01.0",
                    sizes = YaniScreenshotSizesDto(full = "https://img.example/episode-1.jpg"),
                ),
            ),
            videos = listOf(
                YaniVideoDto(
                    videoId = 10,
                    number = "1",
                    iframeUrl = "https://player.example/1",
                ),
                YaniVideoDto(
                    videoId = 20,
                    number = "2",
                    iframeUrl = "https://player.example/2",
                ),
            ),
        ).toRelease()

        assertEquals(
            "https://img.example/episode-1.jpg",
            release.episodes.first { it.displayOrdinal == "1" }.previewUrl,
        )
        assertEquals(
            null,
            release.episodes.first { it.displayOrdinal == "2" }.previewUrl,
        )
    }
}
