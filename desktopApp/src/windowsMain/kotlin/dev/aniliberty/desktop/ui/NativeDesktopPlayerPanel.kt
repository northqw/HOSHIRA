package dev.aniliberty.desktop.ui

import dev.aniliberty.desktop.data.HlsStreamResolver
import java.awt.BorderLayout
import java.awt.Color as AwtColor
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Slider
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.paint.Color
import javafx.util.Duration
import javax.swing.JPanel
import kotlin.concurrent.thread
import kotlin.math.roundToLong

/**
 * Windows HLS player. JavaFX Media decodes the adaptive stream directly; no
 * browser engine, provider page or DOM interception is involved.
 */
internal class NativeDesktopPlayerPanel(
    initialUrl: String,
    initialChrome: EmbeddedPlayerChrome,
    onStateChange: (EmbeddedPlayerState) -> Unit,
    onAction: (EmbeddedPlayerAction) -> Unit,
) : JPanel(BorderLayout()) {
    private val fxPanel = JFXPanel().apply {
        background = AwtColor.BLACK
    }
    private val resolver = HlsStreamResolver()
    private val started = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val generation = AtomicLong()

    @Volatile
    private var requestedUrl = initialUrl

    @Volatile
    private var requestedChrome = initialChrome

    @Volatile
    private var stateCallback = onStateChange

    @Volatile
    private var actionCallback = onAction

    @Volatile
    private var fullscreen = false

    private var activeRequestUrl: String? = null
    private var mediaPlayer: MediaPlayer? = null
    private var root: StackPane? = null
    private var mediaView: MediaView? = null
    private var topBar: HBox? = null
    private var bottomBar: VBox? = null
    private var titleLabel: Label? = null
    private var subtitleLabel: Label? = null
    private var sourceMenu: MenuButton? = null
    private var fullscreenButton: Button? = null
    private var playButton: Button? = null
    private var previousButton: Button? = null
    private var nextButton: Button? = null
    private var seekSlider: Slider? = null
    private var volumeSlider: Slider? = null
    private var elapsedLabel: Label? = null
    private var statusLabel: Label? = null
    private var statusSpinner: ProgressIndicator? = null
    private var controlsTimer: PauseTransition? = null
    private var seeking = false
    private var lastPlaybackNotificationNanos = 0L

    init {
        background = AwtColor.BLACK
        add(fxPanel, BorderLayout.CENTER)
        Platform.runLater {
            Platform.setImplicitExit(false)
            installScene()
        }
    }

    override fun addNotify() {
        super.addNotify()
        if (started.compareAndSet(false, true)) {
            startResolution()
        }
    }

    override fun removeNotify() {
        disposePlayer()
        super.removeNotify()
    }

    fun update(
        url: String,
        chrome: EmbeddedPlayerChrome,
        onStateChange: (EmbeddedPlayerState) -> Unit,
        onAction: (EmbeddedPlayerAction) -> Unit,
    ) {
        stateCallback = onStateChange
        actionCallback = onAction
        val urlChanged = requestedUrl != url
        requestedUrl = url
        requestedChrome = chrome
        Platform.runLater {
            updateChrome(chrome)
        }
        if (urlChanged && started.get() && !disposed.get()) {
            startResolution()
        }
    }

    fun setFullscreenState(fullscreen: Boolean) {
        this.fullscreen = fullscreen
        Platform.runLater {
            fullscreenButton?.text = if (fullscreen) "🗗" else "⛶"
        }
    }

    fun disposePlayer() {
        if (!disposed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        val oldActionCallback = actionCallback
        actionCallback = {}
        stateCallback = {}
        Platform.runLater {
            emitPlayback(oldActionCallback)
            controlsTimer?.stop()
            controlsTimer = null
            mediaPlayer?.stop()
            mediaPlayer?.dispose()
            mediaPlayer = null
            mediaView?.mediaPlayer = null
            fxPanel.scene = null
        }
    }

    private fun installScene() {
        if (disposed.get()) return
        val video = MediaView().apply {
            isPreserveRatio = true
            isSmooth = true
        }
        val sceneRoot = StackPane().apply {
            style = "-fx-background-color: #000000;"
            isFocusTraversable = true
        }
        video.fitWidthProperty().bind(sceneRoot.widthProperty())
        video.fitHeightProperty().bind(sceneRoot.heightProperty())

        val chromeLayer = createChrome()
        sceneRoot.children += video
        sceneRoot.children += chromeLayer
        sceneRoot.addEventFilter(MouseEvent.MOUSE_MOVED) {
            showControls()
        }
        sceneRoot.addEventFilter(MouseEvent.MOUSE_CLICKED) { event ->
            if (event.target === sceneRoot || event.target === video) {
                togglePlayback()
            }
            showControls()
        }
        sceneRoot.setOnKeyPressed { event ->
            when (event.code) {
                KeyCode.SPACE, KeyCode.K -> {
                    togglePlayback()
                    event.consume()
                }
                KeyCode.LEFT -> seekBy(-10.0)
                KeyCode.RIGHT -> seekBy(10.0)
                KeyCode.UP -> changeVolume(0.05)
                KeyCode.DOWN -> changeVolume(-0.05)
                KeyCode.F -> emitAction(EmbeddedPlayerAction.SetFullscreen(!fullscreen))
                KeyCode.ESCAPE -> {
                    if (fullscreen) {
                        emitAction(EmbeddedPlayerAction.SetFullscreen(false))
                    } else {
                        emitAction(EmbeddedPlayerAction.Back)
                    }
                }
                else -> Unit
            }
        }

        root = sceneRoot
        mediaView = video
        fxPanel.scene = Scene(sceneRoot, Color.BLACK)
        updateChrome(requestedChrome)
        showResolving("Получаем прямой HLS-поток…")
    }

    private fun createChrome(): StackPane {
        val backButton = playerButton("←").apply {
            setOnAction { emitAction(EmbeddedPlayerAction.Back) }
        }
        val heading = VBox(2.0).apply {
            alignment = Pos.CENTER_LEFT
        }
        titleLabel = Label().apply {
            style = TITLE_STYLE
        }
        subtitleLabel = Label().apply {
            style = MUTED_LABEL_STYLE
        }
        heading.children.addAll(titleLabel, subtitleLabel)

        sourceMenu = MenuButton().apply {
            style = MENU_STYLE
        }
        fullscreenButton = playerButton("⛶").apply {
            setOnAction {
                emitAction(EmbeddedPlayerAction.SetFullscreen(!fullscreen))
            }
        }
        val upper = HBox(12.0).apply {
            padding = Insets(20.0, 22.0, 18.0, 22.0)
            alignment = Pos.CENTER_LEFT
            style = TOP_GRADIENT_STYLE
            children.addAll(backButton, heading, sourceMenu, fullscreenButton)
        }
        HBox.setHgrow(heading, Priority.ALWAYS)
        topBar = upper

        seekSlider = Slider(0.0, SEEK_RANGE, 0.0).apply {
            maxWidth = Double.MAX_VALUE
            style = SLIDER_STYLE
            addEventHandler(MouseEvent.MOUSE_PRESSED) {
                seeking = true
                controlsTimer?.stop()
            }
            addEventHandler(MouseEvent.MOUSE_RELEASED) {
                seekToSliderPosition()
                seeking = false
                scheduleControlsHide()
            }
        }
        previousButton = playerButton("⏮").apply {
            setOnAction { emitAction(EmbeddedPlayerAction.Previous) }
        }
        playButton = playerButton("▶").apply {
            setOnAction { togglePlayback() }
        }
        nextButton = playerButton("⏭").apply {
            setOnAction { emitAction(EmbeddedPlayerAction.Next) }
        }
        elapsedLabel = Label("00:00 / 00:00").apply {
            minWidth = 118.0
            style = LABEL_STYLE
        }
        val qualityLabel = Label("HLS · AUTO").apply {
            style = BADGE_STYLE
        }
        val volumeGlyph = Label("🔊").apply {
            style = LABEL_STYLE
        }
        volumeSlider = Slider(0.0, 1.0, requestedChrome.startupVolume.toDouble()).apply {
            prefWidth = 110.0
            style = SLIDER_STYLE
            valueProperty().addListener { _, _, value ->
                mediaPlayer?.volume = value.toDouble().coerceIn(0.0, 1.0)
            }
        }
        val playbackRow = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                previousButton,
                playButton,
                nextButton,
                elapsedLabel,
                qualityLabel,
                HBox().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                volumeGlyph,
                volumeSlider,
            )
        }
        val lower = VBox(9.0).apply {
            padding = Insets(26.0, 22.0, 20.0, 22.0)
            style = BOTTOM_GRADIENT_STYLE
            children.addAll(seekSlider, playbackRow)
        }
        bottomBar = lower

        statusLabel = Label().apply {
            style = STATUS_STYLE
            isWrapText = true
            maxWidth = 520.0
        }
        statusSpinner = ProgressIndicator().apply {
            prefWidth = 38.0
            prefHeight = 38.0
            style = "-fx-progress-color: #e84393;"
        }
        val statusBox = VBox(15.0).apply {
            alignment = Pos.CENTER
            isMouseTransparent = true
            children.addAll(statusSpinner, statusLabel)
        }
        val border = BorderPane().apply {
            isPickOnBounds = false
            top = upper
            bottom = lower
        }
        return StackPane(border, statusBox).apply {
            isPickOnBounds = false
        }
    }

    private fun startResolution() {
        val currentGeneration = generation.incrementAndGet()
        val url = choosePlayableUrl(requestedUrl, requestedChrome)
        notifyState(EmbeddedPlayerState.Starting)
        Platform.runLater {
            showResolving(
                if (url == null) {
                    "Ищем совместимый HLS-источник…"
                } else {
                    "Получаем прямой HLS-поток…"
                },
            )
        }
        if (url == null) {
            fail(
                currentGeneration,
                "Для этой серии пока нет источника с прямым HLS-потоком.",
            )
            return
        }
        activeRequestUrl = url

        thread(name = "hoshira-hls-resolver", isDaemon = true) {
            val result = runCatching { resolver.resolve(url) }
            if (disposed.get() || generation.get() != currentGeneration) return@thread
            result.onSuccess { source ->
                Platform.runLater {
                    if (!disposed.get() && generation.get() == currentGeneration) {
                        openMedia(source.url, currentGeneration)
                    }
                }
            }.onFailure { error ->
                fail(
                    currentGeneration,
                    error.message ?: "Не удалось подготовить HLS-поток.",
                )
            }
        }
    }

    private fun choosePlayableUrl(
        primaryUrl: String,
        chrome: EmbeddedPlayerChrome,
    ): String? = sequenceOf(primaryUrl)
        .plus(chrome.sources.asSequence().mapNotNull(EmbeddedPlayerSource::playerPageUrl))
        .distinct()
        .firstOrNull(resolver::supports)

    private fun openMedia(hlsUrl: String, currentGeneration: Long) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.dispose()
            val media = Media(hlsUrl)
            val player = MediaPlayer(media)
            mediaPlayer = player
            mediaView?.mediaPlayer = player
            player.volume = requestedChrome.startupVolume.toDouble().coerceIn(0.0, 1.0)
            volumeSlider?.value = player.volume
            player.onReady = Runnable {
                if (disposed.get() || generation.get() != currentGeneration) return@Runnable
                requestedChrome.resumeSeconds
                    .takeIf { it > 0.0 }
                    ?.let { player.seek(Duration.seconds(it)) }
                hideStatus()
                notifyState(EmbeddedPlayerState.Ready)
                player.play()
                root?.requestFocus()
            }
            player.onPlaying = Runnable {
                playButton?.text = "❚❚"
                scheduleControlsHide()
            }
            player.onPaused = Runnable {
                playButton?.text = "▶"
                showControls(permanent = true)
                emitPlayback()
            }
            player.onStopped = Runnable {
                playButton?.text = "▶"
                emitPlayback()
            }
            player.onEndOfMedia = Runnable {
                emitPlayback()
                if (requestedChrome.autoplayNext && requestedChrome.hasNext) {
                    emitAction(EmbeddedPlayerAction.Next)
                } else {
                    showControls(permanent = true)
                    playButton?.text = "↻"
                }
            }
            player.onError = Runnable {
                fail(
                    currentGeneration,
                    player.error?.message ?: "JavaFX Media не смог открыть HLS-поток.",
                )
            }
            media.setOnError {
                fail(
                    currentGeneration,
                    media.error?.message ?: "Источник вернул неподдерживаемый медиапоток.",
                )
            }
            player.currentTimeProperty().addListener { _, _, _ ->
                updatePlaybackPosition()
            }
        } catch (error: Exception) {
            fail(
                currentGeneration,
                error.message ?: "Не удалось запустить HLS-поток.",
            )
        }
    }

    private fun updateChrome(chrome: EmbeddedPlayerChrome) {
        titleLabel?.text = chrome.title
        subtitleLabel?.text = listOf(chrome.subtitle, chrome.position)
            .filter(String::isNotBlank)
            .joinToString(" · ")
        previousButton?.isDisable = !chrome.hasPrevious
        nextButton?.isDisable = !chrome.hasNext
        fullscreenButton?.text = if (fullscreen) "🗗" else "⛶"

        val playableSources = chrome.sources.filter { resolver.supports(it.playerPageUrl) }
        val selectedSource = playableSources.firstOrNull {
            it.playerPageUrl == activeRequestUrl
        } ?: playableSources.firstOrNull { it.selected }
            ?: playableSources.firstOrNull()
        sourceMenu?.apply {
            text = selectedSource?.label ?: "HLS"
            items.setAll(
                playableSources.map { source ->
                    MenuItem(source.label).apply {
                        isDisable = source.episodeId == selectedSource?.episodeId
                        setOnAction {
                            emitAction(EmbeddedPlayerAction.SelectSource(source.episodeId))
                        }
                    }
                },
            )
            isVisible = playableSources.size > 1
            isManaged = isVisible
        }
    }

    private fun updatePlaybackPosition() {
        val player = mediaPlayer ?: return
        val position = player.currentTime.safeSeconds()
        val duration = player.totalDuration.safeSeconds()
        if (!seeking && duration > 0.0) {
            seekSlider?.value = (position / duration * SEEK_RANGE).coerceIn(0.0, SEEK_RANGE)
        }
        elapsedLabel?.text = "${position.clockText()} / ${duration.clockText()}"
        val now = System.nanoTime()
        if (now - lastPlaybackNotificationNanos >= PLAYBACK_NOTIFICATION_NANOS) {
            lastPlaybackNotificationNanos = now
            emitPlayback()
        }
    }

    private fun seekToSliderPosition() {
        val player = mediaPlayer ?: return
        val duration = player.totalDuration.safeSeconds()
        if (duration <= 0.0) return
        val fraction = (seekSlider?.value ?: 0.0) / SEEK_RANGE
        player.seek(Duration.seconds(duration * fraction))
        emitPlayback()
    }

    private fun seekBy(deltaSeconds: Double) {
        val player = mediaPlayer ?: return
        val duration = player.totalDuration.safeSeconds()
        val target = (player.currentTime.safeSeconds() + deltaSeconds)
            .coerceIn(0.0, duration.takeIf { it > 0.0 } ?: Double.MAX_VALUE)
        player.seek(Duration.seconds(target))
        showControls()
    }

    private fun changeVolume(delta: Double) {
        val slider = volumeSlider ?: return
        slider.value = (slider.value + delta).coerceIn(0.0, 1.0)
        showControls()
    }

    private fun togglePlayback() {
        val player = mediaPlayer ?: return
        when (player.status) {
            MediaPlayer.Status.PLAYING -> player.pause()
            MediaPlayer.Status.READY,
            MediaPlayer.Status.PAUSED,
            MediaPlayer.Status.STOPPED,
            -> player.play()
            else -> Unit
        }
    }

    private fun showResolving(message: String) {
        statusLabel?.text = message
        statusLabel?.isVisible = true
        statusLabel?.isManaged = true
        statusSpinner?.isVisible = true
        statusSpinner?.isManaged = true
        showControls(permanent = true)
    }

    private fun hideStatus() {
        statusLabel?.isVisible = false
        statusLabel?.isManaged = false
        statusSpinner?.isVisible = false
        statusSpinner?.isManaged = false
    }

    private fun showControls(permanent: Boolean = false) {
        topBar?.apply {
            opacity = 1.0
            isMouseTransparent = false
        }
        bottomBar?.apply {
            opacity = 1.0
            isMouseTransparent = false
        }
        root?.cursor = Cursor.DEFAULT
        controlsTimer?.stop()
        if (!permanent) scheduleControlsHide()
    }

    private fun scheduleControlsHide() {
        val player = mediaPlayer ?: return
        if (player.status != MediaPlayer.Status.PLAYING) return
        controlsTimer?.stop()
        controlsTimer = PauseTransition(
            Duration.millis(requestedChrome.controlsHideDelayMs.coerceAtLeast(1_200).toDouble()),
        ).apply {
            setOnFinished {
                topBar?.apply {
                    opacity = 0.0
                    isMouseTransparent = true
                }
                bottomBar?.apply {
                    opacity = 0.0
                    isMouseTransparent = true
                }
                root?.cursor = Cursor.NONE
            }
            playFromStart()
        }
    }

    private fun fail(currentGeneration: Long, message: String) {
        if (disposed.get() || generation.get() != currentGeneration) return
        Platform.runLater {
            if (disposed.get() || generation.get() != currentGeneration) return@runLater
            mediaPlayer?.stop()
            mediaPlayer?.dispose()
            mediaPlayer = null
            mediaView?.mediaPlayer = null
            statusSpinner?.isVisible = false
            statusSpinner?.isManaged = false
            statusLabel?.text = message
            statusLabel?.isVisible = true
            statusLabel?.isManaged = true
            notifyState(EmbeddedPlayerState.Failed(message))
        }
    }

    private fun emitPlayback(callback: (EmbeddedPlayerAction) -> Unit = actionCallback) {
        val player = mediaPlayer ?: return
        val position = player.currentTime.safeSeconds()
        val duration = player.totalDuration.safeSeconds()
        EventQueue.invokeLater {
            callback(
                EmbeddedPlayerAction.Playback(
                    positionSeconds = position,
                    durationSeconds = duration,
                    volume = player.volume.toFloat(),
                    quality = "AUTO",
                ),
            )
        }
    }

    private fun notifyState(state: EmbeddedPlayerState) {
        EventQueue.invokeLater {
            if (!disposed.get()) stateCallback(state)
        }
    }

    private fun emitAction(action: EmbeddedPlayerAction) {
        EventQueue.invokeLater {
            if (!disposed.get()) actionCallback(action)
        }
    }
}

