package dev.aniliberty.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseRepositoryTest {
    @Test
    fun `discoveries prefer API recommendations and exclude featured releases`() {
        val feed = YaniFeedDto(
            recommends = listOf(anime(1), anime(2), anime(2), anime(3)),
            announcements = listOf(anime(4)),
            topCarousel = YaniCarouselDto(items = listOf(anime(5))),
        )

        assertEquals(
            listOf(2, 3),
            feed.discoverySources(featuredIds = setOf(1)).map(YaniAnimeDto::animeId),
        )
    }

    @Test
    fun `discoveries fall back to carousel and announcements`() {
        val feed = YaniFeedDto(
            announcements = listOf(anime(4)),
            topCarousel = YaniCarouselDto(items = listOf(anime(5), anime(5))),
        )

        assertEquals(
            listOf(5, 4),
            feed.discoverySources(featuredIds = emptySet()).map(YaniAnimeDto::animeId),
        )
    }

    private fun anime(id: Int): YaniAnimeDto = YaniAnimeDto(
        animeId = id,
        animeUrl = "anime-$id",
        title = "Anime $id",
    )
}
