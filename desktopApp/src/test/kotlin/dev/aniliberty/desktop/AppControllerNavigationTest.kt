package dev.aniliberty.desktop

import dev.aniliberty.desktop.data.AccountLibrary
import dev.aniliberty.desktop.data.AccountProfile
import dev.aniliberty.desktop.data.AccountRepository
import dev.aniliberty.desktop.data.AnimeListKind
import dev.aniliberty.desktop.data.AnimeMembership
import dev.aniliberty.desktop.data.CatalogFilters
import dev.aniliberty.desktop.data.ReleaseRepository
import dev.aniliberty.desktop.model.HomeFeed
import dev.aniliberty.desktop.model.ReleaseDto
import dev.aniliberty.desktop.model.ReleaseNameDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class AppControllerNavigationTest {
    private val release = ReleaseDto(
        id = 42,
        alias = "test-anime",
        name = ReleaseNameDto(main = "Тестовое аниме"),
    )

    @Test
    fun `details opened from catalog returns to preserved catalog`() = runBlocking {
        val controller = AppController(
            repository = FakeReleaseRepository(release),
            accountRepository = FakeAccountRepository(),
        )
        val filters = CatalogFilters(status = "ongoing")

        controller.showCatalog()
        controller.updateCatalogFilters(filters)
        controller.loadCatalog()
        controller.showDetails(release.id)

        assertIs<AppRoute.Details>(controller.route)
        controller.closeDetails()

        assertEquals(AppRoute.Catalog, controller.route)
        assertEquals(filters, controller.catalogFilters)
        assertEquals(
            listOf(release),
            assertIs<UiState.Ready<List<ReleaseDto>>>(controller.catalogState).value,
        )
    }

    @Test
    fun `details opened from search returns to search`() = runBlocking {
        val controller = AppController(
            repository = FakeReleaseRepository(release),
            accountRepository = FakeAccountRepository(),
        )

        controller.updateSearchQuery("тест")
        controller.search()
        controller.showDetails(release.id)
        controller.closeDetails()

        assertEquals(AppRoute.Search, controller.route)
        assertEquals("тест", controller.searchQuery)
    }
}

private class FakeReleaseRepository(
    private val release: ReleaseDto,
) : ReleaseRepository {
    override suspend fun home(): HomeFeed =
        HomeFeed(listOf(release), listOf(release), emptyList())

    override suspend fun search(query: String): List<ReleaseDto> = listOf(release)

    override suspend fun catalog(
        limit: Int,
        offset: Int,
        filters: CatalogFilters,
    ): List<ReleaseDto> = listOf(release)

    override suspend fun details(id: Int): ReleaseDto = release
}

private class FakeAccountRepository : AccountRepository {
    override suspend fun restoreSession(): AccountProfile? = null

    override suspend fun login(email: String, password: String): AccountProfile =
        error("Not used")

    override suspend fun logout() = Unit

    override suspend fun animeMembership(animeId: Int): AnimeMembership =
        AnimeMembership(null, false)

    override suspend fun setAnimeList(
        animeId: Int,
        list: AnimeListKind?,
    ): AnimeMembership = AnimeMembership(list, false)

    override suspend fun setAnimeFavorite(
        animeId: Int,
        favorite: Boolean,
    ): AnimeMembership = AnimeMembership(null, favorite)

    override suspend fun library(profileId: Long): AccountLibrary = AccountLibrary.Empty
}
