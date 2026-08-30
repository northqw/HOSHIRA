package app.hoshira.desktop.model

import app.hoshira.desktop.data.YaniAnimeDto
import app.hoshira.desktop.data.YaniResponse
import app.hoshira.desktop.data.YaniPosterDto
import app.hoshira.desktop.data.YaniScreenshotDto
import app.hoshira.desktop.data.YaniScreenshotSizesDto
import app.hoshira.desktop.data.YaniVideoDto
import app.hoshira.desktop.data.toRelease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ReleaseModelsTest {
    @Test
    fun `image delivery keeps requested quality before falling back one tier`() {
        assertEquals(
            listOf(
                "https://imgproxy.yani.tv/posters/medium/1.webp",
                "https://static.yani.tv/posters/medium/1.webp",
                "https://imgproxy.yani.tv/posters/small/1.webp",
                "https://static.yani.tv/posters/small/1.webp",
            ),
            imageDeliveryCandidates("https://imgproxy.yani.tv/posters/medium/1.webp"),
        )
        assertEquals(
            listOf(
                "https://imgproxy.yani.tv/posters/small/1.webp",
                "https://static.yani.tv/posters/small/1.webp",
            ),
            imageDeliveryCandidates("https://imgproxy.yani.tv/posters/small/1.webp"),
        )
        assertEquals(
            listOf("https://example.com/poster.webp"),
            imageDeliveryCandidates("https://example.com/poster.webp"),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `static Yani media URL uses direct image proxy`() {
        assertEquals(
            "https://imgproxy.yani.tv/posters/full/poster.jpg",
            "//static.yani.tv/posters/full/poster.jpg".asAbsoluteYaniUrl(),
        )
    }

    @Test
    fun `absolute static Yani media URL uses direct image proxy`() {
        assertEquals(
            "https://imgproxy.yani.tv/posters/full/poster.jpg?size=medium",
            "https://static.yani.tv/posters/full/poster.jpg?size=medium".asAbsoluteYaniUrl(),
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
            "https://imgproxy.yani.tv/screenshots/1/preview.webp",
            image.bestLandscapePath,
        )
    }

    @Test
    fun `backdrop falls back to high poster variant`() {
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
            "https://imgproxy.yani.tv/posters/mega/1.avif",
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
        assertEquals("https://imgproxy.yani.tv/posters/medium/1.webp", release.posterUrl)
        assertEquals("https://imgproxy.yani.tv/posters/full/1.jpg", release.posterFullUrl)
        assertEquals("https://i.kodikres.com/1.jpg", release.backdropUrl)
        assertEquals("https://kodikplayer.com/player/42", release.episodes.single().externalPlayerUrl)
        assertTrue(release.isOngoing)
    }

    @Test
    fun `poster mapping preserves explicit component size fallbacks`() {
        val release = YaniAnimeDto(
            animeId = 1,
            animeUrl = "test",
            title = "Тест",
            poster = YaniPosterDto(
                fullsize = "//static.yani.tv/posters/full/1.jpg",
                mega = "//static.yani.tv/posters/mega/1.jpg",
                huge = "//static.yani.tv/posters/huge/1.jpg",
                big = "//static.yani.tv/posters/big/1.jpg",
                medium = "//static.yani.tv/posters/medium/1.jpg",
                small = "//static.yani.tv/posters/small/1.jpg",
            ),
        ).toRelease()

        assertEquals("https://imgproxy.yani.tv/posters/medium/1.jpg", release.posterThumbnailUrl)
        assertEquals("https://imgproxy.yani.tv/posters/big/1.jpg", release.posterStandardUrl)
        assertEquals("https://imgproxy.yani.tv/posters/mega/1.jpg", release.posterHighUrl)
        assertEquals("https://imgproxy.yani.tv/posters/full/1.jpg", release.posterFullUrl)
    }

    @Test
    fun `every poster role remains non null when API only returns small`() {
        val release = YaniAnimeDto(
            animeId = 1,
            animeUrl = "test",
            title = "Тест",
            poster = YaniPosterDto(small = "/posters/small/1.jpg"),
        ).toRelease()

        val expected = "https://yummyani.me/posters/small/1.jpg"
        assertEquals(expected, release.posterThumbnailUrl)
        assertEquals(expected, release.posterStandardUrl)
        assertEquals(expected, release.posterHighUrl)
        assertEquals(expected, release.posterFullUrl)
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
