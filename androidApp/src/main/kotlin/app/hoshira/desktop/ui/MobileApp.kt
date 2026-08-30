package app.hoshira.desktop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.hoshira.desktop.AccountState
import app.hoshira.desktop.AppController
import app.hoshira.desktop.AppRoute
import app.hoshira.desktop.UiState
import app.hoshira.desktop.data.AccountLibrary
import app.hoshira.desktop.data.AnimeMembership
import app.hoshira.desktop.data.AnimeListKind
import app.hoshira.desktop.data.CatalogFilters
import app.hoshira.desktop.data.CatalogSort
import app.hoshira.desktop.data.ReleaseRepository
import app.hoshira.desktop.data.WatchProgress
import app.hoshira.desktop.model.EpisodeDto
import app.hoshira.desktop.model.HomeFeed
import app.hoshira.desktop.model.ReleaseDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private enum class MobileTab(val label: String, val glyph: String? = null) {
    Home("Главная", "⌂"),
    Catalog("Каталог"),
    Profile("Профиль"),
    Search("Поиск", "⌕"),
}

@Composable
fun HoshiraMobileApp(
    repository: ReleaseRepository,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    HoshiraTheme {
        val controller = remember(repository) { AppController(repository) }
        val scope = rememberCoroutineScope()
        var selectedTab by remember { mutableStateOf(MobileTab.Home) }
        val homeListState = rememberLazyListState()
        val catalogGridState = rememberLazyGridState()
        val searchResultsGridState = rememberLazyGridState()
        val searchHistoryGridState = rememberLazyGridState()
        val profileGridState = rememberLazyGridState()
        var detailsScrollGeneration by remember { mutableIntStateOf(0) }
        val detailsListState = key(detailsScrollGeneration) { rememberLazyListState() }
        val openRelease: (Int) -> Unit = { releaseId ->
            detailsScrollGeneration++
            scope.launch {
                controller.showDetails(releaseId)
            }
        }
        val openSearchRelease: (Int) -> Unit = { releaseId ->
            detailsScrollGeneration++
            scope.launch {
                controller.showSearchResultDetails(releaseId)
            }
        }

        LaunchedEffect(Unit) {
            controller.loadHome()
        }
        LaunchedEffect(Unit) {
            controller.restoreAccount()
        }
        LaunchedEffect(selectedTab) {
            when (selectedTab) {
                MobileTab.Catalog -> controller.loadCatalog()
                MobileTab.Profile -> if (controller.accountState is AccountState.SignedIn) {
                    controller.showLibrary()
                }
                else -> Unit
            }
        }
        LaunchedEffect(controller.searchQuery, selectedTab) {
            if (selectedTab == MobileTab.Search && controller.searchQuery.isNotBlank()) {
                delay(MOBILE_SEARCH_DEBOUNCE_MS)
                controller.search()
            }
        }

        BackHandler(enabled = controller.route != AppRoute.Home || selectedTab != MobileTab.Home) {
            when (controller.route) {
                is AppRoute.Player -> {
                    onFullscreenChange(false)
                    controller.closePlayer()
                }
                is AppRoute.Details -> controller.closeDetails()
                else -> {
                    selectedTab = MobileTab.Home
                    controller.showHome()
                }
            }
        }

        Surface(
            modifier = modifier.fillMaxSize(),
            color = AniColors.Background,
            contentColor = AniColors.Text,
        ) {
            Box(Modifier.fillMaxSize().background(AniColors.Background)) {
                when (val route = controller.route) {
                    is AppRoute.Player -> PlayerScreen(
                        session = controller.playbackSession,
                        onBack = controller::closePlayer,
                        onPlayEpisode = controller::playEpisode,
                        preferences = controller.preferences,
                        preferredQuality = controller.lastQuality,
                        onPlayback = controller::recordPlayback,
                        onPlaybackFlush = controller::flushPlayback,
                        isFullscreen = isFullscreen,
                        onFullscreenChange = onFullscreenChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppRoute.Details -> MobileDetailsScreen(
                        state = controller.detailsState,
                        listState = detailsListState,
                        membershipState = controller.animeMembershipState,
                        accountSignedIn = controller.accountState is AccountState.SignedIn,
                        accountActionInProgress = controller.accountActionInProgress,
                        accountActionError = controller.accountActionError,
                        onBack = controller::closeDetails,
                        onRetry = {
                            scope.launch { controller.showDetails(route.releaseId) }
                        },
                        onPlayEpisode = controller::playEpisode,
                        onSetAnimeList = { list ->
                            scope.launch { controller.setAnimeList(list) }
                        },
                        onToggleFavorite = {
                            scope.launch { controller.toggleAnimeFavorite() }
                        },
                        onOpenAccount = {
                            controller.closeDetails()
                            selectedTab = MobileTab.Profile
                        },
                    )
                    else -> {
                        when (selectedTab) {
                            MobileTab.Home -> MobileHomeScreen(
                                state = controller.homeState,
                                listState = homeListState,
                                continueWatching = controller.continueWatching,
                                onRetry = {
                                    scope.launch { controller.loadHome(force = true) }
                                },
                                onOpenRelease = openRelease,
                                onContinue = { progress ->
                                    scope.launch { controller.continueWatching(progress) }
                                },
                            )
                            MobileTab.Catalog -> MobileCatalogScreen(
                                state = controller.catalogState,
                                gridState = catalogGridState,
                                filters = controller.catalogFilters,
                                onFiltersChange = { filters ->
                                    scope.launch {
                                        catalogGridState.scrollToItem(0)
                                        controller.updateCatalogFilters(filters)
                                        controller.loadCatalog(force = true)
                                    }
                                },
                                onRetry = {
                                    scope.launch { controller.loadCatalog(force = true) }
                                },
                                onLoadMore = { scope.launch { controller.loadMoreCatalog() } },
                                isLoadingMore = controller.catalogLoadingMore,
                                canLoadMore = controller.catalogCanLoadMore,
                                loadMoreError = controller.catalogLoadMoreError,
                                onOpenRelease = openRelease,
                            )
                            MobileTab.Profile -> MobileProfileScreen(
                                controller = controller,
                                gridState = profileGridState,
                                onOpenRelease = openRelease,
                            )
                            MobileTab.Search -> MobileSearchScreen(
                                query = controller.searchQuery,
                                onQueryChange = controller::updateSearchQuery,
                                state = controller.searchState,
                                history = controller.searchHistory,
                                resultsGridState = searchResultsGridState,
                                historyGridState = searchHistoryGridState,
                                onOpenRelease = openSearchRelease,
                            )
                        }
                        MobileBottomBar(
                            selected = selectedTab,
                            onSelect = { tab ->
                                selectedTab = tab
                                when (tab) {
                                    MobileTab.Home -> controller.showHome()
                                    MobileTab.Catalog -> controller.showCatalog()
                                    MobileTab.Search -> Unit
                                    MobileTab.Profile -> Unit
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }

                controller.pendingResume?.let { pending ->
                    AlertDialog(
                        onDismissRequest = { controller.resolveResume(false) },
                        containerColor = AniColors.Surface,
                        title = { Text("Продолжить просмотр?") },
                        text = { Text("${pending.episode.shortTitle} · продолжить с сохранённого места?") },
                        confirmButton = {
                            TextButton(onClick = { controller.resolveResume(true) }) {
                                Text("Продолжить", color = AniColors.OrangeBright)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { controller.resolveResume(false) }) {
                                Text("С начала", color = AniColors.TextMuted)
                            }
                        },
                    )
                }
                controller.playerMessage?.let { message ->
                    AlertDialog(
                        onDismissRequest = controller::dismissPlayerMessage,
                        containerColor = AniColors.Surface,
                        title = { Text(controller.noticeTitle) },
                        text = { Text(message) },
                        confirmButton = {
                            TextButton(onClick = controller::dismissPlayerMessage) {
                                Text("Понятно", color = AniColors.OrangeBright)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileHomeScreen(
    state: UiState<HomeFeed>,
    listState: LazyListState,
    continueWatching: List<WatchProgress>,
    onRetry: () -> Unit,
    onOpenRelease: (Int) -> Unit,
    onContinue: (WatchProgress) -> Unit,
) {
    when (state) {
        UiState.Loading -> MobileLoading()
        is UiState.Error -> ErrorState(state.message, onRetry, Modifier.fillMaxSize())
        is UiState.Ready -> {
            val feed = state.value
            val hero = feed.featured.firstOrNull()
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentPadding = PaddingValues(bottom = MOBILE_BOTTOM_BAR_HEIGHT + 22.dp),
                ) {
                    if (hero != null) {
                        item {
                            MobileHero(hero, onOpenRelease)
                        }
                    } else {
                        item { Spacer(Modifier.height(MOBILE_HEADER_HEIGHT)) }
                    }
                    if (continueWatching.isNotEmpty()) {
                        item {
                            MobileSectionTitle("Продолжить просмотр")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 18.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(continueWatching, key = { "${it.releaseId}-${it.episodeId}" }) { progress ->
                                    MobileProgressCard(progress, onContinue)
                                }
                            }
                            Spacer(Modifier.height(28.dp))
                        }
                    }
                    item {
                        MobileSectionTitle("Последние обновления")
                        MobilePosterRail(feed.latest, onOpenRelease)
                        Spacer(Modifier.height(28.dp))
                    }
                    if (feed.discoveries.isNotEmpty()) {
                        item {
                            MobileSectionTitle("Для вас")
                            MobilePosterRail(feed.discoveries, onOpenRelease)
                        }
                    }
                }
                MobileHeader(Modifier.align(Alignment.TopCenter).zIndex(4f))
            }
        }
    }
}

@Composable
private fun MobileHeader(modifier: Modifier = Modifier) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val shadowAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 650),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MOBILE_HEADER_HEIGHT)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = shadowAlpha),
                        0.70f to Color.Black.copy(alpha = shadowAlpha),
                        0.84f to Color.Black.copy(alpha = shadowAlpha * 0.82f),
                        0.94f to Color.Black.copy(alpha = shadowAlpha * 0.36f),
                        1f to Color.Transparent,
                    ),
                ),
            ),
    ) {
        BrandMark(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 15.dp),
        )
    }
}

@Composable
private fun MobileHero(release: ReleaseDto, onOpenRelease: (Int) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(510.dp)
            .clickable { onOpenRelease(release.id) },
    ) {
        RemoteImage(
            url = release.backdropHighUrl,
            placeholderUrl = release.backdropStandardUrl,
            contentDescription = release.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.08f),
                    0.52f to Color.Black.copy(alpha = 0.22f),
                    1f to Color.Black,
                ),
            ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                release.displayName,
                style = MaterialTheme.typography.headlineLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                release.metadata,
                color = AniColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { onOpenRelease(release.id) },
                colors = ButtonDefaults.buttonColors(containerColor = AniColors.Orange),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 13.dp),
            ) {
                Text("▶  Смотреть", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MobilePosterRail(releases: List<ReleaseDto>, onOpenRelease: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(releases, key = ReleaseDto::id) { release ->
            MobilePosterCard(
                release = release,
                onClick = { onOpenRelease(release.id) },
                modifier = Modifier.width(144.dp),
            )
        }
    }
}

@Composable
private fun MobileProgressCard(progress: WatchProgress, onContinue: (WatchProgress) -> Unit) {
    Column(
        modifier = Modifier
            .width(250.dp)
            .clickable { onContinue(progress) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            RemoteImage(
                url = progress.imageUrl,
                contentDescription = progress.releaseTitle,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.fraction.coerceAtLeast(0.02f))
                    .height(4.dp)
                    .background(AniColors.Orange),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(progress.releaseTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(progress.episodeTitle, color = AniColors.TextMuted, maxLines = 1)
    }
}

@Composable
private fun MobileCatalogScreen(
    state: UiState<List<ReleaseDto>>,
    gridState: LazyGridState,
    filters: CatalogFilters,
    onFiltersChange: (CatalogFilters) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    isLoadingMore: Boolean,
    canLoadMore: Boolean,
    loadMoreError: String?,
    onOpenRelease: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding(),
    ) {
        Text(
            "Каталог",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(start = 18.dp, top = 22.dp, bottom = 13.dp),
        )
        MobileCatalogFilters(
            filters = filters,
            onChange = onFiltersChange,
        )
        Spacer(Modifier.height(15.dp))
        when (state) {
            UiState.Loading -> MobileLoading(modifier = Modifier.weight(1f))
            is UiState.Error -> ErrorState(state.message, onRetry, Modifier.weight(1f))
            is UiState.Ready -> if (state.value.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("По этим фильтрам ничего не найдено", color = AniColors.TextMuted)
                }
            } else {
                val releases = state.value
                LaunchedEffect(
                    gridState,
                    releases.size,
                    canLoadMore,
                    isLoadingMore,
                    loadMoreError,
                ) {
                    if (!canLoadMore || isLoadingMore || loadMoreError != null) {
                        return@LaunchedEffect
                    }
                    snapshotFlow {
                        gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    }
                        .distinctUntilChanged()
                        .collect { lastVisibleIndex ->
                            if (lastVisibleIndex >= releases.lastIndex - 4 && lastVisibleIndex >= 0) {
                                onLoadMore()
                            }
                        }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(
                        start = 18.dp,
                        end = 18.dp,
                        bottom = MOBILE_BOTTOM_BAR_HEIGHT + 20.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(releases, key = ReleaseDto::id) { release ->
                        MobilePosterCard(
                            release = release,
                            onClick = { onOpenRelease(release.id) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (isLoadingMore) {
                        item(
                            key = "mobile-catalog-loading-more",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(88.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = AniColors.OrangeBright,
                                    strokeWidth = 3.dp,
                                )
                            }
                        }
                    } else if (loadMoreError != null) {
                        item(
                            key = "mobile-catalog-load-more-error",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(loadMoreError, color = AniColors.TextMuted)
                                TextButton(onClick = onLoadMore) {
                                    Text("Повторить", color = AniColors.OrangeBright)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileCatalogFilters(
    filters: CatalogFilters,
    onChange: (CatalogFilters) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            MobileFilterDropdown(
                caption = "Тип",
                selected = filters.type,
                options = mobileCatalogTypeOptions,
                onSelected = { onChange(filters.copy(type = it)) },
            )
        }
        item {
            MobileFilterDropdown(
                caption = "Статус",
                selected = filters.status,
                options = mobileCatalogStatusOptions,
                onSelected = { onChange(filters.copy(status = it)) },
            )
        }
        item {
            MobileFilterDropdown(
                caption = "Сезон",
                selected = filters.season,
                options = mobileCatalogSeasonOptions,
                onSelected = { onChange(filters.copy(season = it)) },
            )
        }
        item {
            MobileFilterDropdown(
                caption = "Жанр",
                selected = filters.genre,
                options = mobileCatalogGenreOptions,
                onSelected = { onChange(filters.copy(genre = it)) },
            )
        }
        item {
            val yearValue = filters.fromYear?.let { from -> "$from:${filters.toYear ?: from}" }
            MobileFilterDropdown(
                caption = "Год",
                selected = yearValue,
                options = mobileCatalogYearOptions,
                onSelected = { value ->
                    val bounds = value?.split(':')?.mapNotNull(String::toIntOrNull).orEmpty()
                    onChange(
                        filters.copy(
                            fromYear = bounds.getOrNull(0),
                            toYear = bounds.getOrNull(1),
                        ),
                    )
                },
            )
        }
        item {
            MobileFilterDropdown(
                caption = "Рейтинг",
                selected = filters.minRating?.toString(),
                options = mobileCatalogRatingOptions,
                onSelected = { onChange(filters.copy(minRating = it?.toDoubleOrNull())) },
            )
        }
        item {
            MobileFilterDropdown(
                caption = "Сортировка",
                selected = filters.sort.name,
                options = mobileCatalogSortOptions,
                onSelected = { value ->
                    onChange(
                        filters.copy(
                            sort = CatalogSort.entries.firstOrNull { it.name == value }
                                ?: CatalogSort.Newest,
                        ),
                    )
                },
            )
        }
        if (filters != CatalogFilters()) {
            item {
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(CircleShape)
                        .border(1.dp, AniColors.Border, CircleShape)
                        .clickable { onChange(CatalogFilters()) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Сбросить", color = AniColors.OrangeBright, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MobileFilterDropdown(
    caption: String,
    selected: String?,
    options: List<MobileFilterChoice>,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selected }?.label ?: "Все"
    Box {
        Row(
            modifier = Modifier
                .height(44.dp)
                .clip(CircleShape)
                .background(if (selected != null) AniColors.SurfaceHigh else AniColors.Surface)
                .border(
                    1.dp,
                    if (selected != null) AniColors.Orange.copy(alpha = 0.72f) else AniColors.Border,
                    CircleShape,
                )
                .clickable { expanded = true }
                .padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$caption: $selectedLabel",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            DropdownChevron(
                expanded = expanded,
                color = if (selected != null) AniColors.OrangeBright else AniColors.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = AniColors.SurfaceHigh,
            shape = RoundedCornerShape(18.dp),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            fontWeight = if (option.value == selected) {
                                FontWeight.ExtraBold
                            } else {
                                FontWeight.Medium
                            },
                            color = if (option.value == selected) {
                                AniColors.OrangeBright
                            } else {
                                AniColors.Text
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(option.value)
                    },
                )
            }
        }
    }
}

@Composable
private fun MobileSearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    state: UiState<List<ReleaseDto>>,
    history: List<ReleaseDto>,
    resultsGridState: LazyGridState,
    historyGridState: LazyGridState,
    onOpenRelease: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "Поиск",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(start = 18.dp, top = 22.dp, bottom = 12.dp),
        )
        HoshiraTextField(
            value = query,
            onValueChange = onQueryChange,
            label = "Название аниме",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        )
        Spacer(Modifier.height(16.dp))
        when {
            query.isBlank() && history.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Введите название релиза", color = AniColors.TextMuted)
            }
            query.isBlank() -> MobileSearchGrid(
                releases = history,
                gridState = historyGridState,
                onOpenRelease = onOpenRelease,
            )
            state == UiState.Loading -> MobileLoading()
            state is UiState.Error -> ErrorState(
                state.message,
                onRetry = {},
                modifier = Modifier.fillMaxSize(),
            )
            state is UiState.Ready -> MobileSearchGrid(
                releases = state.value,
                gridState = resultsGridState,
                onOpenRelease = onOpenRelease,
            )
        }
    }
}

@Composable
private fun MobileSearchGrid(
    releases: List<ReleaseDto>,
    gridState: LazyGridState,
    onOpenRelease: (Int) -> Unit,
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            bottom = MOBILE_BOTTOM_BAR_HEIGHT + 20.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(releases, key = ReleaseDto::id) { release ->
            MobilePosterCard(
                release = release,
                onClick = { onOpenRelease(release.id) },
            )
        }
    }
}

@Composable
private fun MobileProfileScreen(
    controller: AppController,
    gridState: LazyGridState,
    onOpenRelease: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    when (val account = controller.accountState) {
        AccountState.Restoring,
        AccountState.Authenticating,
        -> MobileLoading("Проверяем аккаунт…")
        AccountState.SignedOut -> MobileLogin(
            error = controller.accountError,
            onLogin = { email, password ->
                scope.launch {
                    controller.login(email, password)
                    if (controller.accountState is AccountState.SignedIn) {
                        controller.showLibrary()
                    }
                }
            },
        )
        is AccountState.SignedIn -> MobileSignedInProfile(
            nickname = account.profile.nickname,
            avatarUrl = account.profile.avatarUrl,
            selectedKind = controller.selectedLibraryKind,
            libraryState = controller.libraryState,
            gridState = gridState,
            onSelectKind = controller::selectLibraryKind,
            onOpenRelease = onOpenRelease,
            onLogout = { scope.launch { controller.logout() } },
        )
    }
}

@Composable
private fun MobileLogin(
    error: String?,
    onLogin: (String, String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 22.dp, vertical = 34.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Профиль", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text("Войдите в аккаунт YummyAnime", color = AniColors.TextMuted)
        Spacer(Modifier.height(28.dp))
        HoshiraTextField(
            value = email,
            onValueChange = { email = it },
            label = "Email",
            keyboardType = KeyboardType.Email,
        )
        Spacer(Modifier.height(14.dp))
        HoshiraTextField(
            value = password,
            onValueChange = { password = it },
            label = "Пароль",
            password = true,
        )
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = Color(0xFFFF7777))
        }
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = {
                onLogin(email.trim(), password)
                password = ""
            },
            enabled = email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AniColors.Orange),
            shape = CircleShape,
        ) {
            Text("Войти", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MobileSignedInProfile(
    nickname: String,
    avatarUrl: String?,
    selectedKind: AnimeListKind,
    libraryState: UiState<AccountLibrary>,
    gridState: LazyGridState,
    onSelectKind: (AnimeListKind) -> Unit,
    onOpenRelease: (Int) -> Unit,
    onLogout: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteImage(
                url = avatarUrl,
                contentDescription = nickname,
                modifier = Modifier.size(58.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Профиль", color = AniColors.TextMuted)
                Text(nickname, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onLogout) {
                Text("Выйти", color = AniColors.OrangeBright)
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(AnimeListKind.entries) { kind ->
                val selected = kind == selectedKind
                Text(
                    kind.title,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) AniColors.Orange else AniColors.Surface)
                        .clickable { onSelectKind(kind) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    color = if (selected) Color.White else AniColors.TextMuted,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        when (libraryState) {
            UiState.Loading -> MobileLoading("Загружаем списки…")
            is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(libraryState.message, color = Color(0xFFFF7777))
            }
            is UiState.Ready -> {
                val releases = libraryState.value.releases(selectedKind)
                if (releases.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("В этом списке пока пусто", color = AniColors.TextMuted)
                    }
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(
                            start = 18.dp,
                            end = 18.dp,
                            bottom = MOBILE_BOTTOM_BAR_HEIGHT + 20.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(releases, key = ReleaseDto::id) { release ->
                            MobilePosterCard(
                                release = release,
                                onClick = { onOpenRelease(release.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileDetailsScreen(
    state: UiState<ReleaseDto>,
    listState: LazyListState,
    membershipState: UiState<AnimeMembership>,
    accountSignedIn: Boolean,
    accountActionInProgress: Boolean,
    accountActionError: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlayEpisode: (EpisodeDto) -> Unit,
    onSetAnimeList: (AnimeListKind?) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    when (state) {
        UiState.Loading -> MobileLoading("Загружаем релиз…")
        is UiState.Error -> ErrorState(state.message, onRetry, Modifier.fillMaxSize())
        is UiState.Ready -> {
            val release = state.value
            val dubbingGroups = remember(release.id, release.episodes) {
                release.episodes
                    .filter { it.externalPlayerUrl != null }
                    .groupBy { episode ->
                        episode.name?.trim()?.takeIf(String::isNotEmpty) ?: "Озвучка не указана"
                    }
                    .entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, List<EpisodeDto>>> {
                            it.value.map(EpisodeDto::displayOrdinal).distinct().size
                        }.thenBy { it.key },
                    )
            }
            var selectedDubbing by remember(release.id) {
                mutableStateOf(dubbingGroups.firstOrNull()?.key)
            }
            val selectedDubbingEpisodes = dubbingGroups
                .firstOrNull { it.key == selectedDubbing }
                ?.value
                .orEmpty()
            val playerGroups = remember(release.id, selectedDubbing, selectedDubbingEpisodes) {
                selectedDubbingEpisodes
                    .groupBy(EpisodeDto::displayPlayerName)
                    .mapValues { (_, episodes) ->
                        episodes.sortedBy(EpisodeDto::ordinal).distinctBy(EpisodeDto::displayOrdinal)
                    }
                    .entries
                    .sortedWith(
                        compareBy<Map.Entry<String, List<EpisodeDto>>> {
                            playerSourcePriority(it.key)
                        }.thenBy { it.key },
                    )
            }
            var selectedPlayer by remember(release.id, selectedDubbing) {
                mutableStateOf(
                    playerGroups.firstOrNull {
                        !it.key.contains("Alloha", ignoreCase = true)
                    }?.key,
                )
            }
            val selectedEpisodes = playerGroups
                .firstOrNull { it.key == selectedPlayer }
                ?.value
                .orEmpty()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentPadding = PaddingValues(bottom = 30.dp),
            ) {
                item {
                    Box(Modifier.fillMaxWidth().height(390.dp)) {
                        RemoteImage(
                            url = release.backdropFullUrl,
                            placeholderUrl = release.backdropHighUrl
                                ?: release.posterStandardUrl,
                            contentDescription = release.displayName,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    1f to AniColors.Background,
                                ),
                            ),
                        )
                        Box(
                            modifier = Modifier
                                .statusBarsPadding()
                                .padding(16.dp)
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.62f))
                                .clickable(onClick = onBack),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.size(22.dp)) {
                                val stroke = 2.8.dp.toPx()
                                val centerY = size.height / 2f
                                val tipX = size.width * 0.24f
                                val endX = size.width * 0.82f
                                val wingX = size.width * 0.50f
                                val wingOffset = size.height * 0.28f
                                drawLine(
                                    color = Color.White,
                                    start = androidx.compose.ui.geometry.Offset(endX, centerY),
                                    end = androidx.compose.ui.geometry.Offset(tipX, centerY),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round,
                                )
                                drawLine(
                                    color = Color.White,
                                    start = androidx.compose.ui.geometry.Offset(tipX, centerY),
                                    end = androidx.compose.ui.geometry.Offset(wingX, centerY - wingOffset),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round,
                                )
                                drawLine(
                                    color = Color.White,
                                    start = androidx.compose.ui.geometry.Offset(tipX, centerY),
                                    end = androidx.compose.ui.geometry.Offset(wingX, centerY + wingOffset),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(horizontal = 20.dp, vertical = 22.dp),
                        ) {
                            Text(
                                release.displayName,
                                style = MaterialTheme.typography.headlineLarge,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(7.dp))
                            Text(release.metadata, color = AniColors.TextMuted)
                        }
                    }
                }
                if (!release.description.isNullOrBlank()) {
                    item {
                        Text(
                            release.description,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                            color = AniColors.Text.copy(alpha = 0.88f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                item {
                    MobileMembershipActions(
                        membershipState = membershipState,
                        accountSignedIn = accountSignedIn,
                        accountActionInProgress = accountActionInProgress,
                        accountActionError = accountActionError,
                        onSetAnimeList = onSetAnimeList,
                        onToggleFavorite = onToggleFavorite,
                        onOpenAccount = onOpenAccount,
                    )
                }
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
                    ) {
                        Text(
                            "Студия озвучки",
                            style = MaterialTheme.typography.titleMedium,
                            color = AniColors.TextMuted,
                        )
                        Spacer(Modifier.height(11.dp))
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            DubbingDropdown(
                                options = dubbingGroups.map { group ->
                                    group.key to group.value
                                        .map(EpisodeDto::displayOrdinal)
                                        .distinct()
                                        .size
                                },
                                selected = selectedDubbing,
                                onSelected = { selectedDubbing = it },
                                width = maxWidth,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "Источник плеера",
                            style = MaterialTheme.typography.titleMedium,
                            color = AniColors.TextMuted,
                        )
                        Spacer(Modifier.height(11.dp))
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            DubbingDropdown(
                                options = playerGroups.map { it.key to it.value.size },
                                selected = selectedPlayer,
                                onSelected = { selectedPlayer = it },
                                placeholder = "Выберите источник",
                                width = maxWidth,
                                disabledOption = {
                                    it.contains("Alloha", ignoreCase = true)
                                },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Эпизоды",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text("${selectedEpisodes.size} доступно", color = AniColors.TextMuted)
                    }
                }
                if (selectedEpisodes.isEmpty()) {
                    item {
                        Text(
                            "Для выбранной озвучки и плеера эпизоды пока недоступны",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                            color = AniColors.TextMuted,
                        )
                    }
                }
                items(selectedEpisodes, key = EpisodeDto::id) { episode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayEpisode(episode) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RemoteImage(
                            url = episode.previewUrl ?: release.backdropStandardUrl,
                            contentDescription = episode.title,
                            modifier = Modifier
                                .width(128.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(episode.shortTitle, fontWeight = FontWeight.Bold)
                            Text(
                                listOfNotNull(episode.name, episode.displayPlayerName)
                                    .joinToString(" · "),
                                color = AniColors.TextMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("▶", color = AniColors.OrangeBright)
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileMembershipActions(
    membershipState: UiState<AnimeMembership>,
    accountSignedIn: Boolean,
    accountActionInProgress: Boolean,
    accountActionError: String?,
    onSetAnimeList: (AnimeListKind?) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val membership = (membershipState as? UiState.Ready)?.value
    val membershipError = accountActionError
        ?: (membershipState as? UiState.Error)?.message
    val actionsEnabled = !accountActionInProgress && membershipState !is UiState.Loading

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Моя библиотека",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            if (accountActionInProgress || membershipState is UiState.Loading) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = AniColors.OrangeBright,
                    strokeWidth = 2.dp,
                )
            }
        }
        Spacer(Modifier.height(11.dp))

        if (!accountSignedIn) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(CircleShape)
                    .background(AniColors.SurfaceHigh)
                    .border(1.dp, AniColors.Border, CircleShape)
                    .clickable(onClick = onOpenAccount)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MobileHeartIcon(
                    filled = false,
                    color = AniColors.OrangeBright,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("Войти, чтобы сохранять тайтлы", fontWeight = FontWeight.Bold)
            }
            return@Column
        }

        MobileAnimeListDropdown(
            selected = membership?.list,
            enabled = actionsEnabled,
            onSelected = onSetAnimeList,
        )
        Spacer(Modifier.height(10.dp))
        val isFavorite = membership?.isFavorite == true
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(CircleShape)
                .background(
                    if (isFavorite) AniColors.Orange.copy(alpha = 0.16f) else AniColors.SurfaceHigh,
                )
                .border(
                    1.dp,
                    if (isFavorite) AniColors.Orange else AniColors.Border,
                    CircleShape,
                )
                .clickable(enabled = actionsEnabled, onClick = onToggleFavorite)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MobileHeartIcon(
                filled = isFavorite,
                color = AniColors.OrangeBright,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (isFavorite) "В любимом" else "Добавить в любимое",
                color = if (actionsEnabled) AniColors.Text else AniColors.TextMuted,
                fontWeight = FontWeight.Bold,
            )
        }
        if (membershipError != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                membershipError,
                color = Color(0xFFFF7777),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MobileHeartIcon(
    filled: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val heart = Path().apply {
            moveTo(size.width * 0.5f, size.height * 0.9f)
            cubicTo(
                size.width * 0.42f,
                size.height * 0.82f,
                size.width * 0.1f,
                size.height * 0.62f,
                size.width * 0.1f,
                size.height * 0.36f,
            )
            cubicTo(
                size.width * 0.1f,
                size.height * 0.19f,
                size.width * 0.23f,
                size.height * 0.1f,
                size.width * 0.37f,
                size.height * 0.1f,
            )
            cubicTo(
                size.width * 0.45f,
                size.height * 0.1f,
                size.width * 0.49f,
                size.height * 0.16f,
                size.width * 0.5f,
                size.height * 0.24f,
            )
            cubicTo(
                size.width * 0.51f,
                size.height * 0.16f,
                size.width * 0.55f,
                size.height * 0.1f,
                size.width * 0.63f,
                size.height * 0.1f,
            )
            cubicTo(
                size.width * 0.77f,
                size.height * 0.1f,
                size.width * 0.9f,
                size.height * 0.19f,
                size.width * 0.9f,
                size.height * 0.36f,
            )
            cubicTo(
                size.width * 0.9f,
                size.height * 0.62f,
                size.width * 0.58f,
                size.height * 0.82f,
                size.width * 0.5f,
                size.height * 0.9f,
            )
            close()
        }
        if (filled) {
            drawPath(heart, color = color)
        } else {
            drawPath(
                path = heart,
                color = color,
                style = Stroke(
                    width = 2.2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

@Composable
private fun MobileAnimeListDropdown(
    selected: AnimeListKind?,
    enabled: Boolean,
    onSelected: (AnimeListKind?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(CircleShape)
                .background(AniColors.SurfaceHigh)
                .border(
                    1.dp,
                    if (expanded) AniColors.Orange else AniColors.Border,
                    CircleShape,
                )
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected?.title ?: "Добавить в список",
                modifier = Modifier.weight(1f),
                color = if (enabled) AniColors.Text else AniColors.TextMuted,
                fontWeight = FontWeight.Bold,
            )
            DropdownChevron(
                expanded = expanded,
                color = if (enabled) AniColors.OrangeBright else AniColors.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = AniColors.SurfaceHigh,
            shape = RoundedCornerShape(18.dp),
        ) {
            AnimeListKind.entries
                .filterNot { it == AnimeListKind.Favorite }
                .forEach { kind ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                kind.title,
                                color = if (kind == selected) {
                                    AniColors.OrangeBright
                                } else {
                                    AniColors.Text
                                },
                                fontWeight = if (kind == selected) {
                                    FontWeight.ExtraBold
                                } else {
                                    FontWeight.Medium
                                },
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(kind)
                        },
                    )
                }
            if (selected != null) {
                DropdownMenuItem(
                    text = {
                        Text("Убрать из списка", color = Color(0xFFFF7777), fontWeight = FontWeight.Bold)
                    },
                    onClick = {
                        expanded = false
                        onSelected(null)
                    },
                )
            }
        }
    }
}

@Composable
private fun MobilePosterCard(
    release: ReleaseDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.clickable(onClick = onClick)) {
        RemoteImage(
            url = release.posterThumbnailUrl,
            contentDescription = release.displayName,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            release.displayName,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            listOfNotNull(release.year?.toString(), release.type?.description).joinToString(" · "),
            color = AniColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun MobileSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
    )
}

@Composable
private fun MobileLoading(
    label: String = "Загружаем…",
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = AniColors.Orange)
            Spacer(Modifier.height(14.dp))
            Text(label, color = AniColors.TextMuted)
        }
    }
}

@Composable
private fun HoshiraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = CircleShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AniColors.Orange,
            unfocusedBorderColor = AniColors.Border,
            focusedLabelColor = AniColors.OrangeBright,
            cursorColor = AniColors.OrangeBright,
            focusedContainerColor = AniColors.Surface,
            unfocusedContainerColor = AniColors.Surface,
            disabledContainerColor = AniColors.Surface,
        ),
        modifier = modifier.fillMaxWidth().height(60.dp),
    )
}

@Composable
private fun MobileBottomBar(
    selected: MobileTab,
    onSelect: (MobileTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .navigationBarsPadding()
            .height(MOBILE_BOTTOM_BAR_HEIGHT),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MobileTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSelect(tab) }
                    .padding(top = 8.dp, bottom = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MobileTabIcon(
                    tab = tab,
                    color = if (active) AniColors.OrangeBright else AniColors.TextMuted,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) AniColors.OrangeBright else AniColors.TextMuted,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun MobileTabIcon(tab: MobileTab, color: Color) {
    tab.glyph?.let { glyph ->
        Text(
            glyph,
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        return
    }

    Canvas(Modifier.size(24.dp)) {
        when (tab) {
            MobileTab.Catalog -> {
                val tileSize = size.width * 0.34f
                val gap = size.width * 0.14f
                val corner = androidx.compose.ui.geometry.CornerRadius(size.width * 0.075f)
                listOf(
                    androidx.compose.ui.geometry.Offset(0f, 0f),
                    androidx.compose.ui.geometry.Offset(tileSize + gap, 0f),
                    androidx.compose.ui.geometry.Offset(0f, tileSize + gap),
                    androidx.compose.ui.geometry.Offset(tileSize + gap, tileSize + gap),
                ).forEach { topLeft ->
                    drawRoundRect(
                        color = color,
                        topLeft = topLeft,
                        size = androidx.compose.ui.geometry.Size(tileSize, tileSize),
                        cornerRadius = corner,
                    )
                }
            }

            MobileTab.Profile -> {
                drawCircle(
                    color = color,
                    radius = size.width * 0.20f,
                    center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.28f),
                )
                drawPath(
                    path = Path().apply {
                        moveTo(size.width * 0.12f, size.height * 0.92f)
                        cubicTo(
                            size.width * 0.14f,
                            size.height * 0.61f,
                            size.width * 0.31f,
                            size.height * 0.50f,
                            size.width * 0.50f,
                            size.height * 0.50f,
                        )
                        cubicTo(
                            size.width * 0.69f,
                            size.height * 0.50f,
                            size.width * 0.86f,
                            size.height * 0.61f,
                            size.width * 0.88f,
                            size.height * 0.92f,
                        )
                        close()
                    },
                    color = color,
                )
            }

            else -> Unit
        }
    }
}

private val MOBILE_BOTTOM_BAR_HEIGHT = 62.dp
private val MOBILE_HEADER_HEIGHT = 132.dp
private const val MOBILE_SEARCH_DEBOUNCE_MS = 280L

private data class MobileFilterChoice(
    val label: String,
    val value: String?,
)

private val mobileCatalogTypeOptions = listOf(
    MobileFilterChoice("Все", null),
    MobileFilterChoice("Сериал", "tv"),
    MobileFilterChoice("Фильм", "movie"),
    MobileFilterChoice("OVA", "ova"),
    MobileFilterChoice("ONA", "ona"),
    MobileFilterChoice("Спешл", "special"),
    MobileFilterChoice("Короткий метр", "shortfilm"),
)

private val mobileCatalogStatusOptions = listOf(
    MobileFilterChoice("Все", null),
    MobileFilterChoice("Онгоинг", "ongoing"),
    MobileFilterChoice("Завершён", "released"),
    MobileFilterChoice("Анонс", "announcement"),
)

private val mobileCatalogSeasonOptions = listOf(
    MobileFilterChoice("Все", null),
    MobileFilterChoice("Зима", "winter"),
    MobileFilterChoice("Весна", "spring"),
    MobileFilterChoice("Лето", "summer"),
    MobileFilterChoice("Осень", "autumn"),
)

private val mobileCatalogGenreOptions = listOf(
    MobileFilterChoice("Все", null),
    MobileFilterChoice("Экшен", "ekshen"),
    MobileFilterChoice("Приключения", "priklyucheniya"),
    MobileFilterChoice("Фэнтези", "fentezi"),
    MobileFilterChoice("Исекай", "isekai"),
    MobileFilterChoice("Комедия", "komediya"),
    MobileFilterChoice("Романтика", "romantika"),
    MobileFilterChoice("Драма", "drama"),
    MobileFilterChoice("Детектив", "detektiv"),
    MobileFilterChoice("Триллер", "triller"),
    MobileFilterChoice("Ужасы", "ugasy"),
    MobileFilterChoice("Повседневность", "povsednevnost"),
    MobileFilterChoice("Спорт", "sport"),
)

private val mobileCatalogYearOptions = listOf(
    MobileFilterChoice("Все", null),
    MobileFilterChoice("2026", "2026:2026"),
    MobileFilterChoice("2025", "2025:2025"),
    MobileFilterChoice("2024", "2024:2024"),
    MobileFilterChoice("2020–2023", "2020:2023"),
    MobileFilterChoice("2010–2019", "2010:2019"),
    MobileFilterChoice("До 2010", "1900:2009"),
)

private val mobileCatalogRatingOptions = listOf(
    MobileFilterChoice("Любой", null),
    MobileFilterChoice("от 7", "7.0"),
    MobileFilterChoice("от 8", "8.0"),
    MobileFilterChoice("от 9", "9.0"),
)

private val mobileCatalogSortOptions = listOf(
    MobileFilterChoice("Сначала новые", CatalogSort.Newest.name),
    MobileFilterChoice("По рейтингу", CatalogSort.Rating.name),
    MobileFilterChoice("По году", CatalogSort.Year.name),
    MobileFilterChoice("По популярности", CatalogSort.Popular.name),
    MobileFilterChoice("По названию", CatalogSort.Title.name),
)
