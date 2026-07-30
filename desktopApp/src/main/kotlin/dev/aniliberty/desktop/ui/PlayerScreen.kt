package dev.aniliberty.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aniliberty.desktop.PlaybackSession
import dev.aniliberty.desktop.data.PlayerPreferences
import dev.aniliberty.desktop.model.EpisodeDto
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

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
    val playerPageUrl = session?.episode?.externalPlayerUrl
    if (session == null || playerPageUrl == null) {
        ErrorState(
            message = "Не удалось подготовить источник этой серии",
            onRetry = onBack,
            modifier = modifier,
        )
        return
    }

    val release = session.release
    val episode = session.episode
    val studioEpisodes = remember(release.id, episode.name, episode.displayPlayerName) {
        release.episodes
            .filter {
                it.name == episode.name &&
                    it.displayPlayerName == episode.displayPlayerName &&
                    it.externalPlayerUrl != null
            }
            .sortedBy(EpisodeDto::ordinal)
            .distinctBy(EpisodeDto::displayOrdinal)
    }
    val sourceCandidates = remember(
        release.id,
        episode.name,
        episode.displayOrdinal,
    ) {
        release.episodes
            .filter {
                it.name == episode.name &&
                    it.displayOrdinal == episode.displayOrdinal &&
                    it.externalPlayerUrl != null
            }
            .distinctBy(EpisodeDto::displayPlayerName)
            .sortedWith(
                compareBy<EpisodeDto> { playerSourcePriority(it.displayPlayerName) }
                    .thenBy(EpisodeDto::displayPlayerName),
            )
    }
    val currentIndex = studioEpisodes
        .indexOfFirst { it.id == episode.id }
        .takeIf { it >= 0 }
        ?: 0
    val previousEpisode = studioEpisodes.getOrNull(currentIndex - 1)
    val nextEpisode = studioEpisodes.getOrNull(currentIndex + 1)
    val chrome = EmbeddedPlayerChrome(
        title = release.displayName,
        subtitle = listOfNotNull(episode.shortTitle, episode.name)
            .joinToString(" · "),
        position = "${currentIndex + 1} из ${studioEpisodes.size.coerceAtLeast(1)}",
        hasPrevious = previousEpisode != null,
        hasNext = nextEpisode != null,
        sources = sourceCandidates.map { source ->
            EmbeddedPlayerSource(
                episodeId = source.id,
                label = source.displayPlayerName,
                selected = source.id == episode.id,
                playerPageUrl = source.externalPlayerUrl,
            )
        },
        resumeSeconds = session.resumeSeconds,
        startupVolume = preferences.startupVolume,
        preferredQuality = preferredQuality,
        autoplayNext = preferences.autoplayNext,
        controlsHideDelayMs = preferences.controlsHideDelayMs,
        preferredVoice = episode.name,
        fallbackPlayerPageUrls = release.episodes
            .asSequence()
            .filter { it.displayOrdinal == episode.displayOrdinal }
            .mapNotNull(EpisodeDto::externalPlayerUrl)
            .distinct()
            .toList(),
    )

    var playerState by remember {
        mutableStateOf<EmbeddedPlayerState>(EmbeddedPlayerState.Starting)
    }
    var mountNativePlayer by remember {
        mutableStateOf(false)
    }
    val playerPanel = remember {
        AtomicReference<NativeDesktopPlayerPanel?>()
    }
    val currentFullscreen by rememberUpdatedState(isFullscreen)
    val currentFullscreenCallback by rememberUpdatedState(onFullscreenChange)
    val onPlayerAction: (EmbeddedPlayerAction) -> Unit = { action ->
        when (action) {
            EmbeddedPlayerAction.Back -> {
                if (currentFullscreen) {
                    // Restore the top-level window before leaving the route so
                    // AWT does not resize and dispose the media panel at once.
                    currentFullscreenCallback(false)
                    EventQueue.invokeLater(onBack)
                } else {
                    onBack()
                }
            }
            EmbeddedPlayerAction.Previous -> previousEpisode?.let(onPlayEpisode)
            EmbeddedPlayerAction.Next -> nextEpisode?.let(onPlayEpisode)
            is EmbeddedPlayerAction.SetFullscreen ->
                currentFullscreenCallback(action.fullscreen)
            is EmbeddedPlayerAction.SelectSource -> sourceCandidates
                .firstOrNull { it.id == action.episodeId }
                ?.let(onPlayEpisode)
            is EmbeddedPlayerAction.Playback -> onPlayback(
                action.positionSeconds,
                action.durationSeconds,
                action.volume,
                action.quality,
            )
        }
    }

    LaunchedEffect(Unit) {
        // Present one stable Compose frame before attaching the heavyweight
        // native HWND. This prevents the AWT interop layer from exposing its
        // default white surface during the route transition.
        delay(90)
        mountNativePlayer = true
        if (preferences.autoFullscreen && !currentFullscreen) {
            currentFullscreenCallback(true)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (currentFullscreen) {
                currentFullscreenCallback(false)
            }
            playerPanel.getAndSet(null)?.disposePlayer()
        }
    }
    LaunchedEffect(isFullscreen, mountNativePlayer) {
        if (mountNativePlayer) {
            playerPanel.get()?.setFullscreenState(isFullscreen)
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(AniColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = playerState) {
            is EmbeddedPlayerState.Failed -> {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(26.dp))
                        .background(AniColors.Surface.copy(alpha = 0.96f))
                        .border(1.dp, AniColors.Border, RoundedCornerShape(26.dp))
                        .padding(horizontal = 52.dp, vertical = 42.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Не удалось открыть HLS-плеер",
                        color = AniColors.Text,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.message,
                        color = AniColors.TextMuted,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    state.debugInfo?.let { debugInfo ->
                        Spacer(Modifier.height(18.dp))
                        SelectionContainer {
                            Text(
                                text = debugInfo,
                                color = AniColors.TextMuted.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 14,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        SecondaryAction(
                            label = "Копировать диагностику",
                            onClick = {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                    StringSelection(debugInfo),
                                    null,
                                )
                            },
                        )
                    }
                    Spacer(Modifier.height(26.dp))
                    PrimaryAction(
                        label = "Назад",
                        onClick = onBack,
                        leading = "←",
                    )
                }
            }

            else -> {
                if (mountNativePlayer) {
                    SwingPanel(
                        factory = {
                            NativeDesktopPlayerPanel(
                                initialUrl = playerPageUrl,
                                initialChrome = chrome,
                                onStateChange = { playerState = it },
                                onAction = onPlayerAction,
                            ).also(playerPanel::set)
                        },
                        update = { panel ->
                            panel.update(
                                url = playerPageUrl,
                                chrome = chrome,
                                onStateChange = { playerState = it },
                                onAction = onPlayerAction,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                        background = AniColors.Background,
                    )
                } else {
                    LoadingState(
                        label = "Подготавливаем HLS-поток…",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
