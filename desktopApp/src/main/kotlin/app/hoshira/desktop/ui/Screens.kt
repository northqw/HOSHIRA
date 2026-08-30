package app.hoshira.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hoshira.desktop.UiState
import app.hoshira.desktop.data.AccountLibrary
import app.hoshira.desktop.data.AccountProfile
import app.hoshira.desktop.data.AnimeListKind
import app.hoshira.desktop.data.AnimeMembership
import app.hoshira.desktop.data.CatalogFilters
import app.hoshira.desktop.data.WatchProgress
import app.hoshira.desktop.model.EpisodeDto
import app.hoshira.desktop.model.HomeFeed
import app.hoshira.desktop.model.ReleaseDto
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    state: UiState<HomeFeed>,
    continueWatching: List<WatchProgress>,
    onRetry: () -> Unit,
    onOpenRelease: (Int) -> Unit,
    onPlay: (ReleaseDto) -> Unit,
    onContinueWatching: (WatchProgress) -> Unit,
    isTelevision: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when (state) {
        UiState.Loading -> LoadingState(modifier = modifier)
        is UiState.Error -> ErrorState(state.message, onRetry, modifier)
        is UiState.Ready -> if (isTelevision) {
            TvHomeContent(
                feed = state.value,
                continueWatching = continueWatching,
                onOpenRelease = onOpenRelease,
                onContinueWatching = onContinueWatching,
                modifier = modifier,
            )
        } else {
            HomeContent(
                feed = state.value,
                continueWatching = continueWatching,
                onOpenRelease = onOpenRelease,
                onPlay = onPlay,
                onContinueWatching = onContinueWatching,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun TvHomeContent(
    feed: HomeFeed,
    continueWatching: List<WatchProgress>,
    onOpenRelease: (Int) -> Unit,
    onContinueWatching: (WatchProgress) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryReleases = remember(feed.featured, feed.latest) {
        (feed.latest + feed.featured).distinctBy(ReleaseDto::id)
    }
    var focusedRelease by remember(primaryReleases) {
        mutableStateOf(primaryReleases.firstOrNull() ?: feed.featured.first())
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(AniColors.Background),
    ) {
        val heroHeight = maxHeight * TV_HOME_HERO_FRACTION
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 54.dp),
        ) {
            item(key = "tv-home-hero") {
                TvPreviewHero(
                    release = focusedRelease,
                    modifier = Modifier.height(heroHeight),
                )
            }

            item(key = "tv-home-primary-rail") {
                TvReleaseRail(
                    title = "Последние обновления",
                    releases = primaryReleases,
                    onReleaseFocused = { focusedRelease = it },
                    onOpenRelease = onOpenRelease,
                )
            }

            if (feed.discoveries.isNotEmpty()) {
                item(key = "tv-home-discoveries") {
                    TvReleaseRail(
                        title = "Рекомендуем посмотреть",
                        releases = feed.discoveries,
                        onReleaseFocused = { focusedRelease = it },
                        onOpenRelease = onOpenRelease,
                    )
                }
            }

            if (continueWatching.isNotEmpty()) {
                item(key = "tv-home-continue") {
                    ContinueWatchingRail(
                        items = continueWatching,
                        onClick = onContinueWatching,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvPreviewHero(
    release: ReleaseDto,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxWidth(),
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
                Brush.horizontalGradient(
                    0f to Color.Black,
                    0.44f to Color.Black.copy(alpha = 0.78f),
                    0.78f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.12f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.12f),
                    0.58f to Color.Transparent,
                    0.82f to AniColors.Background.copy(alpha = 0.72f),
                    1f to AniColors.Background,
                ),
            ),
        )

        Column(
            Modifier
                .align(Alignment.TopStart)
                .widthIn(max = 590.dp)
                .padding(start = 42.dp, top = 30.dp),
        ) {
            Text(
                release.displayName,
                color = AniColors.Text,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            release.name.english?.takeIf(String::isNotBlank)?.let { english ->
                Spacer(Modifier.height(7.dp))
                Text(
                    english,
                    color = AniColors.TextMuted,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                release.rating?.let { MetaChip("★ ${"%.1f".format(it)}", accent = true) }
                release.year?.let { MetaChip(it.toString()) }
                release.type?.let { MetaChip(it.description ?: it.value) }
                release.ageRating?.let { MetaChip(it.label) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                release.description.orEmpty(),
                color = AniColors.Text.copy(alpha = 0.90f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvReleaseRail(
    title: String,
    releases: List<ReleaseDto>,
    onReleaseFocused: (ReleaseDto) -> Unit,
    onOpenRelease: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 46.dp)) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 42.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 42.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(releases, key = ReleaseDto::id) { release ->
                TvReleaseCard(
                    release = release,
                    onFocused = { onReleaseFocused(release) },
                    onClick = { onOpenRelease(release.id) },
                )
            }
        }
    }
}

@Composable
private fun TvReleaseCard(
    release: ReleaseDto,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.045f else 1f)
    Box(
        modifier = Modifier
            .width(270.dp)
            .height(152.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(AniColors.Surface)
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) AniColors.OrangeBright else AniColors.Border,
                RoundedCornerShape(10.dp),
            )
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) onFocused()
            }
            .clickable(onClick = onClick),
    ) {
        RemoteImage(
            url = release.backdropStandardUrl,
            placeholderUrl = release.posterThumbnailUrl,
            contentDescription = release.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Medium,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.88f),
                ),
            ),
        )
        Text(
            release.displayName,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomeContent(
    feed: HomeFeed,
    continueWatching: List<WatchProgress>,
    onOpenRelease: (Int) -> Unit,
    onPlay: (ReleaseDto) -> Unit,
    onContinueWatching: (WatchProgress) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeHeroIndex by remember(feed.featured) { mutableStateOf(0) }
    LaunchedEffect(feed.featured) {
        while (feed.featured.size > 1) {
            delay(HOME_HERO_ROTATION_DELAY)
            activeHeroIndex = (activeHeroIndex + 1) % feed.featured.size
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(AniColors.Background),
    ) {
        item {
            Box {
                Crossfade(
                    targetState = feed.featured[activeHeroIndex],
                    animationSpec = tween(durationMillis = 850),
                    label = "home-featured-release",
                ) { release ->
                    HeroSection(
                        release = release,
                        onPlay = { onPlay(release) },
                        onDetails = { onOpenRelease(release.id) },
                    )
                }
                if (feed.featured.size > 1) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 34.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        feed.featured.indices.forEach { index ->
                            val selected = index == activeHeroIndex
                            Box(
                                Modifier
                                    .width(if (selected) 28.dp else 8.dp)
                                    .height(5.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) {
                                            Color.White
                                        } else {
                                            Color.White.copy(alpha = 0.3f)
                                        },
                                    )
                                    .clickable { activeHeroIndex = index },
                            )
                        }
                    }
                }
            }
        }

        if (continueWatching.isNotEmpty()) {
            item {
                ContinueWatchingRail(
                    items = continueWatching,
                    onClick = onContinueWatching,
                )
            }
        }

        item {
            ReleaseRail(
                title = "Последние обновления",
                subtitle = "Свежие серии и новые релизы",
                releases = feed.latest,
                onOpenRelease = onOpenRelease,
            )
        }

        if (feed.discoveries.isNotEmpty()) {
            item {
                ReleaseRail(
                    title = "Откройте что-то новое",
                    subtitle = "Рекомендации из каталога",
                    releases = feed.discoveries,
                    onOpenRelease = onOpenRelease,
                )
            }
        }

    }
}

@Composable
private fun ContinueWatchingRail(
    items: List<WatchProgress>,
    onClick: (WatchProgress) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 56.dp)) {
        Column(Modifier.padding(horizontal = 72.dp)) {
            Text("Продолжить просмотр", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(7.dp))
            Text("С того места, где вы остановились", color = AniColors.TextMuted)
        }
        Spacer(Modifier.height(22.dp))
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 72.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(items, key = { "${it.releaseId}:${it.episodeId}" }) { item ->
                var focused by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(if (focused) 1.04f else 1f)
                Column(
                    Modifier
                        .width(326.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .background(AniColors.Surface)
                        .border(
                            if (focused) 2.dp else 0.dp,
                            if (focused) AniColors.OrangeBright else Color.Transparent,
                            RoundedCornerShape(14.dp),
                        )
                        .onFocusChanged { focused = it.isFocused }
                        .clickable { onClick(item) },
                ) {
                    Box(Modifier.fillMaxWidth().height(183.dp)) {
                        RemoteImage(
                            url = item.imageUrl,
                            contentDescription = item.releaseTitle,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.Medium,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .align(Alignment.BottomCenter)
                                .background(Color.White.copy(alpha = 0.18f)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(item.fraction.coerceIn(0f, 1f))
                                    .height(4.dp)
                                    .background(AniColors.OrangeBright),
                            )
                        }
                    }
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            item.releaseTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "${item.episodeTitle} · ${item.dubbing.ifBlank { item.source }}",
                            color = AniColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private const val HOME_HERO_ROTATION_DELAY = 10_000L

@Composable
private fun HeroSection(
    release: ReleaseDto,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(700.dp),
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
                Brush.horizontalGradient(
                    0f to AniColors.Background,
                    0.46f to AniColors.Background.copy(alpha = 0.76f),
                    0.78f to AniColors.Background.copy(alpha = 0.12f),
                    1f to AniColors.Background.copy(alpha = 0.24f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to AniColors.Background.copy(alpha = 0.15f),
                    0.67f to Color.Transparent,
                    1f to AniColors.Background,
                ),
            ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 72.dp, end = 72.dp, top = 82.dp)
                .width(if (maxWidth < 1180.dp) 610.dp else 720.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AniColors.OrangeBright),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    "СВЕЖИЙ РЕЛИЗ",
                    color = Color(0xFFFFB27A),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = release.displayName,
                color = AniColors.Text,
                style = MaterialTheme.typography.displayLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            release.name.english?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(7.dp))
                Text(
                    it,
                    color = AniColors.TextMuted,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                release.year?.let { MetaChip(it.toString(), accent = true) }
                release.type?.let { MetaChip(it.description ?: it.value) }
                release.ageRating?.let { MetaChip(it.label) }
                if (release.isOngoing) MetaChip("Онгоинг")
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = release.description.orEmpty(),
                color = AniColors.Text.copy(alpha = 0.88f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PrimaryAction("Смотреть сериал", onPlay, leading = "▶")
                SecondaryAction("Подробнее", onDetails, leading = "ⓘ")
            }
        }
    }
}

@Composable
private fun ReleaseRail(
    title: String,
    subtitle: String,
    releases: List<ReleaseDto>,
    onOpenRelease: (Int) -> Unit,
) {
    val listState = rememberLazyListState()

    Column(
        Modifier.fillMaxWidth().padding(bottom = 64.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 72.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(7.dp))
                Text(subtitle, color = AniColors.TextMuted)
            }
            Spacer(Modifier.weight(1f))
            CarouselControls(
                state = listState,
                itemCount = releases.size,
            )
        }
        Spacer(Modifier.height(22.dp))
        LazyRow(
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 72.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(releases, key = { it.id }) { release ->
                PosterCard(
                    release = release,
                    onClick = { onOpenRelease(release.id) },
                    modifier = Modifier.width(220.dp),
                )
            }
        }
    }
}

