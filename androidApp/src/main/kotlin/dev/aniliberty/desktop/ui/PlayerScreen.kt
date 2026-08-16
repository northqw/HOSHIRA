package dev.aniliberty.desktop.ui

import android.content.Context
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.aniliberty.desktop.PlaybackSession
import dev.aniliberty.desktop.data.HlsStreamResolver
import dev.aniliberty.desktop.data.PlaybackSource
import dev.aniliberty.desktop.data.PlayerPreferences
import dev.aniliberty.desktop.model.EpisodeDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    session: PlaybackSession?,
    onBack: () -> Unit,
    onPlayEpisode: (EpisodeDto) -> Unit,
    preferences: PlayerPreferences,
    preferredQuality: String?,
    onPlayback: (Double, Double, Float, String?) -> Unit,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playerUrl = session?.episode?.externalPlayerUrl
    if (session == null || playerUrl == null) {
        ErrorState(
            message = "Не удалось подготовить источник этой серии",
            onRetry = onBack,
            modifier = modifier,
        )
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentPlayback by rememberUpdatedState(onPlayback)
    val currentPlayEpisode by rememberUpdatedState(onPlayEpisode)
    val currentFullscreenChange by rememberUpdatedState(onFullscreenChange)
    val currentAutoplayNext by rememberUpdatedState(preferences.autoplayNext)

    val studioEpisodes = remember(session.release.id, session.episode.name, session.episode.displayPlayerName) {
        session.release.episodes
            .filter {
                it.name == session.episode.name &&
                    it.displayPlayerName == session.episode.displayPlayerName &&
                    it.externalPlayerUrl != null
            }
            .sortedBy(EpisodeDto::ordinal)
            .distinctBy(EpisodeDto::displayOrdinal)
    }
    val sourceCandidates = remember(session.release.id, session.episode.name, session.episode.displayOrdinal) {
        session.release.episodes
            .filter {
                it.name == session.episode.name &&
                    it.displayOrdinal == session.episode.displayOrdinal &&
                    it.externalPlayerUrl != null
            }
            .distinctBy(EpisodeDto::displayPlayerName)
            .sortedBy(EpisodeDto::displayPlayerName)
    }
    val resolver = remember { HlsStreamResolver { message -> Log.d(PLAYER_DEBUG_TAG, message) } }
    val visibleSources = remember(sourceCandidates) {
        sourceCandidates.filter { episode ->
            resolver.supports(episode.externalPlayerUrl) || episode.isDeferredAlloha()
        }
    }
    val currentIndex = studioEpisodes.indexOfFirst { it.id == session.episode.id }
        .takeIf { it >= 0 } ?: 0
    val previousEpisode = studioEpisodes.getOrNull(currentIndex - 1)
    val nextEpisode = studioEpisodes.getOrNull(currentIndex + 1)
    val currentNextEpisode by rememberUpdatedState(nextEpisode)

    val player = remember(context) { createNativeHlsPlayer(context.applicationContext) }
    var resolvedSource by remember(playerUrl) { mutableStateOf<PlaybackSource.DirectMedia?>(null) }
    var resolving by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember(playerUrl) { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    var selectedQualityOverride by remember(playerUrl) { mutableStateOf(preferredQuality) }
    var pendingResumeSeconds by remember(playerUrl) { mutableStateOf<Double?>(null) }
    var positionMs by remember(playerUrl) { mutableLongStateOf(0L) }
    var durationMs by remember(playerUrl) { mutableLongStateOf(0L) }
    var seekFraction by remember(playerUrl) { mutableFloatStateOf(0f) }
    var seeking by remember(playerUrl) { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(preferences.startupVolume.coerceIn(0f, 1f)) }
    var playerWidth by remember { mutableFloatStateOf(1f) }
    var playerHeight by remember { mutableFloatStateOf(1f) }
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    val currentResolvedSource by rememberUpdatedState(resolvedSource)
    val debugStartedAt = remember(playerUrl) { SystemClock.elapsedRealtime() }
    fun debug(message: String) {
        Log.d(PLAYER_DEBUG_TAG, "+${SystemClock.elapsedRealtime() - debugStartedAt}ms $message")
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        if (!isFullscreen) currentFullscreenChange(true)
    }

    LaunchedEffect(playerUrl, selectedQualityOverride, retryKey) {
        val resumeSeconds = pendingResumeSeconds ?: session.resumeSeconds
        resolving = true
        buffering = false
        error = null
        controlsVisible = true
        videoAspectRatio = 16f / 9f
        resolvedSource = null
        player.stop()
        player.clearMediaItems()
        val result = withContext(Dispatchers.IO) {
            resolveAndroidHls(
                resolver = resolver,
                primaryUrl = playerUrl,
                fallbackEpisodes = sourceCandidates,
                preferredVoice = session.episode.name,
                preferredQuality = selectedQualityOverride,
                debug = ::debug,
            )
        }
        result.onSuccess { source ->
            resolvedSource = source
            player.volume = volume
            player.setMediaSource(source.toAndroidMediaSource())
            player.seekTo((resumeSeconds * 1_000.0).toLong().coerceAtLeast(0L))
            player.prepare()
            player.playWhenReady = true
            pendingResumeSeconds = null
            debug("native media prepared quality=${source.quality ?: "adaptive"}")
        }.onFailure { cause ->
            resolving = false
            error = cause.message ?: "Не удалось загрузить эпизод."
            debug("HLS resolution failed: ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    resolving = false
                    error = null
                }
                if (playbackState == Player.STATE_ENDED && currentAutoplayNext) {
                    currentNextEpisode?.let(currentPlayEpisode)
                }
            }

            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
                if (value) controlsVisible = true
            }

            override fun onPlayerError(playerError: PlaybackException) {
                resolving = false
                buffering = false
                error = "Поток прервался. Проверьте подключение и попробуйте снова."
                debug("player error=${playerError.errorCodeName}: ${playerError.message}")
            }

            override fun onVolumeChanged(value: Float) {
                volume = value.coerceIn(0f, 1f)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio =
                        (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                            .coerceAtLeast(0.01f)
                }
                debug("video=${videoSize.width}x${videoSize.height} ratio=${videoSize.pixelWidthHeightRatio}")
            }

            override fun onRenderedFirstFrame() {
                resolving = false
                buffering = false
                debug("first native frame rendered")
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            val duration = player.safeDuration()
            currentPlayback(
                player.currentPosition.coerceAtLeast(0L) / 1_000.0,
                duration / 1_000.0,
                player.volume,
                currentResolvedSource?.quality,
            )
            player.removeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
            currentFullscreenChange(false)
        }
    }

    LaunchedEffect(player, resolvedSource) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.safeDuration()
            if (!seeking) {
                seekFraction = if (durationMs > 0L) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
            delay(250L)
        }
    }

    LaunchedEffect(player, resolvedSource) {
        while (true) {
            delay(1_000L)
            val duration = player.safeDuration()
            if (resolvedSource != null && duration > 0L) {
                currentPlayback(
                    player.currentPosition.coerceAtLeast(0L) / 1_000.0,
                    duration / 1_000.0,
                    player.volume,
                    resolvedSource?.quality,
                )
            }
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, sourceMenuExpanded, qualityMenuExpanded, preferences.controlsHideDelayMs) {
        if (controlsVisible && isPlaying && !sourceMenuExpanded && !qualityMenuExpanded) {
            delay(preferences.controlsHideDelayMs.coerceAtLeast(1_200).toLong())
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged {
                playerWidth = it.width.coerceAtLeast(1).toFloat()
                playerHeight = it.height.coerceAtLeast(1).toFloat()
            }
            .pointerInput(player, playerWidth) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        val delta = when {
                            offset.x < playerWidth * 0.38f -> -10_000L
                            offset.x > playerWidth * 0.62f -> 10_000L
                            else -> 0L
                        }
                        if (delta == 0L) {
                            if (player.isPlaying) player.pause() else player.play()
                        } else {
                            player.seekTo(
                                (player.currentPosition + delta)
                                    .coerceIn(0L, player.safeDuration().takeIf { it > 0L } ?: Long.MAX_VALUE),
                            )
                        }
                        controlsVisible = true
                    },
                )
            },
    ) {
        AndroidView(
            factory = { playerContext ->
                TextureView(playerContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    keepScreenOn = true
                    player.setVideoTextureView(this)
                }
            },
            update = { textureView ->
                textureView.applyAspectFitTransform(
                    videoAspectRatio = videoAspectRatio,
                    viewWidth = playerWidth,
                    viewHeight = playerHeight,
                )
            },
            onRelease = { textureView ->
                player.clearVideoTextureView(textureView)
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (controlsVisible) {
            PlayerChrome(
                title = session.release.displayName,
                subtitle = listOfNotNull(session.episode.shortTitle, session.episode.name)
                    .joinToString(" · "),
                positionLabel = "${currentIndex + 1} из ${studioEpisodes.size.coerceAtLeast(1)}",
                sources = visibleSources,
                selectedEpisodeId = session.episode.id,
                sourceMenuExpanded = sourceMenuExpanded,
                onSourceMenuExpandedChange = { sourceMenuExpanded = it },
                onSourceSelected = currentPlayEpisode,
                onBack = onBack,
                isPlaying = isPlaying,
                onTogglePlayback = { if (player.isPlaying) player.pause() else player.play() },
                onSeekBy = { delta ->
                    player.seekTo(
                        (player.currentPosition + delta)
                            .coerceIn(0L, player.safeDuration().takeIf { it > 0L } ?: Long.MAX_VALUE),
                    )
                },
                seekFraction = seekFraction,
                onSeekFractionChange = {
                    seeking = true
                    seekFraction = it
                },
                onSeekFinished = {
                    if (durationMs > 0L) player.seekTo((durationMs * seekFraction).toLong())
                    seeking = false
                },
                elapsed = "${positionMs.clockText()} / ${durationMs.clockText()}",
                quality = resolvedSource?.quality ?: "Авто",
                qualities = resolvedSource?.availableQualities.orEmpty(),
                qualityMenuExpanded = qualityMenuExpanded,
                onQualityMenuExpandedChange = { qualityMenuExpanded = it },
                onQualitySelected = { selectedQuality ->
                    if (selectedQuality != resolvedSource?.quality) {
                        pendingResumeSeconds = player.currentPosition.coerceAtLeast(0L) / 1_000.0
                        selectedQualityOverride = selectedQuality
                    }
                },
                previousEpisode = previousEpisode,
                nextEpisode = nextEpisode,
                onPlayEpisode = currentPlayEpisode,
                isLoading = resolving || buffering,
            )
        }

        if (resolving || buffering) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = AniColors.OrangeBright)
                Text(
                    text = "Загрузка эпизода",
                    color = AniColors.TextMuted,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }

        error?.let { message ->
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 520.dp)
                    .padding(24.dp),
                color = AniColors.Surface.copy(alpha = 0.96f),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(message, color = AniColors.Text, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { retryKey++ },
                        modifier = Modifier.padding(top = 18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AniColors.Orange),
                    ) {
                        Text("Повторить")
                    }
                }
            }
        }
    }
}

