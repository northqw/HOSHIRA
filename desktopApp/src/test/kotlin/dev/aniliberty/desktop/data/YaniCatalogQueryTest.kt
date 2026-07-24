package dev.aniliberty.desktop.data

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class YaniCatalogQueryTest {
    @Test
    fun `catalog path preserves filters sorting and pagination`() {
        val path = catalogPath(
            limit = 30,
            offset = 60,
            filters = CatalogFilters(
                type = "tv",
                status = "ongoing",
                season = "spring",
                genre = "fentezi",
                fromYear = 2020,
                toYear = 2026,
                minRating = 8.0,
                sort = CatalogSort.Rating,
            ),
        )

        listOf(
            "limit=30",
            "offset=60",
            "sort=rating",
            "sort_forward=false",
            "types=tv",
            "status=ongoing",
            "season=spring",
            "genres=fentezi",
            "from_year=2020",
            "to_year=2026",
            "min_rating=8.0",
        ).forEach { parameter ->
            assertContains(path, parameter)
        }
    }

    @Test
    fun `catalog path clamps API pagination limits`() {
        val path = catalogPath(
            limit = 500,
            offset = 50_000,
            filters = CatalogFilters(sort = CatalogSort.Title),
        )

        assertContains(path, "limit=100")
        assertContains(path, "offset=20000")
        assertContains(path, "sort=title")
        assertEquals(true, path.contains("sort_forward=true"))
    }
}