@Composable
private fun CarouselControls(
    state: LazyListState,
    itemCount: Int,
    step: Int = 4,
) {
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CarouselArrow(
            forward = false,
            enabled = itemCount > 0 && state.canScrollBackward,
            onClick = {
                val target = (state.firstVisibleItemIndex - step).coerceAtLeast(0)
                scope.launch { state.animateScrollToItem(target) }
            },
        )
        CarouselArrow(
            forward = true,
            enabled = itemCount > 0 && state.canScrollForward,
            onClick = {
                val target = (state.firstVisibleItemIndex + step)
                    .coerceAtMost((itemCount - 1).coerceAtLeast(0))
                scope.launch { state.animateScrollToItem(target) }
            },
        )
    }
}

@Composable
fun SearchScreen(
    query: String,
    state: UiState<List<ReleaseDto>>,
    recentSearches: List<ReleaseDto> = emptyList(),
    onRetry: () -> Unit,
    onOpenRelease: (Int) -> Unit,
    onLoadMore: (() -> Unit)? = null,
    isLoadingMore: Boolean = false,
    canLoadMore: Boolean = false,
    loadMoreError: String? = null,
    catalogFilters: CatalogFilters? = null,
    onCatalogFiltersChange: ((CatalogFilters) -> Unit)? = null,
    gridState: LazyGridState? = null,
    isTelevision: Boolean = false,
    isSearchPage: Boolean = false,
    onQueryChange: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val horizontalPadding = if (isTelevision) 42.dp else 72.dp
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AniColors.Background)
            .padding(top = if (isTelevision) 32.dp else 120.dp),
    ) {
        Column(Modifier.padding(horizontal = horizontalPadding)) {
            Text(
                when {
                    isSearchPage -> "Поиск"
                    else -> "Каталог"
                },
                style = MaterialTheme.typography.headlineLarge,
            )
            if (isTelevision && isSearchPage && onQueryChange != null) {
                Spacer(Modifier.height(20.dp))
                TvSearchInput(
                    value = query,
                    onValueChange = onQueryChange,
                )
                Spacer(Modifier.height(14.dp))
            } else {
                Spacer(Modifier.height(7.dp))
            }
            Text(
                if (isSearchPage && query.isBlank() && recentSearches.isNotEmpty()) {
                    "Недавно открытые из поиска"
                } else if (isSearchPage && query.isBlank()) {
                    "Введите название — результаты появятся здесь"
                } else if (!isSearchPage) {
                    "Последние доступные релизы"
                } else {
                    "По запросу «$query»"
                },
                color = AniColors.TextMuted,
            )
            if (catalogFilters != null && onCatalogFiltersChange != null) {
                Spacer(Modifier.height(22.dp))
                CatalogFilterBar(
                    filters = catalogFilters,
                    onChange = onCatalogFiltersChange,
                    singleLine = isTelevision,
                )
                Spacer(Modifier.height(24.dp))
            } else {
                Spacer(Modifier.height(28.dp))
            }
            HorizontalDivider(color = AniColors.Border)
        }

        when (state) {
            UiState.Loading -> LoadingState(modifier = Modifier.weight(1f))
            is UiState.Error -> ErrorState(
                state.message,
                onRetry,
                modifier = Modifier.weight(1f),
            )
            is UiState.Ready -> {
                val releases = if (isSearchPage && query.isBlank()) {
                    recentSearches
                } else {
                    state.value
                }
                if (releases.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (isSearchPage && query.isBlank()) {
                                    "Начните вводить название"
                                } else {
                                    "Ничего не найдено"
                                },
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (isSearchPage && query.isBlank()) {
                                    "Можно использовать экранную клавиатуру телевизора"
                                } else {
                                    "Попробуйте изменить запрос"
                                },
                                color = AniColors.TextMuted,
                            )
                        }
                    }
                } else {
                    val effectiveGridState = gridState ?: rememberLazyGridState()
                    LaunchedEffect(
                        effectiveGridState,
                        releases.size,
                        canLoadMore,
                        isLoadingMore,
                        loadMoreError,
                        onLoadMore,
                    ) {
                        if (!canLoadMore || isLoadingMore || loadMoreError != null) {
                            return@LaunchedEffect
                        }
                        snapshotFlow {
                            effectiveGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        }
                            .distinctUntilChanged()
                            .collect { lastVisibleIndex ->
                                if (
                                    lastVisibleIndex >= releases.lastIndex - 6 &&
                                    lastVisibleIndex >= 0
                                ) {
                                    onLoadMore?.invoke()
                                }
                            }
                    }

                    LazyVerticalGrid(
                        state = effectiveGridState,
                        columns = GridCells.Adaptive(210.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = horizontalPadding,
                            end = horizontalPadding,
                            top = 34.dp,
                            bottom = 64.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(26.dp),
                        verticalArrangement = Arrangement.spacedBy(34.dp),
                    ) {
                        items(releases, key = { it.id }) { release ->
                            PosterCard(
                                release = release,
                                onClick = { onOpenRelease(release.id) },
                            )
                        }
                        if (isLoadingMore) {
                            item(
                                key = "catalog-loading-more",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(96.dp),
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
                                key = "catalog-load-more-error",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(loadMoreError, color = AniColors.TextMuted)
                                    Spacer(Modifier.height(14.dp))
                                    SecondaryAction(
                                        label = "Повторить загрузку",
                                        onClick = { onLoadMore?.invoke() },
                                    )
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
private fun TvSearchInput(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.012f else 1f)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(color = AniColors.Text),
        cursorBrush = SolidColor(AniColors.OrangeBright),
        modifier = Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(AniColors.SurfaceHigh)
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) AniColors.OrangeBright else AniColors.Border,
                RoundedCornerShape(14.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        decorationBox = { input ->
            Box {
                if (value.isBlank()) {
                    Text("Название аниме", color = AniColors.TextMuted)
                }
                input()
            }
        },
    )
}

@Composable
fun LibraryScreen(
    profile: AccountProfile,
    state: UiState<AccountLibrary>,
    selectedKind: AnimeListKind,
    onSelectKind: (AnimeListKind) -> Unit,
    onOpenRelease: (Int) -> Unit,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AniColors.Background)
            .padding(top = 126.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Мои списки",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = profile.nickname,
                    color = AniColors.TextMuted,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.weight(1f))
            SecondaryAction(
                label = "Выйти",
                onClick = onLogout,
                leading = "←",
            )
        }

        Spacer(Modifier.height(28.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 72.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimeListKind.entries.forEach { kind ->
                val count = (state as? UiState.Ready)?.value?.count(kind)
                LibraryTab(
                    kind = kind,
                    count = count,
                    selected = kind == selectedKind,
                    onClick = {
                        onSelectKind(kind)
                        scope.launch { gridState.scrollToItem(0) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(26.dp))

        when (state) {
            UiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = AniColors.OrangeBright)
                }
            }

            is UiState.Error -> ErrorState(
                message = state.message,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )

            is UiState.Ready -> {
                val releases = state.value.releases(selectedKind)
                if (selectedKind in state.value.unavailableLists) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Не удалось загрузить этот список",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(16.dp))
                            SecondaryAction(
                                label = "Повторить",
                                onClick = onRetry,
                                leading = "↻",
                            )
                        }
                    }
                } else if (releases.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Здесь пока пусто",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Добавьте аниме в «${selectedKind.title}» на странице релиза.",
                                color = AniColors.TextMuted,
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(210.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 72.dp,
                            end = 72.dp,
                            bottom = 64.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(26.dp),
                        verticalArrangement = Arrangement.spacedBy(34.dp),
                    ) {
                        items(releases, key = { it.id }) { release ->
                            PosterCard(
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
private fun LibraryTab(
    kind: AnimeListKind,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (kind) {
        AnimeListKind.Watching -> Color(0xFFFF6F61)
        AnimeListKind.Planned -> Color(0xFF9B7BFF)
        AnimeListKind.Watched -> Color(0xFF4ED58A)
        AnimeListKind.Dropped -> Color(0xFF8B929D)
        AnimeListKind.Favorite -> Color(0xFFD44BDD)
        AnimeListKind.Postponed -> Color(0xFFFFB84D)
    }
    Column(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) accent.copy(alpha = 0.86f) else AniColors.SurfaceHigh,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = count?.toString() ?: "—",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = kind.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = if (selected) 1f else 0.72f),
        )
    }
}

@Composable
fun DetailsScreen(
    state: UiState<ReleaseDto>,
    membershipState: UiState<AnimeMembership>,
    accountActionInProgress: Boolean,
    accountActionError: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlayEpisode: (EpisodeDto) -> Unit,
    preferredDubbing: String?,
    preferredSourceName: String,
    onSetAnimeList: (AnimeListKind?) -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        UiState.Loading -> LoadingState("Загружаем релиз…", modifier)
        is UiState.Error -> ErrorState(state.message, onRetry, modifier)
        is UiState.Ready -> DetailsContent(
            release = state.value,
            membershipState = membershipState,
            accountActionInProgress = accountActionInProgress,
            accountActionError = accountActionError,
            onBack = onBack,
            onPlayEpisode = onPlayEpisode,
            preferredDubbing = preferredDubbing,
            preferredSourceName = preferredSourceName,
            onSetAnimeList = onSetAnimeList,
            onToggleFavorite = onToggleFavorite,
            modifier = modifier,
        )
    }
}

@Composable
private fun DetailsContent(
    release: ReleaseDto,
    membershipState: UiState<AnimeMembership>,
    accountActionInProgress: Boolean,
    accountActionError: String?,
    onBack: () -> Unit,
    onPlayEpisode: (EpisodeDto) -> Unit,
    preferredDubbing: String?,
    preferredSourceName: String,
    onSetAnimeList: (AnimeListKind?) -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dubbingGroups = remember(release.id, release.episodes) {
        release.episodes
            .groupBy { episode ->
                episode.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Озвучка не указана"
            }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<EpisodeDto>>> {
                    it.value.map(EpisodeDto::displayOrdinal).distinct().size
                }
                    .thenBy { it.key },
            )
    }
    var selectedDubbing by remember(release.id) {
        mutableStateOf(
            preferredDubbing
                ?.takeIf { preferred -> dubbingGroups.any { it.key == preferred } }
                ?: dubbingGroups.firstOrNull()?.key,
        )
    }
    val selectedDubbingEpisodes = dubbingGroups
        .firstOrNull { it.key == selectedDubbing }
        ?.value
        .orEmpty()
    val playerGroups = remember(release.id, selectedDubbing, selectedDubbingEpisodes) {
        selectedDubbingEpisodes
            .groupBy(EpisodeDto::displayPlayerName)
            .mapValues { (_, episodes) ->
                episodes
                    .sortedBy(EpisodeDto::ordinal)
                    .distinctBy(EpisodeDto::displayOrdinal)
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
                !isDeferredPlayerSource(it.key) &&
                    it.key.contains(preferredSourceName, ignoreCase = true)
            }?.key ?: playerGroups.firstOrNull { !isDeferredPlayerSource(it.key) }?.key,
        )
    }
    val selectedEpisodes = playerGroups
        .firstOrNull { it.key == selectedPlayer }
        ?.value
        .orEmpty()
    val episodeListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val membership = (membershipState as? UiState.Ready)?.value

    LazyColumn(
        modifier = modifier.fillMaxSize().background(AniColors.Background),
    ) {
        item {
            Box(Modifier.fillMaxWidth().heightIn(min = 700.dp)) {
                RemoteImage(
                    url = release.backdropFullUrl,
                    placeholderUrl = selectedEpisodes.firstOrNull()?.previewUrl
                        ?: release.backdropHighUrl
                        ?: release.posterStandardUrl,
                    contentDescription = release.displayName,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.horizontalGradient(
                            0f to AniColors.Background,
                            0.55f to AniColors.Background.copy(alpha = 0.58f),
                            1f to AniColors.Background.copy(alpha = 0.14f),
                        ),
                    ),
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            0f to AniColors.Background.copy(alpha = 0.25f),
                            0.7f to Color.Transparent,
                            1f to AniColors.Background,
                        ),
                    ),
                )

                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 72.dp, end = 72.dp, top = 126.dp, bottom = 76.dp)
                        .widthIn(max = 700.dp),
                ) {
                    SecondaryAction("Назад", onBack, leading = "←")
                    Spacer(Modifier.height(30.dp))
                    Text(
                        release.displayName,
                        style = MaterialTheme.typography.displayLarge,
                        color = AniColors.Text,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(15.dp))
                    Text(release.metadata, color = AniColors.TextMuted)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        release.genres.take(4).forEach { MetaChip(it.name) }
                    }
                    Spacer(Modifier.height(22.dp))
                    Text(
                        release.description.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AniColors.Text.copy(alpha = 0.9f),
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(28.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectedEpisodes.isNotEmpty()) {
                            PrimaryAction(
                                label = "Смотреть сериал",
                                onClick = { onPlayEpisode(selectedEpisodes.first()) },
                                leading = "▶",
                            )
                        }
                        AnimeListDropdown(
                            selected = membership?.list,
                            onSelected = onSetAnimeList,
                            enabled = !accountActionInProgress && membershipState !is UiState.Loading,
                        )
                        SecondaryAction(
                            label = if (membership?.isFavorite == true) {
                                "В любимом"
                            } else {
                                "В любимое"
                            },
                            onClick = onToggleFavorite,
                            leading = if (membership?.isFavorite == true) "♥" else "♡",
                        )
                    }
                    val membershipError = accountActionError
                        ?: (membershipState as? UiState.Error)?.message
                    if (membershipError != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = membershipError,
                            color = Color(0xFFFF8A8A),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 68.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 72.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Эпизоды", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${selectedEpisodes.size} доступно",
                        color = AniColors.TextMuted,
                    )
                    Spacer(Modifier.width(20.dp))
                    CarouselControls(
                        state = episodeListState,
                        itemCount = selectedEpisodes.size,
                        step = 3,
                    )
                }
                if (release.episodes.isEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Эпизоды пока не опубликованы",
                        color = AniColors.TextMuted,
                        modifier = Modifier.padding(horizontal = 72.dp, vertical = 36.dp),
                    )
                } else {
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.padding(horizontal = 72.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Column {
                            Text(
                                "Студия озвучки",
                                style = MaterialTheme.typography.titleMedium,
                                color = AniColors.TextMuted,
                            )
                            Spacer(Modifier.height(13.dp))
                            DubbingDropdown(
                                options = dubbingGroups.map { group ->
                                    group.key to group.value
                                        .map(EpisodeDto::displayOrdinal)
                                        .distinct()
                                        .size
                                },
                                selected = selectedDubbing,
                                onSelected = { dubbing ->
                                    selectedDubbing = dubbing
                                    scope.launch { episodeListState.scrollToItem(0) }
                                },
                            )
                        }
                        Column {
                            Text(
                                "Источник плеера",
                                style = MaterialTheme.typography.titleMedium,
                                color = AniColors.TextMuted,
                            )
                            Spacer(Modifier.height(13.dp))
                            DubbingDropdown(
                                options = playerGroups.map { it.key to it.value.size },
                                selected = selectedPlayer,
                                onSelected = { player ->
                                    selectedPlayer = player
                                    scope.launch { episodeListState.scrollToItem(0) }
                                },
                                placeholder = "Выберите источник",
                                disabledOption = ::isDeferredPlayerSource,
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    LazyRow(
                        state = episodeListState,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 72.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(selectedEpisodes, key = { it.id }) { episode ->
                            EpisodeCard(
                                episode = episode,
                                onClick = { onPlayEpisode(episode) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: EpisodeDto,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(326.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AniColors.Surface)
            .padding(bottom = 14.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(183.dp)) {
            RemoteImage(
                url = episode.previewUrl,
                contentDescription = episode.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
            )
        }
        Spacer(Modifier.height(15.dp))
        Text(
            episode.shortTitle,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            episode.duration?.let { "${it / 60} мин" }.orEmpty(),
            color = AniColors.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        SecondaryAction(
            label = "Смотреть",
            onClick = onClick,
            leading = "▶",
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

private const val TV_HOME_HERO_FRACTION = 0.5f
