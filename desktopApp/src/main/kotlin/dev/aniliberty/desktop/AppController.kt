package dev.aniliberty.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.aniliberty.desktop.data.AccountLibrary
import dev.aniliberty.desktop.data.CatalogFilters
import dev.aniliberty.desktop.data.AccountProfile
import dev.aniliberty.desktop.data.AccountRepository
import dev.aniliberty.desktop.data.AnimeListKind
import dev.aniliberty.desktop.data.AnimeMembership
import dev.aniliberty.desktop.data.NetworkAccountRepository
import dev.aniliberty.desktop.data.ReleaseRepository
import dev.aniliberty.desktop.data.YaniApiException
import dev.aniliberty.desktop.model.EpisodeDto
import dev.aniliberty.desktop.model.HomeFeed
import dev.aniliberty.desktop.model.ReleaseDto

sealed interface AppRoute {
    data object Home : AppRoute
    data object Catalog : AppRoute
    data object Search : AppRoute
    data object Library : AppRoute
    data class Details(val releaseId: Int) : AppRoute
    data class Player(val releaseId: Int, val episodeId: String) : AppRoute
}

data class PlaybackSession(
    val release: ReleaseDto,
    val episode: EpisodeDto,
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Ready<T>(val value: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

sealed interface AccountState {
    data object Restoring : AccountState
    data object SignedOut : AccountState
    data object Authenticating : AccountState
    data class SignedIn(val profile: AccountProfile) : AccountState
}

class AppController(
    private val repository: ReleaseRepository,
    private val accountRepository: AccountRepository = NetworkAccountRepository(),
) {
    var route: AppRoute by mutableStateOf(AppRoute.Home)
        private set

    var homeState: UiState<HomeFeed> by mutableStateOf(UiState.Loading)
        private set

    var detailsState: UiState<ReleaseDto> by mutableStateOf(UiState.Loading)
        private set

    var searchState: UiState<List<ReleaseDto>> by mutableStateOf(UiState.Ready(emptyList()))
        private set

    var catalogState: UiState<List<ReleaseDto>> by mutableStateOf(UiState.Ready(emptyList()))
        private set

    var catalogLoadingMore: Boolean by mutableStateOf(false)
        private set

    var catalogCanLoadMore: Boolean by mutableStateOf(true)
        private set

    var catalogLoadMoreError: String? by mutableStateOf(null)
        private set

    var catalogFilters: CatalogFilters by mutableStateOf(CatalogFilters())
        private set

    var searchQuery: String by mutableStateOf("")
        private set

    var playbackSession: PlaybackSession? by mutableStateOf(null)
        private set

    var playerMessage: String? by mutableStateOf(null)
        private set

    var noticeTitle: String by mutableStateOf("Воспроизведение")
        private set

    var accountState: AccountState by mutableStateOf(AccountState.Restoring)
        private set

    var accountError: String? by mutableStateOf(null)
        private set

    var accountDialogVisible: Boolean by mutableStateOf(false)
        private set

    var animeMembershipState: UiState<AnimeMembership> by mutableStateOf(
        UiState.Ready(AnimeMembership(list = null, isFavorite = false)),
    )
        private set

    var accountActionInProgress: Boolean by mutableStateOf(false)
        private set

    var accountActionError: String? by mutableStateOf(null)
        private set

    var libraryState: UiState<AccountLibrary> by mutableStateOf(
        UiState.Ready(AccountLibrary.Empty),
    )
        private set

    var selectedLibraryKind: AnimeListKind by mutableStateOf(AnimeListKind.Watching)
        private set

    suspend fun loadHome(force: Boolean = false) {
        if (!force && homeState is UiState.Ready) return
        homeState = UiState.Loading
        homeState = runCatching { repository.home() }
            .fold(
                onSuccess = { UiState.Ready(it) },
                onFailure = { UiState.Error(it.userFacingMessage()) },
            )
    }

    suspend fun restoreAccount() {
        accountState = AccountState.Restoring
        accountError = null
        accountState = runCatching { accountRepository.restoreSession() }
            .fold(
                onSuccess = { profile ->
                    profile?.let { AccountState.SignedIn(it) } ?: AccountState.SignedOut
                },
                onFailure = {
                    AccountState.SignedOut
                },
            )
    }

    suspend fun login(
        email: String,
        password: String,
    ) {
        if (accountState == AccountState.Authenticating) return
        accountState = AccountState.Authenticating
        accountError = null
        runCatching { accountRepository.login(email, password) }
            .fold(
                onSuccess = { profile ->
                    accountState = AccountState.SignedIn(profile)
                    libraryState = UiState.Ready(AccountLibrary.Empty)
                    libraryLoaded = false
                    val releaseId = (route as? AppRoute.Details)?.releaseId
                    if (releaseId != null) {
                        loadAnimeMembership(releaseId)
                    }
                },
                onFailure = { error ->
                    accountState = AccountState.SignedOut
                    accountError = error.accountMessage()
                },
            )
    }

    suspend fun logout() {
        runCatching { accountRepository.logout() }
        accountState = AccountState.SignedOut
        accountError = null
        animeMembershipState = UiState.Ready(AnimeMembership(null, false))
        libraryState = UiState.Ready(AccountLibrary.Empty)
        libraryLoaded = false
        if (route == AppRoute.Library) route = AppRoute.Home
    }

    fun openAccountDialog() {
        accountDialogVisible = true
        accountError = null
    }

    fun closeAccountDialog() {
        if (accountState != AccountState.Authenticating) {
            accountDialogVisible = false
            accountError = null
        }
    }

    fun showHome() {
        route = AppRoute.Home
        searchQuery = ""
        playbackSession = null
    }

    fun showCatalog() {
        route = AppRoute.Catalog
        searchQuery = ""
        playbackSession = null
    }

    suspend fun showLibrary(force: Boolean = false) {
        val profile = (accountState as? AccountState.SignedIn)?.profile
        if (profile == null) {
            openAccountDialog()
            return
        }
        route = AppRoute.Library
        searchQuery = ""
        playbackSession = null
        loadLibrary(profile, force)
    }

    fun selectLibraryKind(kind: AnimeListKind) {
        selectedLibraryKind = kind
    }

    fun updateSearchQuery(value: String) {
        searchQuery = value
        route = if (value.isBlank()) AppRoute.Catalog else AppRoute.Search
    }

    fun updateCatalogFilters(value: CatalogFilters) {
        if (catalogFilters == value) return
        catalogFilters = value
        catalogState = UiState.Ready(emptyList())
        catalogOffset = 0
        catalogCanLoadMore = true
        catalogLoadMoreError = null
    }

    suspend fun search() {
        val requestedQuery = searchQuery
        if (requestedQuery.isBlank()) return
        searchState = UiState.Loading
        val result = runCatching { repository.search(requestedQuery) }
        if (requestedQuery != searchQuery) return
        searchState = result.fold(
            onSuccess = { UiState.Ready(it) },
            onFailure = { UiState.Error(it.userFacingMessage()) },
        )
    }

    suspend fun loadCatalog(force: Boolean = false) {
        if (!force && (catalogState as? UiState.Ready)?.value?.isNotEmpty() == true) return

        val requestedFilters = catalogFilters
        catalogState = UiState.Loading
        catalogOffset = 0
        catalogCanLoadMore = true
        catalogLoadMoreError = null
        val result = runCatching {
            repository.catalog(
                limit = CATALOG_PAGE_SIZE,
                offset = 0,
                filters = requestedFilters,
            )
        }
        if (requestedFilters != catalogFilters) return
        catalogState = result.fold(
            onSuccess = { page ->
                catalogOffset = page.size
                catalogCanLoadMore = page.size == CATALOG_PAGE_SIZE
                UiState.Ready(page.distinctBy(ReleaseDto::id))
            },
            onFailure = { UiState.Error(it.userFacingMessage()) },
        )
    }

    suspend fun loadMoreCatalog() {
        val current = (catalogState as? UiState.Ready)?.value ?: return
        if (catalogLoadingMore || !catalogCanLoadMore) return

        catalogLoadingMore = true
        catalogLoadMoreError = null
        val requestedFilters = catalogFilters
        runCatching {
            repository.catalog(
                limit = CATALOG_PAGE_SIZE,
                offset = catalogOffset,
                filters = requestedFilters,
            )
        }.fold(
            onSuccess = { page ->
                if (requestedFilters != catalogFilters) return@fold
                catalogOffset += page.size
                catalogCanLoadMore = page.size == CATALOG_PAGE_SIZE
                catalogState = UiState.Ready(
                    (current + page).distinctBy(ReleaseDto::id),
                )
            },
            onFailure = { error ->
                if (requestedFilters == catalogFilters) {
                    catalogLoadMoreError = error.userFacingMessage()
                }
            },
        )
        catalogLoadingMore = false
    }

    suspend fun showDetails(releaseId: Int) {
        when (route) {
            AppRoute.Home,
            AppRoute.Catalog,
            AppRoute.Search,
            AppRoute.Library,
            -> detailsReturnRoute = route

            else -> Unit
        }
        route = AppRoute.Details(releaseId)
        detailsState = UiState.Loading
        detailsState = runCatching { repository.details(releaseId) }
            .fold(
                onSuccess = { UiState.Ready(it) },
                onFailure = { UiState.Error(it.userFacingMessage()) },
            )
        if (detailsState is UiState.Ready && accountState is AccountState.SignedIn) {
            loadAnimeMembership(releaseId)
        } else {
            animeMembershipState = UiState.Ready(AnimeMembership(null, false))
        }
    }

    fun closeDetails() {
        route = detailsReturnRoute
        playbackSession = null
    }

    suspend fun setAnimeList(list: AnimeListKind?) {
        val releaseId = (route as? AppRoute.Details)?.releaseId ?: return
        if (accountState !is AccountState.SignedIn || accountActionInProgress) {
            if (accountState !is AccountState.SignedIn) openAccountDialog()
            return
        }
        accountActionInProgress = true
        accountActionError = null
        runCatching { accountRepository.setAnimeList(releaseId, list) }
            .fold(
                onSuccess = { membership ->
                    animeMembershipState = UiState.Ready(membership)
                    libraryLoaded = false
                },
                onFailure = { error ->
                    accountActionError = error.accountOperationMessage()
                },
            )
        accountActionInProgress = false
    }

    suspend fun toggleAnimeFavorite() {
        val releaseId = (route as? AppRoute.Details)?.releaseId ?: return
        val membership = (animeMembershipState as? UiState.Ready)?.value
            ?: AnimeMembership(null, false)
        if (accountState !is AccountState.SignedIn || accountActionInProgress) {
            if (accountState !is AccountState.SignedIn) openAccountDialog()
            return
        }
        accountActionInProgress = true
        accountActionError = null
        runCatching {
            accountRepository.setAnimeFavorite(releaseId, !membership.isFavorite)
        }.fold(
            onSuccess = { updated ->
                animeMembershipState = UiState.Ready(updated)
                libraryLoaded = false
            },
            onFailure = { error ->
                accountActionError = error.accountOperationMessage()
            },
        )
        accountActionInProgress = false
    }

    suspend fun refreshLibrary() {
        val profile = (accountState as? AccountState.SignedIn)?.profile ?: return
        loadLibrary(profile, force = true)
    }

    fun playEpisode(episode: EpisodeDto) {
        val release = (detailsState as? UiState.Ready)?.value
        if (release == null || episode.externalPlayerUrl == null) {
            noticeTitle = "Воспроизведение"
            playerMessage = "Для этой серии Yani пока не вернул доступную ссылку на плеер."
            return
        }

        playbackSession = PlaybackSession(release, episode)
        route = AppRoute.Player(release.id, episode.id)
    }

    fun closePlayer() {
        val releaseId = playbackSession?.release?.id ?: return showHome()
        route = AppRoute.Details(releaseId)
    }

    fun showAccountUnavailable() {
        noticeTitle = "Профиль"
        playerMessage = "Авторизацию и синхронизацию избранного добавим на следующем этапе."
    }

    fun dismissPlayerMessage() {
        playerMessage = null
    }

    private fun Throwable.userFacingMessage(): String =
        message?.takeIf { it.isNotBlank() }
            ?: "Не удалось связаться с Yani. Проверьте подключение и повторите попытку."

    private fun Throwable.accountMessage(): String = when {
        this is IllegalArgumentException -> message.orEmpty()
        this is YaniApiException && statusCode == 401 ->
            "Неверный email или пароль."
        this is YaniApiException && statusCode == 420 ->
            "Yani запросил проверку hCaptcha. Повторите вход немного позже."
        this is YaniApiException && statusCode == 429 ->
            "Слишком много попыток входа. Подождите несколько минут."
        else ->
            "Не удалось войти. Проверьте подключение и повторите попытку."
    }

    private suspend fun loadAnimeMembership(releaseId: Int) {
        animeMembershipState = UiState.Loading
        accountActionError = null
        animeMembershipState = runCatching {
            accountRepository.animeMembership(releaseId)
        }.fold(
            onSuccess = { UiState.Ready(it) },
            onFailure = { error ->
                UiState.Error(error.accountOperationMessage())
            },
        )
    }

    private suspend fun loadLibrary(
        profile: AccountProfile,
        force: Boolean,
    ) {
        if (!force && libraryLoaded) return
        libraryState = UiState.Loading
        libraryState = runCatching { accountRepository.library(profile.id) }
            .fold(
                onSuccess = {
                    libraryLoaded = true
                    UiState.Ready(it)
                },
                onFailure = { error ->
                    UiState.Error(error.accountOperationMessage())
                },
            )
    }

    private fun Throwable.accountOperationMessage(): String = when {
        this is YaniApiException && statusCode == 401 ->
            "Сессия истекла. Выйдите из аккаунта и войдите снова."
        this is YaniApiException && statusCode == 404 ->
            "Yani не нашёл это аниме или список."
        else ->
            "Не удалось синхронизировать данные аккаунта. Проверьте подключение и повторите попытку."
    }

    private var catalogOffset: Int = 0
    private var detailsReturnRoute: AppRoute = AppRoute.Home
    private var libraryLoaded: Boolean = false
}

private const val CATALOG_PAGE_SIZE = 30
