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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aniliberty.desktop.PlaybackSession
import dev.aniliberty.desktop.model.EpisodeDto
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    session: PlaybackSession?,
    onBack: () -> Unit,
    onPlayEpisode: (EpisodeDto) -> Unit,
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
            )
        },
    )

    var playerState by remember(playerPageUrl) {
        mutableStateOf<EmbeddedPlayerState>(EmbeddedPlayerState.Starting)
    }
    var mountNativePlayer by remember(playerPageUrl) {
        mutableStateOf(false)
    }
    val playerPanel = remember {
        AtomicReference<NativeWebView2PlayerPanel?>()
    }
    val onPlayerAction: (EmbeddedPlayerAction) -> Unit = { action ->
        when (action) {
            EmbeddedPlayerAction.Back -> onBack()
            EmbeddedPlayerAction.Previous -> previousEpisode?.let(onPlayEpisode)
            EmbeddedPlayerAction.Next -> nextEpisode?.let(onPlayEpisode)
            is EmbeddedPlayerAction.SelectSource -> sourceCandidates
                .firstOrNull { it.id == action.episodeId }
                ?.let(onPlayEpisode)
        }
    }

    LaunchedEffect(playerPageUrl) {
        // Present one stable Compose frame before attaching the heavyweight
        // native HWND. This prevents the AWT interop layer from exposing its
        // default white surface during the route transition.
        delay(90)
        mountNativePlayer = true
    }
    DisposableEffect(Unit) {
        onDispose {
            playerPanel.getAndSet(null)?.disposePlayer()
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
                        text = "Не удалось открыть встроенный плеер",
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
                            NativeWebView2PlayerPanel(
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
                        label = "Загружаем плеер…",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun playerSourcePriority(name: String): Int = when {
    name.contains("Kodik", ignoreCase = true) -> 0
    name.contains("Alloha", ignoreCase = true) -> 1
    name.contains("Sibnet", ignoreCase = true) -> 2
    else -> 3
}