private fun playerButton(text: String): Button = Button(text).apply {
    minWidth = 42.0
    minHeight = 38.0
    style = BUTTON_STYLE
}

private fun Duration.safeSeconds(): Double =
    toSeconds().takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

private fun Double.clockText(): String {
    val totalSeconds = takeIf { it.isFinite() && it >= 0.0 }?.roundToLong() ?: 0L
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private const val SEEK_RANGE = 1_000.0
private const val PLAYBACK_NOTIFICATION_NANOS = 750_000_000L
private const val LABEL_STYLE =
    "-fx-text-fill: #f7f7fa; -fx-font-size: 13px; -fx-font-weight: 700;"
private const val MUTED_LABEL_STYLE =
    "-fx-text-fill: #b9bac5; -fx-font-size: 12px;"
private const val TITLE_STYLE =
    "-fx-text-fill: white; -fx-font-size: 17px; -fx-font-weight: 800;"
private const val STATUS_STYLE =
    "-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: 700;" +
        "-fx-background-color: rgba(18,18,24,0.84); -fx-background-radius: 14px;" +
        "-fx-padding: 12px 18px; -fx-alignment: center;"
private const val BUTTON_STYLE =
    "-fx-background-color: rgba(31,31,40,0.82); -fx-background-radius: 11px;" +
        "-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: 800;" +
        "-fx-cursor: hand;"
private const val MENU_STYLE =
    "-fx-background-color: rgba(31,31,40,0.82); -fx-background-radius: 11px;" +
        "-fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: 700;" +
        "-fx-cursor: hand;"
private const val BADGE_STYLE =
    "-fx-text-fill: #ff8fc8; -fx-font-size: 11px; -fx-font-weight: 800;" +
        "-fx-background-color: rgba(232,67,147,0.18); -fx-background-radius: 8px;" +
        "-fx-padding: 5px 8px;"
private const val SLIDER_STYLE = "-fx-accent: #e84393;"
private const val TOP_GRADIENT_STYLE =
    "-fx-background-color: linear-gradient(to bottom, rgba(0,0,0,0.88), transparent);"
private const val BOTTOM_GRADIENT_STYLE =
    "-fx-background-color: linear-gradient(to top, rgba(0,0,0,0.92), transparent);"