private fun TextureView.applyAspectFitTransform(
    videoAspectRatio: Float,
    viewWidth: Float,
    viewHeight: Float,
) {
    if (videoAspectRatio <= 0f || viewWidth <= 0f || viewHeight <= 0f) return
    val viewAspectRatio = viewWidth / viewHeight
    val scaleX = if (viewAspectRatio > videoAspectRatio) {
        videoAspectRatio / viewAspectRatio
    } else {
        1f
    }
    val scaleY = if (viewAspectRatio < videoAspectRatio) {
        viewAspectRatio / videoAspectRatio
    } else {
        1f
    }
    setTransform(
        Matrix().apply {
            setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        },
    )
}

@OptIn(UnstableApi::class)
private fun createNativeHlsPlayer(context: Context): ExoPlayer {
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            30_000,
            75_000,
            2_500,
            5_000,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    val renderers = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
    return ExoPlayer.Builder(context, renderers)
        .setLoadControl(loadControl)
        .build()
        .apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            repeatMode = Player.REPEAT_MODE_OFF
        }
}

@OptIn(UnstableApi::class)
private fun PlaybackSource.DirectMedia.toAndroidMediaSource(): MediaSource {
    val requestUserAgent = headers["User-Agent"] ?: ANDROID_HLS_USER_AGENT
    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent(requestUserAgent)
        .setDefaultRequestProperties(headers - "User-Agent")
    val isHls = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
    val item = MediaItem.Builder()
        .setUri(url)
        .apply {
            if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        .build()
    return if (isHls) {
        HlsMediaSource.Factory(httpDataSourceFactory).createMediaSource(item)
    } else {
        ProgressiveMediaSource.Factory(httpDataSourceFactory).createMediaSource(item)
    }
}

private suspend fun resolveAndroidHls(
    resolver: HlsStreamResolver,
    primaryUrl: String,
    fallbackEpisodes: List<EpisodeDto>,
    preferredVoice: String?,
    preferredQuality: String?,
    debug: (String) -> Unit,
): Result<PlaybackSource.DirectMedia> = runCatching {
    var lastError: Throwable? = null
    val urls = sequenceOf(primaryUrl)
        .plus(fallbackEpisodes.asSequence().mapNotNull(EpisodeDto::externalPlayerUrl))
        .distinct()
        .filter(resolver::supports)
        .toList()
    for (url in urls) {
        try {
            val provider = resolver.inspect(url).provider
            debug("resolving ${provider.displayName}")
            return@runCatching resolver.resolve(
                url = url,
                preferredVoice = preferredVoice,
                preferredQuality = preferredQuality,
                preferHls = true,
            )
        } catch (cause: Throwable) {
            lastError = cause
            debug("resolver attempt failed: ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }
    throw lastError ?: IllegalStateException(
        if (fallbackEpisodes.any(EpisodeDto::isDeferredAlloha)) {
            "Для этой серии пока доступен только Alloha. Его поддержка появится позже."
        } else {
            "Для этой серии не найден доступный источник."
        },
    )
}

@Composable
private fun PlayerChrome(
    title: String,
    subtitle: String,
    positionLabel: String,
    sources: List<EpisodeDto>,
    selectedEpisodeId: String,
    sourceMenuExpanded: Boolean,
    onSourceMenuExpandedChange: (Boolean) -> Unit,
    onSourceSelected: (EpisodeDto) -> Unit,
    onBack: () -> Unit,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    onSeekBy: (Long) -> Unit,
    seekFraction: Float,
    onSeekFractionChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    elapsed: String,
    quality: String,
    qualities: List<String>,
    qualityMenuExpanded: Boolean,
    onQualityMenuExpandedChange: (Boolean) -> Unit,
    onQualitySelected: (String) -> Unit,
    previousEpisode: EpisodeDto?,
    nextEpisode: EpisodeDto?,
    onPlayEpisode: (EpisodeDto) -> Unit,
    isLoading: Boolean,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.92f), Color.Transparent),
                    ),
                )
                .padding(horizontal = 22.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerPill("←  Назад", onBack)
                Column(
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                        .weight(1f),
                ) {
                    Text(
                        title,
                        color = AniColors.Text,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(subtitle, positionLabel).filter(String::isNotBlank).joinToString(" · "),
                        color = AniColors.TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (sources.isNotEmpty()) {
                    Box {
                        PlayerPill(
                            text = "Источник  ${sources.firstOrNull { it.id == selectedEpisodeId }?.displayPlayerName ?: "Плеер"}  ⌄",
                            onClick = { onSourceMenuExpandedChange(!sourceMenuExpanded) },
                        )
                        DropdownMenu(
                            expanded = sourceMenuExpanded,
                            onDismissRequest = { onSourceMenuExpandedChange(false) },
                            modifier = Modifier.widthIn(min = 310.dp, max = 390.dp),
                            containerColor = AniColors.SurfaceHigh,
                        ) {
                            Text(
                                "Выберите плеер",
                                color = AniColors.TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                            sources.forEach { episode ->
                                val enabled = !episode.isDeferredAlloha()
                                DropdownMenuItem(
                                    text = {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                episode.displayPlayerName,
                                                color = if (enabled) AniColors.Text else AniColors.TextMuted,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            if (!enabled) {
                                                Text(
                                                    "Поддержка появится позже",
                                                    color = AniColors.TextMuted.copy(alpha = 0.72f),
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    },
                                    enabled = enabled && episode.id != selectedEpisodeId,
                                    onClick = {
                                        onSourceMenuExpandedChange(false)
                                        onSourceSelected(episode)
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                }
            }
        }

        if (!isLoading) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerRoundButton("−10", { onSeekBy(-10_000L) }, large = true)
                PlayerRoundButton(if (isPlaying) "❚❚" else "▶", onTogglePlayback, large = true, accent = true)
                PlayerRoundButton("+10", { onSeekBy(10_000L) }, large = true)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.96f)),
                    ),
                )
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Slider(
                value = seekFraction,
                onValueChange = onSeekFractionChange,
                onValueChangeFinished = onSeekFinished,
                colors = playerSliderColors(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(elapsed, color = AniColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Box {
                    PlayerPill(
                        text = if (qualities.size > 1) "$quality  ⌄" else quality,
                        onClick = {
                            if (qualities.size > 1) {
                                onQualityMenuExpandedChange(!qualityMenuExpanded)
                            }
                        },
                    )
                    DropdownMenu(
                        expanded = qualityMenuExpanded,
                        onDismissRequest = { onQualityMenuExpandedChange(false) },
                        containerColor = AniColors.SurfaceHigh,
                    ) {
                        qualities.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option,
                                        color = if (option == quality) {
                                            AniColors.OrangeBright
                                        } else {
                                            AniColors.Text
                                        },
                                        fontWeight = FontWeight.Bold,
                                    )
                                },
                                enabled = option != quality,
                                onClick = {
                                    onQualityMenuExpandedChange(false)
                                    onQualitySelected(option)
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                previousEpisode?.let { episode ->
                    PlayerPill("‹ Предыдущая", { onPlayEpisode(episode) })
                    Spacer(Modifier.width(8.dp))
                }
                nextEpisode?.let { episode ->
                    PlayerPill("Следующая ›", { onPlayEpisode(episode) }, accent = true)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

@Composable
private fun PlayerPill(
    text: String,
    onClick: () -> Unit,
    accent: Boolean = false,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = if (accent) AniColors.Orange else AniColors.SurfaceHigh.copy(alpha = 0.92f),
    ) {
        Text(
            text,
            color = AniColors.Text,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun PlayerRoundButton(
    text: String,
    onClick: () -> Unit,
    large: Boolean = false,
    accent: Boolean = false,
) {
    val diameter = if (large) 62.dp else 46.dp
    Surface(
        modifier = Modifier
            .size(diameter)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = if (accent) AniColors.Orange else AniColors.SurfaceHigh.copy(alpha = 0.9f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                color = AniColors.Text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (large) 17.sp else 18.sp,
            )
        }
    }
}

@Composable
private fun playerSliderColors() = SliderDefaults.colors(
    thumbColor = AniColors.OrangeBright,
    activeTrackColor = AniColors.OrangeBright,
    inactiveTrackColor = Color.White.copy(alpha = 0.26f),
)

private fun ExoPlayer.safeDuration(): Long =
    duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L

private fun Long.clockText(): String {
    val totalSeconds = (this / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun EpisodeDto.isDeferredAlloha(): Boolean =
    displayPlayerName.contains("Alloha", ignoreCase = true)

private const val PLAYER_DEBUG_TAG = "HoshiraPlayer"
private const val ANDROID_HLS_USER_AGENT = "Hoshira Android HLS/0.4.0"
