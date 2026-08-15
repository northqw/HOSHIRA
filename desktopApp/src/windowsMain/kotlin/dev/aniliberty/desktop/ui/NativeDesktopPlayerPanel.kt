package dev.aniliberty.desktop.ui

import dev.aniliberty.desktop.data.HlsStreamResolver
import dev.aniliberty.desktop.data.hlsDebugUrl
import java.awt.BorderLayout
import java.awt.Color as AwtColor
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseMotionAdapter
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javafx.animation.KeyFrame
import javafx.animation.PauseTransition
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Slider
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseEvent
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
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
import javax.swing.BorderFactory
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicComboBoxUI
import javax.swing.plaf.basic.BasicSliderUI
import kotlin.concurrent.thread
import kotlin.math.roundToLong
import kotlin.math.roundToInt

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
    private val videoSurface = AwtVideoSurface()
    private val fxPanel = JFXPanel().apply {
        background = AwtColor.BLACK
    }
    private val debugSession = HlsDebugSession()
    private val resolver = HlsStreamResolver(debug = debugSession::record)
    private val started = AtomicBoolean(false)
    private val sceneInstallScheduled = AtomicBoolean(false)
    private val sceneInstalled = AtomicBoolean(false)
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
    private var activeQuality: String? = null
    private var availableQualities: List<String> = emptyList()
    private var selectedQualityOverride: String? = initialChrome.preferredQuality
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
    private var qualityLabel: Label? = null
    private var controlsTimer: PauseTransition? = null
    private var frameCaptureTimer: Timeline? = null
    private var snapshotImage: WritableImage? = null
    private var snapshotBuffers: Array<BufferedImage>? = null
    private var snapshotWriteIndex = 0
    private var firstFrameLogged = false
    private var seeking = false
    private var lastPlaybackNotificationNanos = 0L

    private val awtTitleLabel = JLabel()
    private val awtSubtitleLabel = JLabel()
    private val awtSourceSelector = JComboBox<String>()
    private val awtQualitySelector = JComboBox<String>()
    private val awtBackButton = HoshiraPlayerButton("←   Назад", pill = true)
    private val awtFullscreenButton = HoshiraPlayerButton("⛶", pill = true)
    private val awtRewindButton = HoshiraPlayerButton("↶10", pill = true)
    private val awtPlayButton = HoshiraPlayerButton("▶", pill = true)
    private val awtForwardButton = HoshiraPlayerButton("10↷", pill = true)
    private val awtNextButton = HoshiraPlayerButton("›", accent = true, pill = true)
    private val awtVolumeButton = HoshiraPlayerButton("🔊", pill = true)
    private val awtElapsedLabel = JLabel("00:00 / 00:00")
    private val awtEpisodeLabel = JLabel()
    private val awtSeekSlider = JSlider(0, SEEK_RANGE.toInt(), 0)
    private val awtVolumeSlider = JSlider(0, 100, (initialChrome.startupVolume * 100).toInt())
    private var awtSourceIds = emptyList<String>()
    private var awtSourceUpdate = false
    private var awtQualityUpdate = false
    private var awtSeeking = false

    init {
        background = AwtColor.BLACK
        add(createAwtPlayerHost(), BorderLayout.CENTER)
        installAwtKeyboardActions()
        debugSession.record(
            "JavaFX decoder created prism=${System.getProperty("prism.order", "default")}",
        )
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentShown(event: ComponentEvent?) {
                    scheduleSceneInstall("shown")
                }

                override fun componentResized(event: ComponentEvent?) {
                    scheduleSceneInstall("resized")
                    requestSurfaceRepaint()
                }
            },
        )
        videoSurface.addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(event: java.awt.event.MouseEvent?) {
                    Platform.runLater { showControls() }
                }

                override fun mouseDragged(event: java.awt.event.MouseEvent?) {
                    Platform.runLater { showControls(permanent = true) }
                }
            },
        )
    }

    override fun addNotify() {
        super.addNotify()
        scheduleSceneInstall("addNotify")
        if (started.compareAndSet(false, true)) {
            startResolution()
        }
    }

    override fun removeNotify() {
        // Compose temporarily detaches SwingPanel while moving the window in and
        // out of fullscreen. The player belongs to PlayerScreen's lifecycle, so
        // detaching the peer must not destroy the decoder or the current frame.
        debugSession.record("Swing surface detached; preserving player for reattach")
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
        if (urlChanged) {
            selectedQualityOverride = chrome.preferredQuality
            activeQuality = null
            availableQualities = emptyList()
        }
        Platform.runLater {
            updateChrome(chrome)
        }
        if (urlChanged && started.get() && !disposed.get()) {
            startResolution()
        }
    }

    fun setFullscreenState(fullscreen: Boolean) {
        this.fullscreen = fullscreen
        EventQueue.invokeLater {
            awtFullscreenButton.text = if (fullscreen) "🗗" else "⛶"
        }
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
            frameCaptureTimer?.stop()
            frameCaptureTimer = null
            mediaPlayer?.stop()
            mediaPlayer?.dispose()
            mediaPlayer = null
            mediaView?.mediaPlayer = null
            fxPanel.scene = null
        }
    }

    private fun createAwtPlayerHost(): JComponent {
        val heading = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(awtTitleLabel)
            add(awtSubtitleLabel)
        }
        awtTitleLabel.apply {
            foreground = AWT_TEXT
            font = hoshiraFont(Font.BOLD, 21f)
        }
        awtSubtitleLabel.apply {
            foreground = AWT_MUTED
            font = hoshiraFont(Font.PLAIN, 14f)
        }
        awtSourceSelector.apply {
            preferredSize = Dimension(192, 46)
            maximumSize = Dimension(224, 46)
            isOpaque = false
            foreground = AWT_TEXT
            isFocusable = false
            font = hoshiraFont(Font.BOLD, 12f)
            border = BorderFactory.createEmptyBorder(0, 14, 0, 8)
            ui = HoshiraComboBoxUi()
            renderer = HoshiraComboRenderer(prefix = "ИСТОЧНИК")
            addActionListener {
                if (awtSourceUpdate) return@addActionListener
                val index = selectedIndex
                awtSourceIds.getOrNull(index)?.let { episodeId ->
                    emitAction(EmbeddedPlayerAction.SelectSource(episodeId))
                }
            }
        }
        styleAwtButton(awtBackButton) {
            emitAction(EmbeddedPlayerAction.Back)
        }
        awtBackButton.apply {
            preferredSize = Dimension(114, 48)
            maximumSize = Dimension(124, 48)
            font = hoshiraFont(Font.BOLD, 13f)
            toolTipText = "Назад"
        }
        styleAwtButton(awtFullscreenButton) {
            emitAction(EmbeddedPlayerAction.SetFullscreen(!fullscreen))
        }
        awtFullscreenButton.apply {
            preferredSize = Dimension(46, 46)
            maximumSize = Dimension(46, 46)
            toolTipText = "Полный экран (F)"
        }
        val sourcePill = PillControlPanel().apply {
            preferredSize = Dimension(196, 48)
            maximumSize = Dimension(228, 48)
            add(awtSourceSelector, BorderLayout.CENTER)
        }
        val upper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(20, 32, 8, 32)
            add(awtBackButton)
            add(Box.createHorizontalStrut(24))
            add(heading)
            add(Box.createHorizontalGlue())
            add(sourcePill)
        }

        styleAwtButton(awtRewindButton) {
            Platform.runLater { seekBy(-10.0) }
        }
        awtRewindButton.toolTipText = "Назад на 10 секунд"
        styleAwtButton(awtPlayButton) {
            Platform.runLater { togglePlayback() }
        }
        awtPlayButton.apply {
            preferredSize = Dimension(46, 46)
            maximumSize = Dimension(46, 46)
            toolTipText = "Пауза / продолжить (Пробел)"
        }
        styleAwtButton(awtForwardButton) {
            Platform.runLater { seekBy(10.0) }
        }
        awtForwardButton.toolTipText = "Вперёд на 10 секунд"
        styleAwtButton(awtNextButton) {
            emitAction(EmbeddedPlayerAction.Next)
        }
        awtNextButton.apply {
            preferredSize = Dimension(46, 46)
            maximumSize = Dimension(46, 46)
            font = hoshiraFont(Font.BOLD, 22f)
            toolTipText = "Следующая серия"
        }
        styleAwtButton(awtVolumeButton) {
            val muted = awtVolumeSlider.value > 0
            awtVolumeSlider.value = if (muted) 0 else 100
            awtVolumeButton.text = if (muted) "🔇" else "🔊"
        }
        awtVolumeButton.apply {
            preferredSize = Dimension(46, 46)
            maximumSize = Dimension(46, 46)
            font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
            toolTipText = "Выключить звук"
        }
        awtElapsedLabel.apply {
            foreground = AWT_TEXT
            font = hoshiraFont(Font.BOLD, 12f)
            horizontalAlignment = SwingConstants.LEFT
            preferredSize = Dimension(126, 40)
            maximumSize = Dimension(140, 40)
        }
        awtEpisodeLabel.apply {
            foreground = AwtColor(244, 244, 247, 194)
            font = hoshiraFont(Font.BOLD, 13f)
            horizontalAlignment = SwingConstants.CENTER
            border = BorderFactory.createEmptyBorder(0, 14, 0, 14)
        }
        awtSeekSlider.apply {
            isOpaque = false
            ui = HoshiraSliderUi(this)
            addChangeListener {
                if (valueIsAdjusting) {
                    awtSeeking = true
                } else if (awtSeeking) {
                    awtSeeking = false
                    val value = value
                    Platform.runLater {
                        val player = mediaPlayer ?: return@runLater
                        val duration = player.totalDuration.safeSeconds()
                        if (duration > 0.0) {
                            player.seek(
                                Duration.seconds(duration * value / SEEK_RANGE),
                            )
                        }
                    }
                }
            }
            addMouseListener(
                object : MouseAdapter() {
                    override fun mousePressed(event: java.awt.event.MouseEvent) {
                        if (!SwingUtilities.isLeftMouseButton(event)) return
                        awtSeeking = true
                        setSliderValueAtPointer(this@apply, event.x)
                    }

                    override fun mouseReleased(event: java.awt.event.MouseEvent) {
                        if (!SwingUtilities.isLeftMouseButton(event)) return
                        setSliderValueAtPointer(this@apply, event.x)
                        awtSeeking = false
                        seekToAwtSliderPosition()
                    }
                },
            )
        }
        awtVolumeSlider.apply {
            isOpaque = false
            ui = HoshiraSliderUi(this)
            preferredSize = Dimension(92, 32)
            maximumSize = Dimension(110, 32)
            addChangeListener {
                val volume = value / 100.0
                Platform.runLater {
                    mediaPlayer?.volume = volume
                    if (!valueIsAdjusting) emitPlayback()
                }
                awtVolumeButton.text = if (value == 0) "🔇" else "🔊"
            }
        }
        awtQualitySelector.apply {
            preferredSize = Dimension(90, 46)
            maximumSize = Dimension(112, 46)
            isOpaque = false
            foreground = AWT_TEXT
            isFocusable = false
            font = hoshiraFont(Font.BOLD, 12f)
            border = BorderFactory.createEmptyBorder(0, 12, 0, 5)
            ui = HoshiraComboBoxUi()
            renderer = HoshiraComboRenderer()
            addActionListener {
                if (awtQualityUpdate) return@addActionListener
                (selectedItem as? String)?.let(::selectQuality)
            }
        }
        val qualityPill = PillControlPanel().apply {
            preferredSize = Dimension(94, 48)
            maximumSize = Dimension(116, 48)
            add(awtQualitySelector, BorderLayout.CENTER)
        }
        val leftControls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(awtPlayButton)
            add(Box.createHorizontalStrut(8))
            add(awtRewindButton)
            add(Box.createHorizontalStrut(8))
            add(awtForwardButton)
            add(Box.createHorizontalStrut(14))
            add(awtElapsedLabel)
        }
        val episodePill = PillControlPanel().apply {
            preferredSize = Dimension(82, 46)
            maximumSize = Dimension(104, 46)
            add(awtEpisodeLabel, BorderLayout.CENTER)
        }
        val centerControls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(episodePill)
            add(Box.createHorizontalStrut(10))
            add(awtNextButton)
        }
        val rightControls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(qualityPill)
            add(Box.createHorizontalStrut(10))
            add(awtVolumeButton)
            add(Box.createHorizontalStrut(8))
            add(awtVolumeSlider)
            add(Box.createHorizontalStrut(12))
            add(awtFullscreenButton)
        }
        val playbackRow = ThreeZoneControls(leftControls, centerControls, rightControls)
        val lower = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 32, 20, 32)
            add(awtSeekSlider, BorderLayout.NORTH)
            add(playbackRow, BorderLayout.CENTER)
        }
        val overlay = ChromeOverlayPanel().apply {
            layout = BorderLayout()
            add(upper, BorderLayout.NORTH)
            add(lower, BorderLayout.SOUTH)
        }
        // Keep the controls as children of the painted video surface. Swing
        // always invokes paintChildren after paintComponent, so every captured
        // video frame is guaranteed to stay behind the control overlay.
        videoSurface.layout = BorderLayout()
        videoSurface.add(overlay, BorderLayout.CENTER)
        return videoSurface
    }

    private fun setSliderValueAtPointer(slider: JSlider, pointerX: Int) {
        val usableWidth = (slider.width - SLIDER_POINTER_PADDING * 2).coerceAtLeast(1)
        val fraction = ((pointerX - SLIDER_POINTER_PADDING).toDouble() / usableWidth)
            .coerceIn(0.0, 1.0)
        slider.value = (
            slider.minimum + fraction * (slider.maximum - slider.minimum)
        ).roundToInt()
    }

    private fun seekToAwtSliderPosition() {
        val value = awtSeekSlider.value
        Platform.runLater {
            val player = mediaPlayer ?: return@runLater
            val duration = player.totalDuration.safeSeconds()
            if (duration > 0.0) {
                player.seek(Duration.seconds(duration * value / SEEK_RANGE))
                emitPlayback()
            }
        }
    }

    private fun styleAwtButton(button: JButton, action: () -> Unit) {
        button.apply {
            foreground = AWT_TEXT
            font = hoshiraFont(Font.BOLD, 15f)
            isFocusPainted = false
            preferredSize = Dimension(46, 46)
            maximumSize = Dimension(46, 46)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener { action() }
        }
    }

    private fun installAwtKeyboardActions() {
        fun bind(keyStroke: KeyStroke, name: String, action: () -> Unit) {
            getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(keyStroke, name)
            actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent?) = action()
            })
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hoshira-exit-fullscreen") {
            if (fullscreen) {
                emitAction(EmbeddedPlayerAction.SetFullscreen(false))
            } else {
                emitAction(EmbeddedPlayerAction.Back)
            }
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F, 0), "hoshira-toggle-fullscreen") {
            emitAction(EmbeddedPlayerAction.SetFullscreen(!fullscreen))
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "hoshira-toggle-playback") {
            Platform.runLater { togglePlayback() }
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
        video.mediaPlayer = mediaPlayer
        fxPanel.scene = Scene(
            sceneRoot,
            CAPTURE_WIDTH.toDouble(),
            CAPTURE_HEIGHT.toDouble(),
            Color.BLACK,
        )
        updateChrome(requestedChrome)
        when (mediaPlayer?.status) {
            MediaPlayer.Status.READY,
            MediaPlayer.Status.PLAYING,
            MediaPlayer.Status.PAUSED,
            MediaPlayer.Status.STOPPED,
            -> {
                hideStatus()
                updatePlaybackPosition()
                startFrameCapture()
                playButton?.text =
                    if (mediaPlayer?.status == MediaPlayer.Status.PLAYING) "❚❚" else "▶"
                showControls(permanent = mediaPlayer?.status != MediaPlayer.Status.PLAYING)
            }
            else -> showResolving("Получаем прямой HLS-поток…")
        }
        debugSession.record(
            "JavaFX decoder scene installed size=${CAPTURE_WIDTH}x$CAPTURE_HEIGHT " +
                "playerAttached=${video.mediaPlayer != null}",
        )
        requestSurfaceRepaint()
    }

    private fun scheduleSceneInstall(reason: String) {
        if (disposed.get() || sceneInstalled.get()) return
        EventQueue.invokeLater {
            if (disposed.get() || sceneInstalled.get()) return@invokeLater
            val width = this@NativeDesktopPlayerPanel.width
            val height = this@NativeDesktopPlayerPanel.height
            if (width <= 0 || height <= 0) {
                debugSession.record(
                    "JavaFX scene deferred reason=$reason size=${width}x$height",
                )
                return@invokeLater
            }
            if (!sceneInstallScheduled.compareAndSet(false, true)) {
                return@invokeLater
            }
            debugSession.record(
                "JavaFX scene scheduling reason=$reason size=${width}x$height",
            )
            Platform.runLater {
                try {
                    Platform.setImplicitExit(false)
                    if (!disposed.get() && sceneInstalled.compareAndSet(false, true)) {
                        installScene()
                    }
                } catch (error: Throwable) {
                    sceneInstalled.set(false)
                    debugSession.record(
                        "JavaFX decoder scene failed type=${error.javaClass.simpleName} " +
                            "message=${error.message ?: "unknown"}",
                    )
                } finally {
                    sceneInstallScheduled.set(false)
                }
            }
        }
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
        qualityLabel = Label("HLS").apply {
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

    private fun startResolution(
        keepCurrentFrame: Boolean = false,
        resumeSeconds: Double? = null,
        volume: Double? = null,
    ) {
        val currentGeneration = generation.incrementAndGet()
        if (!keepCurrentFrame) {
            Platform.runLater {
                frameCaptureTimer?.stop()
                frameCaptureTimer = null
                snapshotImage = null
                snapshotBuffers = null
            }
            EventQueue.invokeLater {
                videoSurface.frame = null
                videoSurface.status = "Проверяем HLS-источник…"
                videoSurface.repaint()
            }
        }
        debugSession.restart()
        debugSession.record(
                "Swing surface displayable=$isDisplayable " +
                "showing=$isShowing size=${width}x$height " +
                "sceneInstalled=${sceneInstalled.get()} " +
                "prism=${System.getProperty("prism.order", "default")}",
        )
        debugSession.record(
            "primary=${requestedUrl.hlsDebugUrl()} sources=${requestedChrome.sources.size} " +
                "fallbacks=${requestedChrome.fallbackPlayerPageUrls.size}",
        )
        requestedChrome.sources.forEachIndexed { index, source ->
            val diagnostic = resolver.inspect(source.playerPageUrl)
            debugSession.record(
                "source[$index] label=${source.label} selected=${source.selected} " +
                    "provider=${diagnostic.provider.displayName} " +
                    "supported=${diagnostic.supported} " +
                    "detail=${diagnostic.detail ?: "none"} " +
                    "url=${source.playerPageUrl?.hlsDebugUrl() ?: "<none>"}",
            )
        }
        requestedChrome.fallbackPlayerPageUrls.forEachIndexed { index, fallbackUrl ->
            val diagnostic = resolver.inspect(fallbackUrl)
            debugSession.record(
                "fallback[$index] provider=${diagnostic.provider.displayName} " +
                    "supported=${diagnostic.supported} " +
                    "detail=${diagnostic.detail ?: "none"} " +
                    "url=${fallbackUrl.hlsDebugUrl()}",
            )
        }
        val primaryDiagnostic = resolver.inspect(requestedUrl)
        if (primaryDiagnostic.provider == dev.aniliberty.desktop.data.HlsProvider.ALLOHA) {
            debugSession.record(
                "Alloha rejected immediately detail=${primaryDiagnostic.detail ?: "unknown"}",
            )
            fail(
                currentGeneration,
                "Alloha пока нельзя открыть без браузерного движка: источник требует " +
                    "JavaScript, WebSocket и динамические заголовки. Выберите Kodik или CVH.",
            )
            return
        }
        val urls = candidateUrls(requestedUrl, requestedChrome)
            .filter(resolver::supports)
            .toList()
        debugSession.record(
            "playable=${urls.size} candidates=" +
                urls.joinToString { it.hlsDebugUrl() },
        )
        if (!keepCurrentFrame) {
            notifyState(EmbeddedPlayerState.Starting)
            Platform.runLater {
                showResolving(
                    if (urls.isEmpty()) {
                        "Ищем совместимый HLS-источник…"
                    } else {
                        "Проверяем HLS-источники…"
                    },
                )
            }
        }
        if (urls.isEmpty()) {
            fail(
                currentGeneration,
                unavailableSourceMessage(requestedUrl, requestedChrome),
            )
            return
        }

        thread(name = "hoshira-hls-resolver", isDaemon = true) {
            var lastError: Throwable? = null
            var resolvedUrl: String? = null
            var resolvedSourceUrl: String? = null
            var resolvedQuality: String? = null
            var resolvedQualities: List<String> = emptyList()
            val preferredQuality = selectedQualityOverride ?: requestedChrome.preferredQuality
            for (url in urls) {
                val diagnostic = resolver.inspect(url)
                debugSession.record(
                    "attempt provider=${diagnostic.provider.displayName} " +
                        "url=${url.hlsDebugUrl()}",
                )
                val result = runCatching {
                    resolver.resolve(
                        url = url,
                        preferredVoice = requestedChrome.preferredVoice,
                        preferredQuality = preferredQuality,
                    )
                }
                result.onSuccess { source ->
                    resolvedUrl = url
                    resolvedSourceUrl = source.url
                    resolvedQuality = source.quality
                    resolvedQualities = source.availableQualities
                }.onFailure { error ->
                    lastError = error
                    debugSession.record(
                        "attempt failed provider=${diagnostic.provider.displayName} " +
                            "type=${error.javaClass.simpleName} " +
                            "message=${error.message ?: "unknown"}",
                    )
                }
                if (resolvedSourceUrl != null) break
            }
            if (disposed.get() || generation.get() != currentGeneration) return@thread
            val mediaUrl = resolvedSourceUrl
            val requestUrl = resolvedUrl
            if (mediaUrl != null && requestUrl != null) {
                Platform.runLater {
                    if (!disposed.get() && generation.get() == currentGeneration) {
                        activeRequestUrl = requestUrl
                        updateChrome(requestedChrome)
                        openMedia(
                            hlsUrl = mediaUrl,
                            currentGeneration = currentGeneration,
                            quality = resolvedQuality,
                            qualities = resolvedQualities,
                            resumeSeconds = resumeSeconds,
                            volume = volume,
                        )
                    }
                }
            } else {
                val error = lastError
                fail(
                    currentGeneration,
                    error?.message ?: "Не удалось подготовить HLS-поток.",
                    error,
                )
            }
        }
    }

    private fun candidateUrls(
        primaryUrl: String,
        chrome: EmbeddedPlayerChrome,
    ): Sequence<String> = sequenceOf(primaryUrl)
        .plus(chrome.sources.asSequence().mapNotNull(EmbeddedPlayerSource::playerPageUrl))
        .plus(chrome.fallbackPlayerPageUrls.asSequence())
        .distinct()

    private fun unavailableSourceMessage(
        primaryUrl: String,
        chrome: EmbeddedPlayerChrome,
    ): String {
        val providers = candidateUrls(primaryUrl, chrome)
            .map(resolver::inspect)
            .map { it.provider.displayName }
            .filterNot { it == "Неизвестный" || it == "Некорректная ссылка" }
            .distinct()
            .toList()
        return if ("Alloha" in providers) {
            "Найден Alloha, но его поток требует JavaScript/WebSocket и " +
                "динамических заголовков. Подробности есть в диагностике."
        } else if (providers.isNotEmpty()) {
            "Найдены источники ${providers.joinToString()}, но получить прямой HLS не удалось."
        } else {
            "Для этой серии не найден поддерживаемый HLS-источник."
        }
    }

    private fun openMedia(
        hlsUrl: String,
        currentGeneration: Long,
        quality: String?,
        qualities: List<String>,
        resumeSeconds: Double?,
        volume: Double?,
    ) {
        try {
            debugSession.record("JavaFX Media opening ${hlsUrl.hlsDebugUrl()}")
            frameCaptureTimer?.stop()
            frameCaptureTimer = null
            mediaPlayer?.stop()
            mediaPlayer?.dispose()
            val media = Media(hlsUrl)
            val player = MediaPlayer(media)
            mediaPlayer = player
            mediaView?.mediaPlayer = player
            player.volume = (volume ?: requestedChrome.startupVolume.toDouble())
                .coerceIn(0.0, 1.0)
            volumeSlider?.value = player.volume
            EventQueue.invokeLater {
                awtVolumeSlider.value = (player.volume * 100).toInt().coerceIn(0, 100)
            }
            activeQuality = quality
            availableQualities = qualities
            updateQualityControls()
            player.onReady = Runnable {
                if (disposed.get() || generation.get() != currentGeneration) return@Runnable
                (resumeSeconds ?: requestedChrome.resumeSeconds)
                    .takeIf { it > 0.0 }
                    ?.let { player.seek(Duration.seconds(it)) }
                hideStatus()
                debugSession.record(
                    "JavaFX ready duration=${player.totalDuration.safeSeconds()}s " +
                        "media=${media.width}x${media.height} " +
                        "view=${mediaView?.fitWidth?.toInt()}x${mediaView?.fitHeight?.toInt()} " +
                        "tracks=${media.tracks.joinToString { it.javaClass.simpleName }}",
                )
                startFrameCapture()
                notifyState(EmbeddedPlayerState.Ready)
                player.play()
                root?.requestFocus()
                requestSurfaceRepaint()
            }
            player.onPlaying = Runnable {
                debugSession.record("JavaFX playing")
                playButton?.text = "❚❚"
                EventQueue.invokeLater { awtPlayButton.text = "❚❚" }
                scheduleControlsHide()
            }
            player.onPaused = Runnable {
                playButton?.text = "▶"
                EventQueue.invokeLater { awtPlayButton.text = "▶" }
                showControls(permanent = true)
                emitPlayback()
            }
            player.onStopped = Runnable {
                playButton?.text = "▶"
                EventQueue.invokeLater { awtPlayButton.text = "▶" }
                emitPlayback()
            }
            player.onEndOfMedia = Runnable {
                emitPlayback()
                if (requestedChrome.autoplayNext && requestedChrome.hasNext) {
                    emitAction(EmbeddedPlayerAction.Next)
                } else {
                    showControls(permanent = true)
                    playButton?.text = "↻"
                    EventQueue.invokeLater { awtPlayButton.text = "↻" }
                }
            }
            player.onError = Runnable {
                fail(
                    currentGeneration,
                    player.error?.message ?: "JavaFX Media не смог открыть HLS-поток.",
                    player.error,
                )
            }
            media.setOnError {
                fail(
                    currentGeneration,
                    media.error?.message ?: "Источник вернул неподдерживаемый медиапоток.",
                    media.error,
                )
            }
            player.currentTimeProperty().addListener { _, _, _ ->
                updatePlaybackPosition()
            }
        } catch (error: Exception) {
            fail(
                currentGeneration,
                error.message ?: "Не удалось запустить HLS-поток.",
                error,
            )
        }
    }

    private fun startFrameCapture() {
        val video = mediaView ?: return
        frameCaptureTimer?.stop()
        snapshotImage = WritableImage(CAPTURE_WIDTH, CAPTURE_HEIGHT)
        snapshotBuffers = Array(2) {
            BufferedImage(
                CAPTURE_WIDTH,
                CAPTURE_HEIGHT,
                BufferedImage.TYPE_INT_ARGB_PRE,
            )
        }
        snapshotWriteIndex = 0
        firstFrameLogged = false
        val parameters = SnapshotParameters().apply {
            fill = Color.BLACK
        }
        frameCaptureTimer = Timeline(
            KeyFrame(
                Duration.millis(FRAME_CAPTURE_INTERVAL_MS),
                EventHandler { captureVideoFrame(video, parameters) },
            ),
        ).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }
        debugSession.record(
            "Swing frame capture started ${CAPTURE_WIDTH}x$CAPTURE_HEIGHT " +
                "interval=${FRAME_CAPTURE_INTERVAL_MS.toInt()}ms",
        )
    }

    private fun captureVideoFrame(
        video: MediaView,
        parameters: SnapshotParameters,
    ) {
        if (disposed.get()) return
        val image = snapshotImage ?: return
        val buffers = snapshotBuffers ?: return
        val target = buffers[snapshotWriteIndex]
        try {
            video.snapshot(parameters, image)
            val pixels = (target.raster.dataBuffer as DataBufferInt).data
            image.pixelReader.getPixels(
                0,
                0,
                CAPTURE_WIDTH,
                CAPTURE_HEIGHT,
                PixelFormat.getIntArgbPreInstance(),
                pixels,
                0,
                CAPTURE_WIDTH,
            )
            videoSurface.frame = target
            snapshotWriteIndex = (snapshotWriteIndex + 1) % buffers.size
            if (!firstFrameLogged) {
                firstFrameLogged = true
                debugSession.record("Swing first video frame captured")
            }
            EventQueue.invokeLater {
                if (!disposed.get()) videoSurface.repaint()
            }
        } catch (error: Throwable) {
            frameCaptureTimer?.stop()
            debugSession.record(
                "Swing frame capture failed type=${error.javaClass.simpleName} " +
                    "message=${error.message ?: "unknown"}",
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
        EventQueue.invokeLater {
            if (disposed.get()) return@invokeLater
            awtTitleLabel.text = chrome.title
            awtSubtitleLabel.text = chrome.subtitle
            awtEpisodeLabel.text = chrome.position
            awtNextButton.isEnabled = chrome.hasNext
            awtFullscreenButton.text = if (fullscreen) "🗗" else "⛶"
            awtSourceUpdate = true
            try {
                awtSourceIds = playableSources.map(EmbeddedPlayerSource::episodeId)
                awtSourceSelector.removeAllItems()
                playableSources.forEach { source ->
                    awtSourceSelector.addItem(source.label)
                }
                awtSourceSelector.selectedIndex = if (playableSources.isEmpty()) {
                    -1
                } else {
                    playableSources.indexOfFirst {
                        it.episodeId == selectedSource?.episodeId
                    }.takeIf { it >= 0 } ?: 0
                }
                awtSourceSelector.isVisible = playableSources.isNotEmpty()
            } finally {
                awtSourceUpdate = false
            }
        }
    }

    private fun updateQualityControls() {
        val current = activeQuality
        qualityLabel?.text = current?.let { "HLS · $it" } ?: "HLS"
        EventQueue.invokeLater {
            if (disposed.get()) return@invokeLater
            awtQualityUpdate = true
            try {
                awtQualitySelector.removeAllItems()
                val options = availableQualities.ifEmpty { listOfNotNull(current) }
                options.forEach(awtQualitySelector::addItem)
                awtQualitySelector.selectedItem = current
                awtQualitySelector.isEnabled = options.size > 1
                awtQualitySelector.isVisible = options.isNotEmpty()
            } finally {
                awtQualityUpdate = false
            }
        }
    }

    private fun selectQuality(quality: String) {
        if (quality == activeQuality || quality !in availableQualities) return
        val resumeSeconds = mediaPlayer?.currentTime?.safeSeconds()
        val volume = mediaPlayer?.volume
        selectedQualityOverride = quality
        debugSession.record(
            "quality switch from=${activeQuality ?: "unknown"} to=$quality " +
                "resume=${resumeSeconds ?: 0.0}s",
        )
        startResolution(
            keepCurrentFrame = true,
            resumeSeconds = resumeSeconds,
            volume = volume,
        )
    }

    private fun updatePlaybackPosition() {
        val player = mediaPlayer ?: return
        val position = player.currentTime.safeSeconds()
        val duration = player.totalDuration.safeSeconds()
        if (!seeking && duration > 0.0) {
            seekSlider?.value = (position / duration * SEEK_RANGE).coerceIn(0.0, SEEK_RANGE)
        }
        elapsedLabel?.text = "${position.clockText()} / ${duration.clockText()}"
        EventQueue.invokeLater {
            if (!awtSeeking && duration > 0.0) {
                awtSeekSlider.value =
                    (position / duration * SEEK_RANGE).toInt().coerceIn(0, SEEK_RANGE.toInt())
            }
            awtElapsedLabel.text = "${position.clockText()} / ${duration.clockText()}"
        }
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
        EventQueue.invokeLater {
            videoSurface.status = message
            videoSurface.repaint()
        }
        showControls(permanent = true)
    }

    private fun hideStatus() {
        statusLabel?.isVisible = false
        statusLabel?.isManaged = false
        statusSpinner?.isVisible = false
        statusSpinner?.isManaged = false
        EventQueue.invokeLater {
            videoSurface.status = null
            videoSurface.repaint()
        }
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

    private fun fail(
        currentGeneration: Long,
        message: String,
        error: Throwable? = null,
    ) {
        if (disposed.get() || generation.get() != currentGeneration) return
        debugSession.record(
            "FAILED type=${error?.javaClass?.simpleName ?: "none"} message=$message",
        )
        Platform.runLater {
            if (disposed.get() || generation.get() != currentGeneration) return@runLater
            mediaPlayer?.stop()
            mediaPlayer?.dispose()
            mediaPlayer = null
            mediaView?.mediaPlayer = null
            frameCaptureTimer?.stop()
            frameCaptureTimer = null
            statusSpinner?.isVisible = false
            statusSpinner?.isManaged = false
            statusLabel?.text = message
            statusLabel?.isVisible = true
            statusLabel?.isManaged = true
            EventQueue.invokeLater {
                videoSurface.status = message
                videoSurface.repaint()
            }
            notifyState(
                EmbeddedPlayerState.Failed(
                    message = message,
                    debugInfo = debugSession.report(),
                ),
            )
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
                    quality = activeQuality,
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

    private fun requestSurfaceRepaint() {
        EventQueue.invokeLater {
            if (disposed.get()) return@invokeLater
            fxPanel.revalidate()
            fxPanel.repaint()
            videoSurface.revalidate()
            videoSurface.repaint()
            revalidate()
            repaint()
        }
    }
}

private class ChromeOverlayPanel : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(graphics: Graphics) {
        val graphics2D = graphics.create() as Graphics2D
        try {
            enableHighQualityRendering(graphics2D)
            graphics2D.paint = GradientPaint(
                0f,
                0f,
                AwtColor(0, 0, 0, 178),
                0f,
                minOf(150, height / 4).toFloat(),
                AwtColor(0, 0, 0, 0),
            )
            graphics2D.fillRect(0, 0, width, minOf(180, height / 3))
            val lowerStart = (height - minOf(210, height / 3)).coerceAtLeast(0)
            graphics2D.paint = GradientPaint(
                0f,
                lowerStart.toFloat(),
                AwtColor(0, 0, 0, 0),
                0f,
                height.toFloat(),
                AwtColor(0, 0, 0, 215),
            )
            graphics2D.fillRect(0, lowerStart, width, height - lowerStart)
        } finally {
            graphics2D.dispose()
        }
        super.paintComponent(graphics)
    }
}

private class PillControlPanel : JPanel(BorderLayout()) {
    init {
        isOpaque = false
    }

    override fun paintComponent(graphics: Graphics) {
        val graphics2D = graphics.create() as Graphics2D
        try {
            enableHighQualityRendering(graphics2D)
            val arc = (height - 4).coerceAtLeast(12)
            graphics2D.color = AwtColor(10, 11, 15, 196)
            graphics2D.fillRoundRect(1, 1, width - 3, height - 3, arc, arc)
            graphics2D.color = AwtColor(255, 255, 255, 28)
            graphics2D.drawRoundRect(1, 1, width - 3, height - 3, arc, arc)
        } finally {
            graphics2D.dispose()
        }
        super.paintComponent(graphics)
    }
}

private class ThreeZoneControls(
    private val leftControls: JComponent,
    private val centerControls: JComponent,
    private val rightControls: JComponent,
) : JPanel(null) {
    init {
        isOpaque = false
        add(leftControls)
        add(centerControls)
        add(rightControls)
    }

    override fun getPreferredSize(): Dimension = Dimension(
        leftControls.preferredSize.width + centerControls.preferredSize.width +
            rightControls.preferredSize.width,
        maxOf(
            leftControls.preferredSize.height,
            centerControls.preferredSize.height,
            rightControls.preferredSize.height,
        ),
    )

    override fun doLayout() {
        val left = leftControls.preferredSize
        val center = centerControls.preferredSize
        val right = rightControls.preferredSize
        leftControls.setBounds(0, (height - left.height) / 2, left.width, left.height)
        centerControls.setBounds(
            ((width - center.width) / 2).coerceAtLeast(left.width + 8),
            (height - center.height) / 2,
            center.width,
            center.height,
        )
        rightControls.setBounds(
            (width - right.width).coerceAtLeast(left.width + center.width + 16),
            (height - right.height) / 2,
            right.width,
            right.height,
        )
    }
}

private class AwtVideoSurface : JPanel() {
    @Volatile
    var frame: BufferedImage? = null

    @Volatile
    var status: String? = "Подготавливаем HLS-поток…"

    init {
        background = AwtColor.BLACK
        isOpaque = true
        minimumSize = Dimension(1, 1)
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val graphics2D = graphics.create() as Graphics2D
        try {
            enableHighQualityRendering(graphics2D)
            graphics2D.color = AwtColor.BLACK
            graphics2D.fillRect(0, 0, width, height)
            frame?.let { image ->
                val scale = minOf(
                    width.toDouble() / image.width.coerceAtLeast(1),
                    height.toDouble() / image.height.coerceAtLeast(1),
                )
                val drawWidth = (image.width * scale).toInt().coerceAtLeast(1)
                val drawHeight = (image.height * scale).toInt().coerceAtLeast(1)
                val drawX = (width - drawWidth) / 2
                val drawY = (height - drawHeight) / 2
                graphics2D.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                graphics2D.drawImage(
                    image,
                    drawX,
                    drawY,
                    drawWidth,
                    drawHeight,
                    null,
                )
            }
            if (frame == null) {
                status?.let { message ->
                    graphics2D.color = AWT_MUTED
                    graphics2D.font = hoshiraFont(Font.BOLD, 15f)
                    val metrics = graphics2D.fontMetrics
                    val textX = ((width - metrics.stringWidth(message)) / 2).coerceAtLeast(16)
                    val textY = (height + metrics.ascent) / 2
                    graphics2D.drawString(message, textX, textY)
                }
            }
        } finally {
            graphics2D.dispose()
        }
    }
}

private class HoshiraPlayerButton(
    text: String,
    private val accent: Boolean = false,
    private val pill: Boolean = false,
) : JButton(text) {
    init {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isRolloverEnabled = true
        margin = java.awt.Insets(0, 0, 0, 0)
    }

    override fun paintComponent(graphics: Graphics) {
        val graphics2D = graphics.create() as Graphics2D
        try {
            enableHighQualityRendering(graphics2D)
            val fill = when {
                !isEnabled -> AWT_CONTROL_DISABLED
                model.isPressed -> if (accent) AWT_ACCENT_PRESSED else AWT_CONTROL_PRESSED
                model.isRollover -> if (accent) AWT_ACCENT_HOVER else AWT_CONTROL_HOVER
                accent -> AWT_ACCENT
                else -> AWT_CONTROL
            }
            val arc = if (pill) height.coerceAtLeast(14) else 14
            graphics2D.color = AwtColor(0, 0, 0, if (accent) 82 else 48)
            graphics2D.fillRoundRect(
                2,
                4,
                (width - 4).coerceAtLeast(1),
                (height - 6).coerceAtLeast(1),
                arc,
                arc,
            )
            graphics2D.paint = if (accent) {
                GradientPaint(
                    0f,
                    1f,
                    fill.brighter(),
                    0f,
                    height.toFloat(),
                    fill,
                )
            } else {
                GradientPaint(
                    0f,
                    1f,
                    AwtColor(fill.red + 8, fill.green + 8, fill.blue + 9, fill.alpha),
                    0f,
                    height.toFloat(),
                    fill,
                )
            }
            graphics2D.fill(
                RoundRectangle2D.Double(
                    1.0,
                    1.0,
                    (width - 2).coerceAtLeast(1).toDouble(),
                    (height - 4).coerceAtLeast(1).toDouble(),
                    arc.toDouble(),
                    arc.toDouble(),
                ),
            )
            if (!accent) {
                graphics2D.color = if (model.isRollover) AWT_BORDER_HOVER else AWT_BORDER
                graphics2D.draw(
                    RoundRectangle2D.Double(
                        1.5,
                        1.5,
                        (width - 3).coerceAtLeast(1).toDouble(),
                        (height - 5).coerceAtLeast(1).toDouble(),
                        arc.toDouble(),
                        arc.toDouble(),
                    ),
                )
            }
        } finally {
            graphics2D.dispose()
        }
        super.paintComponent(graphics)
    }
}

private class HoshiraComboBoxUi : BasicComboBoxUI() {
    override fun paintCurrentValueBackground(
        graphics: Graphics,
        bounds: java.awt.Rectangle,
        hasFocus: Boolean,
    ) = Unit

    override fun createArrowButton(): JButton = JButton("⌄").apply {
        preferredSize = Dimension(28, 28)
        foreground = AWT_MUTED
        font = hoshiraFont(Font.BOLD, 12f)
        isFocusable = false
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        margin = java.awt.Insets(0, 0, 0, 0)
    }
}

private class HoshiraComboRenderer(
    private val accent: Boolean = false,
    private val prefix: String? = null,
) : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): java.awt.Component {
        val label = super.getListCellRendererComponent(
            list,
            value,
            index,
            isSelected,
            cellHasFocus,
        ) as JLabel
        label.background = if (isSelected) AWT_CONTROL_HOVER else AWT_CONTROL_HIGH
        label.foreground = if (accent && index < 0) AWT_ACCENT else AWT_TEXT
        label.font = hoshiraFont(Font.BOLD, 12f)
        label.border = BorderFactory.createEmptyBorder(7, 10, 7, 10)
        label.isOpaque = index >= 0
        if (index < 0 && !prefix.isNullOrBlank()) {
            label.text = "$prefix   ${value?.toString().orEmpty()}"
        }
        return label
    }
}

private class HoshiraSliderUi(slider: JSlider) : BasicSliderUI(slider) {
    override fun calculateThumbSize() {
        thumbRect.setSize(14, 14)
    }

    override fun paintTrack(graphics: Graphics) {
        val graphics2D = graphics.create() as Graphics2D
        try {
            enableHighQualityRendering(graphics2D)
            val y = trackRect.y + (trackRect.height - 4) / 2
            graphics2D.color = AWT_SLIDER_TRACK
            graphics2D.fillRoundRect(trackRect.x, y, trackRect.width, 4, 4, 4)
            val range = (slider.maximum - slider.minimum).coerceAtLeast(1)
            val fraction = (slider.value - slider.minimum).toDouble() / range
            val fillWidth = (trackRect.width * fraction).toInt().coerceIn(0, trackRect.width)
            graphics2D.color = AWT_ACCENT
            graphics2D.fillRoundRect(trackRect.x, y, fillWidth, 4, 4, 4)
        } finally {
            graphics2D.dispose()
        }
    }

    override fun paintThumb(graphics: Graphics) {
        val graphics2D = graphics.create() as Graphics2D
        try {
            enableHighQualityRendering(graphics2D)
            graphics2D.color = AwtColor(0, 0, 0, 90)
            graphics2D.fillOval(
                thumbRect.x - 2,
                thumbRect.y + 2,
                thumbRect.width + 4,
                thumbRect.height + 4,
            )
            graphics2D.color = AWT_ACCENT
            graphics2D.fillOval(thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height)
            graphics2D.color = AWT_TEXT
            graphics2D.drawOval(thumbRect.x, thumbRect.y, thumbRect.width - 1, thumbRect.height - 1)
        } finally {
            graphics2D.dispose()
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
        "%d:%02d".format(minutes, seconds)
    }
}

private fun enableHighQualityRendering(graphics: Graphics2D) {
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
}

private val HOSHIRA_FONT: Font by lazy {
    runCatching {
        NativeDesktopPlayerPanel::class.java.getResourceAsStream(HOSHIRA_FONT_RESOURCE)
            ?.use { Font.createFont(Font.TRUETYPE_FONT, it) }
            ?: error("Bundled Hoshira font not found")
    }.getOrElse { Font(Font.SANS_SERIF, Font.PLAIN, 12) }
}

private fun hoshiraFont(style: Int, size: Float): Font = HOSHIRA_FONT.deriveFont(style, size)

private const val SEEK_RANGE = 1_000.0
private const val CAPTURE_WIDTH = 1_280
private const val CAPTURE_HEIGHT = 720
private const val FRAME_CAPTURE_INTERVAL_MS = 40.0
private const val PLAYBACK_NOTIFICATION_NANOS = 750_000_000L
private const val SLIDER_POINTER_PADDING = 7
private const val HOSHIRA_FONT_RESOURCE =
    "/composeResources/dev.aniliberty.desktop.desktopapp.generated.resources/font/montserrat_variable.ttf"
private val AWT_TEXT = AwtColor(0xF7, 0xF7, 0xFA)
private val AWT_MUTED = AwtColor(0xB9, 0xBA, 0xC5)
private val AWT_ACCENT = AwtColor(0xFF, 0x6A, 0x00)
private val AWT_ACCENT_HOVER = AwtColor(0xFF, 0x7D, 0x21)
private val AWT_ACCENT_PRESSED = AwtColor(0xE8, 0x57, 0x00)
private val AWT_CONTROL = AwtColor(27, 29, 34, 189)
private val AWT_CONTROL_HIGH = AwtColor(18, 19, 23, 240)
private val AWT_CONTROL_HOVER = AwtColor(49, 51, 58, 230)
private val AWT_CONTROL_PRESSED = AwtColor(0x18, 0x19, 0x1E, 235)
private val AWT_CONTROL_DISABLED = AwtColor(0x19, 0x1A, 0x1F, 150)
private val AWT_BORDER = AwtColor(255, 255, 255, 35)
private val AWT_BORDER_HOVER = AwtColor(0x5B, 0x5D, 0x69)
private val AWT_SLIDER_TRACK = AwtColor(255, 255, 255, 55)
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
