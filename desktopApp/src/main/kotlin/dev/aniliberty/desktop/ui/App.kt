package dev.aniliberty.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import dev.aniliberty.desktop.AppController
import dev.aniliberty.desktop.AppRoute
import dev.aniliberty.desktop.AccountState
import dev.aniliberty.desktop.platformCacheDirectory
import dev.aniliberty.desktop.data.AccountProfile
import dev.aniliberty.desktop.data.ReleaseRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

@Composable
fun HoshiraApp(
    repository: ReleaseRepository,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(256L * 1024L * 1024L)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(hoshiraImageCachePath())
                    .maxSizeBytes(768L * 1024L * 1024L)
                    .build()
            }
            .build()
    }

    HoshiraTheme {
        val controller = remember(repository) { AppController(repository) }
        val scope = rememberCoroutineScope()
        val route = controller.route
        val catalogGridState = rememberLazyGridState()
        val searchGridState = rememberLazyGridState()
        val libraryGridState = rememberLazyGridState()
        val accountProfile = (controller.accountState as? AccountState.SignedIn)?.profile
        var showSplash by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            coroutineScope {
                val minimumSplash = async { delay(MINIMUM_SPLASH_DURATION_MS) }
                controller.loadHome()
                minimumSplash.await()
            }
            showSplash = false
        }

        LaunchedEffect(controller) {
            while (true) {
                delay(HOME_REFRESH_INTERVAL_MS)
                if (controller.route !is AppRoute.Player) {
                    controller.refreshHome()
                }
            }
        }

        LaunchedEffect(Unit) {
            controller.restoreAccount()
        }

        LaunchedEffect(controller.searchQuery, controller.catalogFilters, route) {
            when (route) {
                AppRoute.Catalog -> controller.loadCatalog()
                AppRoute.Search -> {
                    delay(SEARCH_DEBOUNCE_MS)
                    controller.search()
                }
                AppRoute.Library -> Unit
                else -> Unit
            }
        }

        Surface(
            modifier = modifier.fillMaxSize(),
            color = AniColors.Background,
            contentColor = AniColors.Text,
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(AniColors.Background),
            ) {
                when (val currentRoute = route) {
                AppRoute.Home -> HomeScreen(
                    state = controller.homeState,
                    onRetry = { scope.launch { controller.loadHome(force = true) } },
                    onOpenRelease = { id -> scope.launch { controller.showDetails(id) } },
                    onPlay = { release -> scope.launch { controller.showDetails(release.id) } },
                    modifier = Modifier.fillMaxSize(),
                )

                AppRoute.Catalog -> SearchScreen(
                    query = "",
                    state = controller.catalogState,
                    onRetry = { scope.launch { controller.loadCatalog(force = true) } },
                    onOpenRelease = { id -> scope.launch { controller.showDetails(id) } },
                    onLoadMore = { scope.launch { controller.loadMoreCatalog() } },
                    isLoadingMore = controller.catalogLoadingMore,
                    canLoadMore = controller.catalogCanLoadMore,
                    loadMoreError = controller.catalogLoadMoreError,
                    catalogFilters = controller.catalogFilters,
                    onCatalogFiltersChange = controller::updateCatalogFilters,
                    gridState = catalogGridState,
                    modifier = Modifier.fillMaxSize(),
                )

                AppRoute.Search -> SearchScreen(
                    query = controller.searchQuery,
                    state = controller.searchState,
                    onRetry = { scope.launch { controller.search() } },
                    onOpenRelease = { id -> scope.launch { controller.showDetails(id) } },
                    gridState = searchGridState,
                    modifier = Modifier.fillMaxSize(),
                )

                AppRoute.Library -> {
                    if (accountProfile != null) {
                        LibraryScreen(
                            profile = accountProfile,
                            state = controller.libraryState,
                            selectedKind = controller.selectedLibraryKind,
                            onSelectKind = controller::selectLibraryKind,
                            onOpenRelease = { id ->
                                scope.launch { controller.showDetails(id) }
                            },
                            onRetry = {
                                scope.launch { controller.refreshLibrary() }
                            },
                            onLogout = {
                                scope.launch { controller.logout() }
                            },
                            gridState = libraryGridState,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LoadingState(
                            label = "Открываем профиль…",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                is AppRoute.Details -> DetailsScreen(
                    state = controller.detailsState,
                    membershipState = controller.animeMembershipState,
                    accountActionInProgress = controller.accountActionInProgress,
                    accountActionError = controller.accountActionError,
                    onBack = controller::closeDetails,
                    onRetry = {
                        scope.launch { controller.showDetails(currentRoute.releaseId) }
                    },
                    onPlayEpisode = controller::playEpisode,
                    onSetAnimeList = { list ->
                        scope.launch { controller.setAnimeList(list) }
                    },
                    onToggleFavorite = {
                        scope.launch { controller.toggleAnimeFavorite() }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                is AppRoute.Player -> PlayerScreen(
                    session = controller.playbackSession,
                    onBack = controller::closePlayer,
                    onPlayEpisode = controller::playEpisode,
                    isFullscreen = isFullscreen,
                    onFullscreenChange = onFullscreenChange,
                    modifier = Modifier.fillMaxSize(),
                )
                }

                if (route !is AppRoute.Player) {
                    TopNavigation(
                        route = route,
                        query = controller.searchQuery,
                        onQueryChange = controller::updateSearchQuery,
                        onHome = controller::showHome,
                        onCatalog = controller::showCatalog,
                        accountProfile = accountProfile,
                        onAccount = {
                            if (accountProfile != null) {
                                scope.launch { controller.showLibrary() }
                            } else {
                                controller.openAccountDialog()
                            }
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                AnimatedVisibility(
                    visible = showSplash,
                    modifier = Modifier.fillMaxSize().zIndex(20f),
                    exit = fadeOut(tween(520)) + scaleOut(
                        targetScale = 1.04f,
                        animationSpec = tween(520),
                    ),
                ) {
                    StartupSplash()
                }
            }
        }

        controller.playerMessage?.let { message ->
            AlertDialog(
                onDismissRequest = controller::dismissPlayerMessage,
                containerColor = AniColors.Surface,
                titleContentColor = AniColors.Text,
                textContentColor = AniColors.TextMuted,
                title = { Text(controller.noticeTitle) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = controller::dismissPlayerMessage) {
                        Text("Понятно", color = AniColors.OrangeBright)
                    }
                },
            )
        }

        if (controller.accountDialogVisible) {
            AccountDialog(
                state = controller.accountState,
                error = controller.accountError,
                onDismiss = controller::closeAccountDialog,
                onLogin = { email, password ->
                    scope.launch { controller.login(email, password) }
                },
                onLogout = {
                    scope.launch { controller.logout() }
                },
                onOpenLibrary = {
                    controller.closeAccountDialog()
                    scope.launch { controller.showLibrary() }
                },
            )
        }
    }
}

private fun hoshiraImageCachePath(): okio.Path {
    return platformCacheDirectory()
        .resolve("images")
        .toString()
        .toPath()
}

@Composable
private fun TopNavigation(
    route: AppRoute,
    query: String,
    onQueryChange: (String) -> Unit,
    onHome: () -> Unit,
    onCatalog: () -> Unit,
    accountProfile: AccountProfile?,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var entered by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(query.isNotBlank()) }
    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(route, query) {
        when {
            query.isNotBlank() -> searchExpanded = true
            route != AppRoute.Search -> searchExpanded = false
        }
    }
    val shadowAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 650),
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(144.dp),
    ) {
        val availableWidth = maxWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(144.dp)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = shadowAlpha),
                            0.58f to Color.Black.copy(alpha = shadowAlpha),
                            1f to Color.Transparent,
                        ),
                    ),
                ),
        )

        BrandMark(
            Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = if (availableWidth < 1180.dp) 28.dp else 52.dp,
                    top = 30.dp,
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onHome,
                ),
        )

        val islandShape = CircleShape
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .height(68.dp)
                .graphicsLayer { alpha = shadowAlpha }
                .shadow(
                    elevation = 30.dp,
                    shape = islandShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.92f),
                    spotColor = Color.Black,
                )
                .clip(islandShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xF707080A),
                            Color(0xF0000000),
                        ),
                    ),
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.1f),
                            Color.Black.copy(alpha = 0.8f),
                        ),
                    ),
                    shape = islandShape,
                )
                .padding(horizontal = if (availableWidth < 1180.dp) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    if (availableWidth < 1180.dp) 2.dp else 6.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavigationItem(
                    text = "Главная",
                    selected = route == AppRoute.Home,
                    onClick = onHome,
                )
                NavigationItem(
                    text = "Каталог",
                    selected = route == AppRoute.Catalog || route == AppRoute.Search,
                    onClick = onCatalog,
                )
            }

            Spacer(Modifier.width(if (availableWidth < 1180.dp) 8.dp else 14.dp))

            SearchBox(
                value = query,
                onValueChange = onQueryChange,
                expanded = searchExpanded,
                onExpandedChange = { searchExpanded = it },
                expandedWidth = if (availableWidth < 1180.dp) 210.dp else 286.dp,
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = 26.dp,
                    end = if (availableWidth < 1180.dp) 28.dp else 52.dp,
                )
                .size(44.dp)
                .shadow(
                    elevation = 22.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.8f),
                    spotColor = Color.Black,
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(AniColors.Orange, Color(0xFF6E2FE8)),
                    ),
                )
                .border(2.dp, Color.White.copy(alpha = 0.78f), CircleShape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onAccount,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (accountProfile?.avatarUrl != null) {
                RemoteImage(
                    url = accountProfile.avatarUrl,
                    contentDescription = accountProfile.nickname,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .width(16.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            .background(Color.White),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(48.dp)
            .width(if (text.length > 10) 154.dp else 80.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) AniColors.Text else AniColors.TextMuted,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(34.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(AniColors.Orange, AniColors.Amber),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    expandedWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val width by animateDpAsState(
        targetValue = if (expanded) expandedWidth else 48.dp,
        animationSpec = tween(durationMillis = 280),
    )

    LaunchedEffect(expanded) {
        if (expanded) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = modifier
            .width(width)
            .height(48.dp)
            .clip(CircleShape)
            .background(Color(0xE008090B))
            .border(1.dp, Color.White.copy(alpha = 0.075f), CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                enabled = !expanded,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onExpandedChange(true) },
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchGlyph(
            modifier = Modifier.size(20.dp),
            color = if (expanded) AniColors.Text else AniColors.TextMuted,
        )

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(110)),
        ) {
            Row(
                modifier = Modifier.width(expandedWidth - 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = AniColors.Text),
                    cursorBrush = SolidColor(AniColors.OrangeBright),
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.isEmpty()) {
                                Text(
                                    "Поиск…",
                                    color = AniColors.TextMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                onValueChange("")
                                onExpandedChange(false)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "×",
                        color = AniColors.TextMuted,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchGlyph(
    modifier: Modifier = Modifier,
    color: Color,
) {
    Canvas(modifier) {
        val strokeWidth = 2.dp.toPx()
        val radius = size.minDimension * 0.29f
        val center = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth),
        )
        drawLine(
            color = color,
            start = Offset(
                center.x + radius * 0.72f,
                center.y + radius * 0.72f,
            ),
            end = Offset(size.width * 0.86f, size.height * 0.86f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun AccountDialog(
    state: AccountState,
    error: String?,
    onDismiss: () -> Unit,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(480.dp)
                .shadow(
                    elevation = 42.dp,
                    shape = RoundedCornerShape(30.dp),
                    ambientColor = Color.Black,
                    spotColor = Color.Black,
                ),
            shape = RoundedCornerShape(30.dp),
            color = Color(0xFF090A0C),
            contentColor = AniColors.Text,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                Color.White.copy(alpha = 0.1f),
            ),
        ) {
            Column(Modifier.padding(34.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = when (state) {
                                is AccountState.SignedIn -> "Ваш аккаунт"
                                else -> "Вход в аккаунт"
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Требуется аккаунт YummyAnime",
                            color = AniColors.TextMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", color = AniColors.TextMuted, style = MaterialTheme.typography.titleLarge)
                    }
                }

                Spacer(Modifier.height(30.dp))
                when (state) {
                    AccountState.Restoring,
                    AccountState.Authenticating,
                    -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(38.dp),
                                color = Color.White,
                                strokeWidth = 3.dp,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                if (state == AccountState.Authenticating) {
                                    "Проверяем данные…"
                                } else {
                                    "Восстанавливаем сессию…"
                                },
                                color = AniColors.TextMuted,
                            )
                        }
                    }

                    AccountState.SignedOut -> {
                        AccountTextField(
                            label = "Email",
                            value = email,
                            onValueChange = { email = it },
                            placeholder = "name@example.com",
                        )
                        Spacer(Modifier.height(18.dp))
                        AccountTextField(
                            label = "Пароль",
                            value = password,
                            onValueChange = { password = it },
                            placeholder = "Введите пароль",
                            password = true,
                        )
                        if (error != null) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = error,
                                color = Color(0xFFFF7777),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(26.dp))
                        PrimaryAction(
                            label = "Войти",
                            onClick = {
                                onLogin(email.trim(), password)
                                password = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            leading = "→",
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Пароль не сохраняется. Токен сессии хранится в защищённом виде.",
                            color = AniColors.TextMuted.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                    is AccountState.SignedIn -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(76.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = 0.13f),
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.profile.avatarUrl != null) {
                                    RemoteImage(
                                        url = state.profile.avatarUrl,
                                        contentDescription = state.profile.nickname,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Text(
                                        state.profile.nickname.take(1).uppercase(),
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Spacer(Modifier.width(20.dp))
                            Column {
                                Text(
                                    state.profile.nickname,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "ID ${state.profile.id}",
                                    color = AniColors.TextMuted,
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AccountMetric(
                                label = "Уведомления",
                                value = state.profile.unreadNotifications,
                                modifier = Modifier.weight(1f),
                            )
                            AccountMetric(
                                label = "Сообщения",
                                value = state.profile.unreadMessages,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(26.dp))
                        PrimaryAction(
                            label = "Открыть мои списки",
                            onClick = onOpenLibrary,
                            modifier = Modifier.fillMaxWidth(),
                            leading = "♥",
                        )
                        Spacer(Modifier.height(12.dp))
                        SecondaryAction(
                            label = "Выйти из аккаунта",
                            onClick = onLogout,
                            modifier = Modifier.fillMaxWidth(),
                            leading = "←",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
) {
    Column {
        Text(
            label,
            color = AniColors.TextMuted,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF111216))
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.09f),
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 17.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = AniColors.Text),
            cursorBrush = SolidColor(AniColors.OrangeBright),
            visualTransformation = if (password) {
                PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            decorationBox = { innerTextField ->
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            color = AniColors.TextMuted.copy(alpha = 0.58f),
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun AccountMetric(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(18.dp),
            )
            .padding(18.dp),
    ) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(label, color = AniColors.TextMuted)
    }
}

private const val HOME_REFRESH_INTERVAL_MS = 10 * 60 * 1_000L
private const val MINIMUM_SPLASH_DURATION_MS = 350L
private const val SEARCH_DEBOUNCE_MS = 260L
