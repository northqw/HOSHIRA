package app.hoshira.desktop

import app.hoshira.desktop.data.AccountLibrary
import app.hoshira.desktop.data.AccountProfile
import app.hoshira.desktop.data.AccountRepository
import app.hoshira.desktop.data.AnimeListKind
import app.hoshira.desktop.data.AnimeMembership
import app.hoshira.desktop.data.CatalogFilters
import app.hoshira.desktop.data.ReleaseRepository
import app.hoshira.desktop.model.HomeFeed
import app.hoshira.desktop.model.ReleaseDto
import app.hoshira.desktop.model.ReleaseNameDto
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

    @Test
    fun `closing search returns to the screen where it was opened`() {
        val controller = AppController(
            repository = FakeReleaseRepository(release),
            accountRepository = FakeAccountRepository(),
        )

        controller.updateSearchQuery("тест")
        assertEquals(AppRoute.Search, controller.route)

        controller.updateSearchQuery("")

        assertEquals(AppRoute.Home, controller.route)
        assertEquals("", controller.searchQuery)
    }

    @Test
    fun `background home refresh replaces data and keeps previous data on failure`() = runBlocking {
        val repository = FakeReleaseRepository(release)
        val controller = AppController(
            repository = repository,
            accountRepository = FakeAccountRepository(),
        )
        controller.loadHome()

        val refreshedRelease = release.copy(
            id = 43,
            name = ReleaseNameDto(main = "Обновлённый релиз"),
        )
        repository.homeRelease = refreshedRelease
        controller.refreshHome()

        assertEquals(
            refreshedRelease,
            assertIs<UiState.Ready<HomeFeed>>(controller.homeState).value.featured.first(),
        )

        repository.homeFailure = IllegalStateException("network unavailable")
        controller.refreshHome()

        assertEquals(
            refreshedRelease,
            assertIs<UiState.Ready<HomeFeed>>(controller.homeState).value.featured.first(),
        )
    }
}

private class FakeReleaseRepository(
    private val release: ReleaseDto,
) : ReleaseRepository {
    var homeRelease: ReleaseDto = release
    var homeFailure: Throwable? = null

    override suspend fun home(): HomeFeed =
        homeFailure?.let { throw it }
            ?: HomeFeed(listOf(homeRelease), listOf(homeRelease), emptyList())

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
