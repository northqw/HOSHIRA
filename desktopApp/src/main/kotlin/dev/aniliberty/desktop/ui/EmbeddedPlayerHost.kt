package dev.aniliberty.desktop.ui

import java.nio.charset.StandardCharsets
import java.util.Base64

internal sealed interface EmbeddedPlayerState {
    data object Starting : EmbeddedPlayerState
    data object Ready : EmbeddedPlayerState
    data class Failed(
        val message: String,
        val debugInfo: String? = null,
    ) : EmbeddedPlayerState
}

internal data class EmbeddedPlayerChrome(
    val title: String,
    val subtitle: String,
    val position: String,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val sources: List<EmbeddedPlayerSource>,
    val resumeSeconds: Double = 0.0,
    val startupVolume: Float = 1f,
    val preferredQuality: String? = null,
    val autoplayNext: Boolean = true,
    val controlsHideDelayMs: Int = 4_800,
    val preferredVoice: String? = null,
    val fallbackPlayerPageUrls: List<String> = emptyList(),
)

internal data class EmbeddedPlayerSource(
    val episodeId: String,
    val label: String,
    val selected: Boolean,
    val playerPageUrl: String? = null,
)

internal sealed interface EmbeddedPlayerAction {
    data object Back : EmbeddedPlayerAction
    data object Previous : EmbeddedPlayerAction
    data object Next : EmbeddedPlayerAction
    data class SetFullscreen(val fullscreen: Boolean) : EmbeddedPlayerAction
    data class SelectSource(val episodeId: String) : EmbeddedPlayerAction
    data class Playback(
        val positionSeconds: Double,
        val durationSeconds: Double,
        val volume: Float,
        val quality: String?,
    ) : EmbeddedPlayerAction
}

/*
 * Historical SWT implementation. Kept in this source block temporarily for
 * reference while the direct WebView2 host settles; it is not compiled or
 * packaged, and the desktop distribution no longer depends on SWT.
 *
/**
 * Hosts SWT's Edge browser inside Compose's AWT hierarchy.
 *
 * SWT widgets and their event loop live on a dedicated thread. No SWT call is
 * made synchronously from AWT, so closing the player cannot block Compose's UI.
 */
internal class WebView2PlayerPanel(
    initialUrl: String,
    initialChrome: EmbeddedPlayerChrome,
    onStateChange: (EmbeddedPlayerState) -> Unit,
    onAction: (EmbeddedPlayerAction) -> Unit,
) : Panel(BorderLayout()) {
    private val browserCanvas = Canvas().apply {
        background = Color.BLACK
    }
    private val started = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)

    @Volatile
    private var hostWidth = 1

    @Volatile
    private var hostHeight = 1

    @Volatile
    private var requestedUrl = initialUrl

    @Volatile
    private var requestedChrome = initialChrome

    @Volatile
    private var stateCallback = onStateChange

    @Volatile
    private var actionCallback = onAction

    @Volatile
    private var display: Display? = null

    @Volatile
    private var shell: Shell? = null

    @Volatile
    private var browser: Browser? = null

    @Volatile
    private var pointerTracker: Timer? = null

    private var activeUrl: String? = null
    private var allohaHostInstalled = false

    init {
        background = Color.BLACK
        add(browserCanvas, BorderLayout.CENTER)
        val resizeListener = object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                rememberHostSize()
                resizeEmbeddedShell()
            }
        }
        addComponentListener(resizeListener)
        browserCanvas.addComponentListener(resizeListener)
    }

    override fun doLayout() {
        super.doLayout()
        browserCanvas.setBounds(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
        rememberHostSize()
        resizeEmbeddedShell()
    }

    override fun addNotify() {
        super.addNotify()
        doLayout()
        EventQueue.invokeLater {
            if (isShowing && !disposed.get()) {
                doLayout()
                startBrowser()
            }
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
        requestedChrome = chrome
        if (requestedUrl == url) return

        requestedUrl = url
        notifyState(EmbeddedPlayerState.Starting)
        display?.postSafely {
            browser?.takeUnless(Browser::isDisposed)?.let { currentBrowser ->
                load(currentBrowser, url)
            }
        }
    }

    fun disposePlayer() {
        if (!disposed.compareAndSet(false, true)) return
        stateCallback = {}
        actionCallback = {}
        stopPointerTracking()
        display?.postSafely {
            shell?.takeUnless(Shell::isDisposed)?.dispose()
        }
    }

    private fun startBrowser() {
        if (!started.compareAndSet(false, true)) return

        thread(
            name = "hoshira-webview2",
            isDaemon = true,
        ) {
            runBrowserLoop()
        }
    }

    private fun runBrowserLoop() {
        try {
            configureEdgeRuntime()
            val swtDisplay = Display()
            display = swtDisplay
            if (disposed.get()) {
                swtDisplay.dispose()
                return
            }

            val swtShell = SWT_AWT.new_Shell(swtDisplay, browserCanvas).apply {
                layout = FillLayout()
                setBounds(0, 0, hostWidth, hostHeight)
            }
            shell = swtShell

            val swtBrowser = Browser(swtShell, SWT.EDGE).apply {
                setJavascriptEnabled(true)
                setText(PLAYER_LOADING_HTML)
            }
            browser = swtBrowser
            startPointerTracking()
            val actionBridge = object : BrowserFunction(swtBrowser, "hoshiraNative") {
                override fun function(arguments: Array<out Any?>): Any? {
                    when (arguments.firstOrNull()?.toString()) {
                        "back" -> notifyAction(EmbeddedPlayerAction.Back)
                        "previous" -> notifyAction(EmbeddedPlayerAction.Previous)
                        "next" -> notifyAction(EmbeddedPlayerAction.Next)
                        else -> arguments
                            .firstOrNull()
                            ?.toString()
                            ?.removePrefix("source:")
                            ?.takeIf { sourceId ->
                                arguments.firstOrNull()?.toString()?.startsWith("source:") == true &&
                                    sourceId.isNotBlank()
                            }
                            ?.let { sourceId ->
                                notifyAction(EmbeddedPlayerAction.SelectSource(sourceId))
                            }
                    }
                    return null
                }
            }
            swtBrowser.addOpenWindowListener { event ->
                event.required = true
                event.browser = null
            }
            swtBrowser.addLocationListener(
                LocationListener.changingAdapter { event ->
                    if (event.top && allohaHostInstalled) {
                        event.doit = false
                    }
                },
            )

            swtBrowser.addProgressListener(object : ProgressAdapter() {
                override fun completed(event: ProgressEvent) {
                    val url = activeUrl ?: return
                    if (allohaHostInstalled) return
                    allohaHostInstalled = true
                    val installation = runCatching {
                        swtBrowser.evaluate(
                            playerHostScript(url, requestedChrome),
                        ) == true &&
                            swtBrowser.evaluate(
                                "return document.querySelector('.chrome') !== null;",
                            ) == true
                    }
                    val installed = installation.getOrDefault(false)
                    if (installed) {
                        notifyState(EmbeddedPlayerState.Ready)
                    } else {
                        notifyState(
                            EmbeddedPlayerState.Failed(
                                "WebView2 не смог встроить страницу плеера.",
                            ),
                        )
                    }
                }
            })

            swtShell.open()
            applyEmbeddedBounds(swtShell, swtBrowser)
            startBoundsSynchronizer(swtDisplay, swtShell, swtBrowser)
            swtDisplay.asyncExec {
                if (!disposed.get() && !swtBrowser.isDisposed) {
                    load(swtBrowser, requestedUrl)
                }
            }

            while (!disposed.get() && !swtShell.isDisposed) {
                if (!swtDisplay.readAndDispatch()) swtDisplay.sleep()
            }

            if (!actionBridge.isDisposed) actionBridge.dispose()
            if (!swtShell.isDisposed) swtShell.dispose()
            if (!swtDisplay.isDisposed) swtDisplay.dispose()
        } catch (error: Throwable) {
            notifyState(
                EmbeddedPlayerState.Failed(error.toPlayerMessage()),
            )
        } finally {
            stopPointerTracking()
            runCatching {
                shell?.takeUnless(Shell::isDisposed)?.dispose()
            }
            runCatching {
                display?.takeUnless(Display::isDisposed)?.dispose()
            }
            browser = null
            shell = null
            display = null
        }
    }

    private fun resizeEmbeddedShell() {
        display?.postSafely {
            shell?.takeUnless(Shell::isDisposed)?.let { currentShell ->
                browser?.takeUnless(Browser::isDisposed)?.let { currentBrowser ->
                    applyEmbeddedBounds(currentShell, currentBrowser)
                }
            }
        }
    }

    private fun rememberHostSize() {
        hostWidth = width.coerceAtLeast(browserCanvas.width).coerceAtLeast(1)
        hostHeight = height.coerceAtLeast(browserCanvas.height).coerceAtLeast(1)
    }

    private fun applyEmbeddedBounds(
        currentShell: Shell,
        currentBrowser: Browser,
    ) {
        currentShell.setBounds(0, 0, hostWidth, hostHeight)
        currentShell.layout(true, true)
        currentBrowser.setBounds(currentShell.clientArea)
    }

    private fun startBoundsSynchronizer(
        swtDisplay: Display,
        swtShell: Shell,
        swtBrowser: Browser,
    ) {
        val synchronizer = object : Runnable {
            override fun run() {
                if (
                    disposed.get() ||
                    swtDisplay.isDisposed ||
                    swtShell.isDisposed ||
                    swtBrowser.isDisposed
                ) {
                    return
                }
                applyEmbeddedBounds(swtShell, swtBrowser)
                swtDisplay.timerExec(250, this)
            }
        }
        swtDisplay.timerExec(0, synchronizer)
    }

    private fun notifyState(state: EmbeddedPlayerState) {
        EventQueue.invokeLater {
            if (!disposed.get()) stateCallback(state)
        }
    }

    private fun notifyAction(action: EmbeddedPlayerAction) {
        EventQueue.invokeLater {
            if (!disposed.get()) actionCallback(action)
        }
    }

    private fun startPointerTracking() {
        EventQueue.invokeLater {
            if (disposed.get() || pointerTracker != null) return@invokeLater

            var lastPosition: Point? = null
            var wasInside = false
            pointerTracker = Timer(120) {
                if (disposed.get()) {
                    stopPointerTracking()
                    return@Timer
                }

                val pointer = MouseInfo.getPointerInfo()?.location ?: return@Timer
                val origin = runCatching { browserCanvas.locationOnScreen }.getOrNull()
                    ?: return@Timer
                val inside =
                    pointer.x >= origin.x &&
                        pointer.y >= origin.y &&
                        pointer.x < origin.x + browserCanvas.width &&
                        pointer.y < origin.y + browserCanvas.height
                val moved = lastPosition?.let { previous ->
                    previous.x != pointer.x || previous.y != pointer.y
                } ?: true

                if (inside && (moved || !wasInside)) {
                    display?.postSafely {
                        browser?.takeUnless(Browser::isDisposed)?.execute(
                            "window.hoshiraActivity && window.hoshiraActivity();",
                        )
                    }
                }
                lastPosition = Point(pointer)
                wasInside = inside
            }.apply {
                isRepeats = true
                start()
            }
        }
    }

    private fun stopPointerTracking() {
        EventQueue.invokeLater {
            pointerTracker?.stop()
            pointerTracker = null
        }
    }

    private fun navigate(browser: Browser, url: String) {
        val validUrl = runCatching { URI(url) }
            .getOrNull()
            ?.takeIf { it.scheme == "https" || it.scheme == "http" }
            ?.toASCIIString()

        if (validUrl == null) {
            notifyState(EmbeddedPlayerState.Failed("Источник вернул некорректную ссылку на плеер."))
            return
        }

        val opened = if (isAllohaUrl(validUrl)) {
            browser.setUrl(
                validUrl,
                null,
                arrayOf("Referer: $ALLOHA_ORIGIN"),
            )
        } else {
            browser.setUrl(validUrl)
        }
        if (!opened) {
            notifyState(EmbeddedPlayerState.Failed("WebView2 не смог открыть страницу плеера."))
        }
    }

    private fun load(browser: Browser, url: String) {
        activeUrl = url
        allohaHostInstalled = false
        navigate(browser, url)
    }
}

private const val ALLOHA_ORIGIN = "https://alloha.yani.tv/"

private val PLAYER_LOADING_HTML = """
    <!doctype html>
    <html>
      <head>
        <meta charset="utf-8">
        <style>
          html, body {
            width: 100%;
            height: 100%;
            margin: 0;
            overflow: hidden;
            background: #000;
          }
        </style>
      </head>
      <body></body>
    </html>
""".trimIndent()

private fun configureEdgeRuntime() {
    EdgeRuntimeSettings.configure()
}

private object EdgeRuntimeSettings {
    private val configured = AtomicBoolean(false)

    fun configure() {
        if (!configured.compareAndSet(false, true)) return

        Display.setAppName("Hoshira")
        val localData = System.getenv("LOCALAPPDATA")
            ?.takeIf(String::isNotBlank)
            ?: System.getProperty("user.home")
        System.setProperty(
            "org.eclipse.swt.browser.EdgeDataDir",
            File(localData, "Hoshira/browser/webview2").absolutePath,
        )
    }
}

private fun isAllohaUrl(url: String): Boolean =
    runCatching { URI(url).host.equals("alloha.yani.tv", ignoreCase = true) }
        .getOrDefault(false)
*/

internal fun playerHostScript(
    playerUrl: String,
    chrome: EmbeddedPlayerChrome,
): String {
    val encodedUrl = Base64.getEncoder().encodeToString(
        playerUrl.toByteArray(StandardCharsets.UTF_8),
    )
    val encodedQuality = Base64.getEncoder().encodeToString(
        chrome.preferredQuality.orEmpty().toByteArray(StandardCharsets.UTF_8),
    )
    val previousButton = if (chrome.hasPrevious) {
        """
            <button class="action episode-action" data-action="previous">
              <span class="action-icon">‹</span>
              <span>Предыдущая серия</span>
            </button>
        """.trimIndent()
    } else {
        "<span></span>"
    }
    val nextButton = if (chrome.hasNext) {
        """
            <button class="action episode-action primary" data-action="next">
              <span>Следующая серия</span>
              <span class="action-icon">›</span>
            </button>
        """.trimIndent()
    } else {
        "<span></span>"
    }
    val selectedSource = chrome.sources.firstOrNull {
        it.selected && !isDeferredPlayerSource(it.label)
    } ?: chrome.sources.firstOrNull { !isDeferredPlayerSource(it.label) }
    val sourceSelector = selectedSource?.let { selected ->
        if (chrome.sources.size > 1) {
            val sourceOptions = chrome.sources.joinToString("\n") { source ->
                val deferred = isDeferredPlayerSource(source.label)
                """
                    <button
                      class="source-option${if (source.selected && !deferred) " selected" else ""}${if (deferred) " unavailable" else ""}"
                      data-action="source:${source.episodeId.escapeHtml()}"
                      ${if (deferred) "disabled aria-disabled=\"true\"" else ""}
                    >
                      <span>
                        ${source.label.escapeHtml()}
                        ${if (deferred) "<small>$DEFERRED_PLAYER_NOTE</small>" else ""}
                      </span>
                      ${if (source.selected && !deferred) "<span class=\"selected-mark\">✓</span>" else ""}
                    </button>
                """.trimIndent()
            }
            """
                <div class="source-picker">
                  <button class="action source-toggle" id="source-toggle">
                    <span class="source-caption">Источник</span>
                    <span>${selected.label.escapeHtml()}</span>
                    <span class="source-chevron" aria-hidden="true">
                      <svg viewBox="0 0 18 18">
                        <path d="M4 6.5 9 11l5-4.5"></path>
                      </svg>
                    </span>
                  </button>
                  <div class="source-menu" id="source-menu">
                    <div class="source-menu-title">Выберите плеер</div>
                    $sourceOptions
                  </div>
                </div>
            """.trimIndent()
        } else {
            """
                <div class="source-single">
                  <span class="source-caption">Источник</span>
                  <span>${selected.label.escapeHtml()}</span>
                </div>
            """.trimIndent()
        }
    }.orEmpty()
    val markup = """
        <iframe
          id="hoshira-player"
          allow="autoplay; fullscreen; encrypted-media; picture-in-picture"
          allowfullscreen
          referrerpolicy="origin"
        ></iframe>
        <div class="player-loading" id="player-loading">
          <button class="loading-back" data-action="back" type="button">
            <span>←</span>
            <span>Назад</span>
          </button>
          <div class="loading-content">
            <div class="loading-spinner"></div>
            <div class="loading-label" id="loading-label">Загружаем плеер…</div>
            <div class="loading-note" id="loading-note"></div>
          </div>
        </div>
        <div class="chrome">
          <div class="top-shade"></div>
          <div class="top-bar">
            <div class="top-left">
              <button class="action back-action" data-action="back">
                <span class="action-icon">←</span>
                <span>Назад</span>
              </button>
              <div class="episode-meta">
                <div class="episode-title">${chrome.title.escapeHtml()}</div>
                <div class="episode-subtitle">${chrome.subtitle.escapeHtml()}</div>
              </div>
            </div>
            <div class="top-right">
              $sourceSelector
            </div>
          </div>

          <div class="center-controls">
            <button
              class="player-button center-play is-disabled"
              id="center-play"
              type="button"
              aria-label="Воспроизвести"
              disabled
            >
              <span class="play-icon">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M8 5.5v13l10-6.5z"></path>
                </svg>
              </span>
              <span class="pause-icon">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M7 5h4v14H7zm6 0h4v14h-4z"></path>
                </svg>
              </span>
            </button>
          </div>
          <div id="next-countdown" style="display:none;position:absolute;right:32px;bottom:116px;z-index:40;
            width:300px;padding:20px;border-radius:16px;background:rgba(10,10,12,.94);
            border:1px solid rgba(255,255,255,.14);box-shadow:0 16px 50px rgba(0,0,0,.45)">
            <div style="font-size:16px;font-weight:700">Следующая серия через <span id="next-seconds">8</span> сек.</div>
            <div style="display:flex;gap:10px;margin-top:14px">
              <button class="action episode-action primary" id="next-now" type="button">Смотреть сейчас</button>
              <button class="action episode-action" id="next-cancel" type="button">Отмена</button>
            </div>
          </div>

          <div class="bottom-shade"></div>
          <div class="player-controls" id="player-controls">
            <div class="player-status" id="player-status">Подключение к видео…</div>
            <div class="timeline">
              <input
                class="range seek-range"
                id="seek-range"
                type="range"
                min="0"
                max="1000"
                value="0"
                step="1"
                aria-label="Позиция воспроизведения"
                disabled
              >
            </div>
            <div class="control-row">
              <div class="control-cluster playback-cluster">
                <button
                  class="player-button is-disabled"
                  id="play-toggle"
                  type="button"
                  aria-label="Воспроизвести"
                  disabled
                >
                  <span class="play-icon">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M8 5.5v13l10-6.5z"></path>
                    </svg>
                  </span>
                  <span class="pause-icon">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M7 5h4v14H7zm6 0h4v14h-4z"></path>
                    </svg>
                  </span>
                </button>
                <button
                  class="player-button is-disabled"
                  id="rewind"
                  type="button"
                  aria-label="Назад на 10 секунд"
                  disabled
                >
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M11 6V3L6.5 7.5 11 12V9c3.3 0 6 2.7 6 6 0 1.1-.3 2.1-.8 3l2.2 1.3A8.5 8.5 0 0 0 11 6z"></path>
                    <text x="10.8" y="18.5" text-anchor="middle">10</text>
                  </svg>
                </button>
                <button
                  class="player-button is-disabled"
                  id="forward"
                  type="button"
                  aria-label="Вперёд на 10 секунд"
                  disabled
                >
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M13 6V3l4.5 4.5L13 12V9c-3.3 0-6 2.7-6 6 0 1.1.3 2.1.8 3l-2.2 1.3A8.5 8.5 0 0 1 13 6z"></path>
                    <text x="13.2" y="18.5" text-anchor="middle">10</text>
                  </svg>
                </button>
                <div class="time-label">
                  <span id="current-time">0:00</span>
                  <span class="time-divider">/</span>
                  <span id="duration">0:00</span>
                </div>
              </div>

              <div class="episode-navigation">
                <div class="bottom-slot left">$previousButton</div>
                <div class="episode-position">${chrome.position.escapeHtml()}</div>
                <div class="bottom-slot right">$nextButton</div>
              </div>

              <div class="control-cluster utility-cluster">
                <div class="quality-picker" id="quality-picker">
                  <button
                    class="quality-toggle is-disabled"
                    id="quality-toggle"
                    type="button"
                    aria-label="Выбрать качество видео"
                    disabled
                  >
                    <span id="quality-label">Авто</span>
                    <span class="quality-chevron" aria-hidden="true">
                      <svg viewBox="0 0 18 18">
                        <path d="M4 6.5 9 11l5-4.5"></path>
                      </svg>
                    </span>
                  </button>
                  <div class="quality-menu" id="quality-menu">
                    <div class="quality-menu-title">Качество видео</div>
                    <div class="quality-options" id="quality-options">
                      <div class="quality-empty">Ищем варианты…</div>
                    </div>
                  </div>
                </div>
                <button
                  class="player-button is-disabled"
                  id="mute-toggle"
                  type="button"
                  aria-label="Выключить звук"
                  disabled
                >
                  <span class="volume-icon">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M4 9v6h4l5 4V5L8 9H4zm11.5-.9v7.8a5 5 0 0 0 0-7.8zm0-3.1v2.1a7 7 0 0 1 0 9.8V19a9 9 0 0 0 0-14z"></path>
                    </svg>
                  </span>
                  <span class="muted-icon">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M4 9v6h4l5 4V5L8 9H4zm12.2 3 2.4-2.4-1.2-1.2-2.4 2.4-2.4-2.4-1.2 1.2 2.4 2.4-2.4 2.4 1.2 1.2 2.4-2.4 2.4 2.4 1.2-1.2z"></path>
                    </svg>
                  </span>
                </button>
                <input
                  class="range volume-range"
                  id="volume-range"
                  type="range"
                  min="0"
                  max="1"
                  value="1"
                  step="0.01"
                  aria-label="Громкость"
                  disabled
                >
                <button
                  class="player-button"
                  id="fullscreen-toggle"
                  type="button"
                  aria-label="На весь экран"
                >
                  <span class="fullscreen-enter-icon">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M5 5h5v2H7v3H5V5zm9 0h5v5h-2V7h-3V5zM5 14h2v3h3v2H5v-5zm12 0h2v5h-5v-2h3v-3z"></path>
                    </svg>
                  </span>
                  <span class="fullscreen-exit-icon">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M10 10H5V8h3V5h2v5zm4 0V5h2v3h3v2h-5zm-4 4v5H8v-3H5v-2h5zm4 0h5v2h-3v3h-2v-5z"></path>
                    </svg>
                  </span>
                </button>
              </div>
            </div>
          </div>
        </div>
    """.trimIndent()
    val css = """
        :root {
          color-scheme: dark;
          font-family: "Montserrat", "Segoe UI Variable Display", "Segoe UI", sans-serif;
        }
        html, body {
          width: 100%;
          height: 100%;
          margin: 0;
          overflow: hidden;
          background: #000;
        }
        * {
          box-sizing: border-box;
        }
        #hoshira-player {
          position: absolute;
          inset: 0;
          z-index: 1;
          width: 100%;
          height: 100%;
          margin: 0;
          border: 0;
          background: #000;
        }
        .player-loading {
          position: absolute;
          inset: 0;
          z-index: 5;
          display: grid;
          place-items: center;
          overflow: hidden;
          background: #090a0c;
          color: #fff;
          opacity: 1;
          visibility: visible;
          transition:
            opacity 360ms ease,
            visibility 360ms step-end;
        }
        .player-loading.is-hidden {
          opacity: 0;
          visibility: hidden;
          pointer-events: none;
        }
        .loading-back {
          position: absolute;
          top: 24px;
          left: 28px;
          display: inline-flex;
          min-height: 48px;
          align-items: center;
          gap: 10px;
          padding: 0 20px;
          border: 1px solid rgba(255, 255, 255, 0.13);
          border-radius: 999px;
          outline: 0;
          background: rgba(0, 0, 0, 0.82);
          box-shadow: 0 16px 40px rgba(0, 0, 0, 0.34);
          color: #fff;
          font: inherit;
          font-size: 14px;
          font-weight: 750;
          cursor: pointer;
        }
        .loading-content {
          display: flex;
          align-items: center;
          flex-direction: column;
          max-width: min(460px, calc(100vw - 48px));
        }
        .loading-spinner {
          width: 38px;
          height: 38px;
          margin-bottom: 18px;
          border: 3px solid rgba(255, 77, 0, 0.2);
          border-top-color: #ff4d00;
          border-right-color: #ff4d00;
          border-radius: 50%;
          animation: hoshira-loading-spin 0.9s linear infinite;
        }
        .loading-label {
          color: #e5e7eb;
          font-size: 16px;
          font-weight: 650;
          text-align: center;
        }
        .loading-note {
          display: none;
          margin-top: 10px;
          color: #7f858f;
          font-size: 13px;
          font-weight: 450;
          line-height: 1.5;
          text-align: center;
        }
        .loading-note.is-visible {
          display: block;
        }
        @keyframes hoshira-loading-spin {
          to { transform: rotate(360deg); }
        }
        .chrome {
          position: absolute;
          inset: 0;
          z-index: 2;
          color: #f8f8fa;
          opacity: 1;
          pointer-events: none;
          transition: opacity 280ms ease;
          will-change: opacity;
        }
        .chrome.is-hidden {
          opacity: 0;
        }
        .chrome.is-hidden .action,
        .chrome.is-hidden .player-button,
        .chrome.is-hidden .range,
        .chrome.is-hidden .source-picker,
        .chrome.is-hidden .source-option,
        .chrome.is-hidden .quality-picker,
        .chrome.is-hidden .quality-option {
          pointer-events: none !important;
        }
        .top-shade,
        .bottom-shade {
          position: absolute;
          left: 0;
          right: 0;
          pointer-events: none;
        }
        .top-shade {
          top: 0;
          height: 154px;
          background: linear-gradient(
            to bottom,
            rgba(3, 4, 6, 0.88) 0%,
            rgba(3, 4, 6, 0.54) 48%,
            rgba(3, 4, 6, 0) 100%
          );
        }
        .bottom-shade {
          bottom: 0;
          height: 220px;
          background: linear-gradient(
            to top,
            rgba(3, 4, 6, 0.96) 0%,
            rgba(3, 4, 6, 0.64) 48%,
            rgba(3, 4, 6, 0) 100%
          );
        }
        .top-bar {
          position: absolute;
          top: 0;
          left: 0;
          right: 0;
          display: flex;
          align-items: center;
          justify-content: space-between;
          gap: 24px;
          padding: 20px 32px;
        }
        .top-left {
          display: flex;
          align-items: center;
          min-width: 0;
          gap: 24px;
        }
        .top-right {
          display: flex;
          flex: 0 0 auto;
          align-items: center;
          gap: 12px;
          pointer-events: none;
        }
        .episode-meta {
          min-width: 0;
          text-shadow: 0 2px 18px rgba(0, 0, 0, 0.92);
        }
        .episode-title {
          max-width: min(720px, 52vw);
          overflow: hidden;
          color: #fff;
          font-size: 21px;
          font-weight: 800;
          line-height: 1.2;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
        .episode-subtitle {
          margin-top: 4px;
          overflow: hidden;
          color: rgba(235, 236, 242, 0.74);
          font-size: 14px;
          font-weight: 500;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
        .action {
          display: inline-flex;
          flex: 0 0 auto;
          align-items: center;
          justify-content: center;
          gap: 10px;
          min-height: 50px;
          padding: 0 22px;
          border: 1px solid rgba(255, 255, 255, 0.12);
          border-radius: 999px;
          outline: 0;
          background: rgba(28, 30, 35, 0.72);
          box-shadow:
            0 12px 32px rgba(0, 0, 0, 0.3),
            inset 0 1px 0 rgba(255, 255, 255, 0.06);
          -webkit-backdrop-filter: blur(18px) saturate(140%);
          backdrop-filter: blur(18px) saturate(140%);
          color: #fff;
          font: inherit;
          font-size: 14px;
          font-weight: 750;
          cursor: pointer;
          pointer-events: auto;
          transition:
            transform 150ms ease,
            border-color 150ms ease,
            background 150ms ease;
        }
        .action:hover {
          transform: translateY(-1px);
          border-color: rgba(255, 255, 255, 0.22);
          background: rgba(40, 42, 48, 0.88);
        }
        .action:active {
          transform: translateY(0) scale(0.98);
        }
        .action-icon {
          font-size: 20px;
          font-weight: 600;
          line-height: 1;
        }
        .source-picker {
          position: relative;
          pointer-events: auto;
        }
        .source-toggle {
          min-width: 176px;
        }
        .source-caption {
          color: rgba(235, 236, 242, 0.58);
          font-size: 11px;
          font-weight: 700;
          letter-spacing: 0.05em;
          text-transform: uppercase;
        }
        .source-chevron {
          display: inline-grid;
          width: 18px;
          height: 18px;
          place-items: center;
          margin-left: 2px;
          color: rgba(255, 255, 255, 0.72);
          transition: transform 160ms ease;
        }
        .source-chevron svg,
        .quality-chevron svg {
          display: block;
          width: 18px;
          height: 18px;
          overflow: visible;
          fill: none;
          stroke: currentColor;
          stroke-width: 1.8;
          stroke-linecap: round;
          stroke-linejoin: round;
        }
        .source-picker.open .source-chevron {
          transform: rotate(180deg);
        }
        .source-menu {
          position: absolute;
          top: calc(100% + 10px);
          right: 0;
          display: none;
          width: 250px;
          overflow: hidden;
          padding: 8px;
          border: 1px solid rgba(255, 255, 255, 0.13);
          border-radius: 18px;
          background: rgba(18, 19, 23, 0.94);
          box-shadow: 0 22px 60px rgba(0, 0, 0, 0.52);
          -webkit-backdrop-filter: blur(24px) saturate(150%);
          backdrop-filter: blur(24px) saturate(150%);
          pointer-events: auto;
        }
        .source-picker.open .source-menu {
          display: block;
        }
        .source-menu-title {
          padding: 8px 10px 10px;
          color: rgba(235, 236, 242, 0.48);
          font-size: 11px;
          font-weight: 750;
          letter-spacing: 0.06em;
          text-transform: uppercase;
        }
        .source-option {
          display: flex;
          width: 100%;
          align-items: center;
          justify-content: space-between;
          min-height: 42px;
          padding: 0 12px;
          border: 0;
          border-radius: 12px;
          background: transparent;
          color: #f4f4f6;
          font: inherit;
          font-size: 14px;
          font-weight: 650;
          cursor: pointer;
          pointer-events: auto;
        }
        .source-option:hover {
          background: rgba(255, 255, 255, 0.08);
        }
        .source-option.selected {
          background: rgba(255, 110, 16, 0.13);
          color: #ff9b4a;
        }
        .source-option.unavailable {
          cursor: default;
          opacity: 0.46;
        }
        .source-option small {
          display: block;
          margin-top: 3px;
          color: rgba(235, 236, 242, 0.55);
          font-size: 10px;
          font-weight: 650;
        }
        .selected-mark {
          color: #ff8a24;
          font-size: 15px;
        }
        .source-single {
          display: inline-flex;
          align-items: center;
          gap: 9px;
          min-height: 44px;
          padding: 0 16px;
          border: 1px solid rgba(255, 255, 255, 0.1);
          border-radius: 999px;
          background: rgba(18, 19, 23, 0.58);
          -webkit-backdrop-filter: blur(16px);
          backdrop-filter: blur(16px);
          color: rgba(248, 248, 250, 0.82);
          font-size: 13px;
          font-weight: 700;
          pointer-events: none;
        }
        .quality-picker {
          position: relative;
          flex: 0 0 auto;
          pointer-events: auto;
        }
        .quality-toggle {
          display: inline-flex;
          min-width: 76px;
          height: 46px;
          align-items: center;
          justify-content: center;
          gap: 8px;
          padding: 0 14px;
          border: 1px solid rgba(255, 255, 255, 0.12);
          border-radius: 999px;
          outline: 0;
          background: rgba(27, 29, 34, 0.74);
          box-shadow:
            0 10px 28px rgba(0, 0, 0, 0.28),
            inset 0 1px 0 rgba(255, 255, 255, 0.06);
          -webkit-backdrop-filter: blur(18px) saturate(140%);
          backdrop-filter: blur(18px) saturate(140%);
          color: #fff;
          font: inherit;
          font-size: 13px;
          font-weight: 800;
          cursor: pointer;
        }
        .quality-toggle:disabled,
        .quality-toggle.is-disabled {
          cursor: default;
          opacity: 0.42;
        }
        .quality-chevron {
          display: inline-grid;
          width: 18px;
          height: 18px;
          place-items: center;
          color: rgba(255, 255, 255, 0.66);
          transition: transform 160ms ease;
        }
        .quality-picker.open .quality-chevron {
          transform: rotate(180deg);
        }
        .quality-menu {
          position: absolute;
          right: 0;
          bottom: calc(100% + 12px);
          display: none;
          width: 190px;
          overflow: hidden;
          padding: 8px;
          border: 1px solid rgba(255, 255, 255, 0.13);
          border-radius: 18px;
          background: rgba(18, 19, 23, 0.95);
          box-shadow: 0 22px 60px rgba(0, 0, 0, 0.52);
          -webkit-backdrop-filter: blur(24px) saturate(150%);
          backdrop-filter: blur(24px) saturate(150%);
          pointer-events: auto;
        }
        .quality-picker.open .quality-menu {
          display: block;
        }
        .quality-menu-title {
          padding: 8px 10px 10px;
          color: rgba(235, 236, 242, 0.48);
          font-size: 11px;
          font-weight: 750;
          letter-spacing: 0.06em;
          text-transform: uppercase;
        }
        .quality-option {
          display: flex;
          width: 100%;
          min-height: 40px;
          align-items: center;
          justify-content: space-between;
          padding: 0 12px;
          border: 0;
          border-radius: 11px;
          outline: 0;
          background: transparent;
          color: #f4f4f6;
          font: inherit;
          font-size: 14px;
          font-weight: 700;
          cursor: pointer;
        }
        .quality-option:hover {
          background: rgba(255, 255, 255, 0.08);
        }
        .quality-option.selected {
          background: rgba(255, 255, 255, 0.1);
          color: #fff;
        }
        .quality-option.selected::after {
          content: '✓';
          color: rgba(255, 255, 255, 0.82);
        }
        .quality-empty {
          padding: 10px;
          color: rgba(235, 236, 242, 0.52);
          font-size: 12px;
          line-height: 1.4;
        }
        .center-controls {
          position: absolute;
          inset: 0;
          display: grid;
          place-items: center;
          pointer-events: none;
        }
        .center-play {
          width: 82px;
          height: 82px;
          border-color: rgba(255, 255, 255, 0.18);
          background: linear-gradient(135deg, rgba(255, 83, 0, 0.96), rgba(255, 142, 17, 0.96));
          box-shadow:
            0 18px 54px rgba(255, 83, 0, 0.34),
            inset 0 1px 0 rgba(255, 255, 255, 0.22);
          opacity: 0;
          transform: scale(0.86);
          pointer-events: none;
          transition:
            opacity 180ms ease,
            transform 180ms ease,
            filter 150ms ease;
        }
        .center-play.is-visible {
          opacity: 1;
          transform: scale(1);
          pointer-events: auto;
        }
        .center-play:hover {
          filter: brightness(1.1);
          transform: scale(1.04);
        }
        .player-controls {
          position: absolute;
          right: 32px;
          bottom: 22px;
          left: 32px;
          display: flex;
          flex-direction: column;
          gap: 12px;
          pointer-events: auto;
        }
        .player-status {
          position: absolute;
          bottom: calc(100% + 12px);
          left: 0;
          display: inline-flex;
          align-items: center;
          min-height: 30px;
          padding: 0 12px;
          border: 1px solid rgba(255, 255, 255, 0.1);
          border-radius: 999px;
          background: rgba(14, 15, 18, 0.72);
          -webkit-backdrop-filter: blur(16px);
          backdrop-filter: blur(16px);
          color: rgba(238, 239, 244, 0.72);
          font-size: 12px;
          font-weight: 650;
          opacity: 1;
          transform: translateY(0);
          transition:
            opacity 180ms ease,
            transform 180ms ease;
        }
        .player-status.is-ready {
          opacity: 0;
          transform: translateY(5px);
          pointer-events: none;
        }
        .timeline {
          position: relative;
          display: flex;
          width: 100%;
          height: 16px;
          align-items: center;
        }
        .range {
          --range-progress: 0%;
          width: 100%;
          height: 5px;
          margin: 0;
          border: 0;
          border-radius: 999px;
          outline: none;
          appearance: none;
          -webkit-appearance: none;
          background:
            linear-gradient(
              to right,
              #ff6b00 0,
              #ff9b21 var(--range-progress),
              rgba(255, 255, 255, 0.22) var(--range-progress),
              rgba(255, 255, 255, 0.22) 100%
            );
          cursor: pointer;
          pointer-events: auto;
          transition: height 140ms ease;
        }
        .range:disabled {
          cursor: default;
          opacity: 0.42;
        }
        .range:not(:disabled):hover,
        .range.is-scrubbing {
          height: 7px;
        }
        .range::-webkit-slider-thumb {
          width: 15px;
          height: 15px;
          border: 2px solid rgba(255, 255, 255, 0.92);
          border-radius: 50%;
          appearance: none;
          -webkit-appearance: none;
          background: #ff7a0c;
          box-shadow: 0 4px 14px rgba(0, 0, 0, 0.46);
          opacity: 0;
          transform: scale(0.7);
          transition:
            opacity 140ms ease,
            transform 140ms ease;
        }
        .range:not(:disabled):hover::-webkit-slider-thumb,
        .range.is-scrubbing::-webkit-slider-thumb {
          opacity: 1;
          transform: scale(1);
        }
        .control-row {
          display: grid;
          grid-template-columns: minmax(270px, 1fr) auto minmax(270px, 1fr);
          align-items: center;
          gap: 20px;
        }
        .control-cluster {
          display: flex;
          align-items: center;
          min-width: 0;
          gap: 8px;
        }
        .utility-cluster {
          justify-content: flex-end;
        }
        .player-button {
          display: inline-grid;
          flex: 0 0 auto;
          width: 46px;
          height: 46px;
          place-items: center;
          padding: 0;
          border: 1px solid rgba(255, 255, 255, 0.12);
          border-radius: 50%;
          outline: 0;
          background: rgba(27, 29, 34, 0.74);
          box-shadow:
            0 10px 28px rgba(0, 0, 0, 0.28),
            inset 0 1px 0 rgba(255, 255, 255, 0.06);
          -webkit-backdrop-filter: blur(18px) saturate(140%);
          backdrop-filter: blur(18px) saturate(140%);
          color: #fff;
          cursor: pointer;
          pointer-events: auto;
          transition:
            transform 150ms ease,
            border-color 150ms ease,
            background 150ms ease,
            opacity 150ms ease;
        }
        .player-button:hover:not(:disabled) {
          border-color: rgba(255, 255, 255, 0.24);
          background: rgba(49, 51, 58, 0.9);
          transform: translateY(-1px);
        }
        .player-button:active:not(:disabled) {
          transform: scale(0.96);
        }
        .player-button:disabled,
        .player-button.is-disabled {
          cursor: default;
          opacity: 0.42;
        }
        .player-button svg {
          display: block;
          width: 23px;
          height: 23px;
          overflow: visible;
          fill: currentColor;
        }
        .player-button svg text {
          fill: currentColor;
          font-family: "Montserrat", "Segoe UI Variable", "Segoe UI", sans-serif;
          font-size: 6px;
          font-weight: 850;
        }
        .pause-icon,
        .muted-icon,
        .fullscreen-exit-icon {
          display: none;
        }
        .is-playing .play-icon,
        .is-muted .volume-icon,
        .is-fullscreen .fullscreen-enter-icon {
          display: none;
        }
        .is-playing .pause-icon,
        .is-muted .muted-icon,
        .is-fullscreen .fullscreen-exit-icon {
          display: inline-flex;
        }
        .time-label {
          display: flex;
          min-width: 96px;
          align-items: center;
          gap: 6px;
          padding-left: 6px;
          color: rgba(250, 250, 252, 0.94);
          font-size: 13px;
          font-variant-numeric: tabular-nums;
          font-weight: 750;
          white-space: nowrap;
          text-shadow: 0 2px 12px rgba(0, 0, 0, 0.8);
        }
        .time-divider,
        #duration {
          color: rgba(235, 236, 242, 0.54);
        }
        .volume-range {
          width: 88px;
          height: 4px;
        }
        .volume-range::-webkit-slider-thumb {
          width: 13px;
          height: 13px;
          opacity: 1;
          transform: scale(1);
        }
        .episode-navigation {
          display: grid;
          grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
          align-items: center;
          gap: 10px;
        }
        .episode-navigation .action {
          min-height: 44px;
          padding: 0 16px;
        }
        .episode-navigation .episode-action span:not(.action-icon) {
          display: none;
        }
        .bottom-slot {
          display: flex;
          min-width: 0;
        }
        .bottom-slot.right {
          justify-content: flex-end;
        }
        .episode-position {
          padding: 10px 16px;
          border: 1px solid rgba(255, 255, 255, 0.1);
          border-radius: 999px;
          background: rgba(18, 19, 23, 0.62);
          box-shadow: 0 8px 26px rgba(0, 0, 0, 0.28);
          -webkit-backdrop-filter: blur(16px);
          backdrop-filter: blur(16px);
          color: rgba(244, 244, 247, 0.76);
          font-size: 13px;
          font-weight: 700;
          white-space: nowrap;
        }
        .primary {
          border-color: rgba(255, 126, 22, 0.64);
          background: linear-gradient(115deg, #ff5500, #ff8a00);
          box-shadow: 0 14px 38px rgba(255, 91, 0, 0.26);
        }
        .primary:hover {
          border-color: rgba(255, 176, 94, 0.8);
          background: linear-gradient(115deg, #ff6414, #ff9818);
        }
        @media (max-width: 1180px) {
          .control-row {
            grid-template-columns: minmax(240px, 1fr) auto minmax(170px, 1fr);
          }
          .volume-range {
            display: none;
          }
        }
        @media (max-width: 880px) {
          .top-bar {
            padding: 16px 18px;
          }
          .top-left {
            gap: 14px;
          }
          .episode-title {
            max-width: 40vw;
            font-size: 17px;
          }
          .action {
            min-height: 44px;
            padding: 0 16px;
          }
          .back-action span:last-child,
          .episode-action span:not(.action-icon) {
            display: none;
          }
          .source-caption {
            display: none;
          }
          .source-toggle {
            min-width: 0;
          }
          .player-controls {
            right: 18px;
            bottom: 16px;
            left: 18px;
          }
          .control-row {
            grid-template-columns: minmax(0, 1fr) auto;
          }
          .episode-navigation {
            display: none;
          }
          .utility-cluster {
            min-width: auto;
          }
          .time-label {
            min-width: 0;
          }
        }
        @media (max-width: 620px) {
          #rewind,
          #forward,
          #mute-toggle,
          .volume-range {
            display: none;
          }
          .episode-meta {
            display: none;
          }
        }
    """.trimIndent()
    val encodedMarkup = markup.toBase64()
    val encodedCss = css.toBase64()
    return """
        return (() => {
          const decode = value => new TextDecoder().decode(
            Uint8Array.from(atob(value), c => c.charCodeAt(0))
          );
          const playerUrl = decode("$encodedUrl");
          const providerHost = (() => {
            try {
              return new URL(playerUrl).hostname.toLowerCase();
            } catch (_) {
              return '';
            }
          })();
          const isAllohaProvider = providerHost === 'alloha.yani.tv';
          const isKodikProvider =
            providerHost === 'kodikplayer.com' || providerHost.endsWith('.kodikplayer.com');
          document.documentElement.style.cssText =
            'width:100%;height:100%;margin:0;background:#000;overflow:hidden';
          document.head.innerHTML = '<meta name="referrer" content="origin">';
          const style = document.createElement('style');
          style.textContent = decode("$encodedCss");
          document.head.appendChild(style);
          document.body.innerHTML = decode("$encodedMarkup");
          const iframe = document.getElementById('hoshira-player');
          const playerLoading = document.getElementById('player-loading');
          const loadingLabel = document.getElementById('loading-label');
          const loadingNote = document.getElementById('loading-note');
          const chrome = document.querySelector('.chrome');
          const playerStatus = document.getElementById('player-status');
          const playToggle = document.getElementById('play-toggle');
          const centerPlay = document.getElementById('center-play');
          const rewind = document.getElementById('rewind');
          const forward = document.getElementById('forward');
          const seekRange = document.getElementById('seek-range');
          const currentTimeLabel = document.getElementById('current-time');
          const durationLabel = document.getElementById('duration');
          const muteToggle = document.getElementById('mute-toggle');
          const volumeRange = document.getElementById('volume-range');
          const fullscreenToggle = document.getElementById('fullscreen-toggle');
          const qualityPicker = document.getElementById('quality-picker');
          const qualityToggle = document.getElementById('quality-toggle');
          const qualityLabel = document.getElementById('quality-label');
          const qualityOptions = document.getElementById('quality-options');
          const nextCountdown = document.getElementById('next-countdown');
          const nextSeconds = document.getElementById('next-seconds');
          const nextNow = document.getElementById('next-now');
          const nextCancel = document.getElementById('next-cancel');
          const resumeSeconds = ${chrome.resumeSeconds.coerceAtLeast(0.0)};
          const startupVolume = ${chrome.startupVolume.coerceIn(0f, 1f)};
          let preferredQuality = decode("$encodedQuality");
          const autoplayNext = ${chrome.autoplayNext && chrome.hasNext};
          const controlsHideDelay = ${chrome.controlsHideDelayMs.coerceIn(1_500, 12_000)};

          if (isKodikProvider) {
            loadingLabel.textContent = 'Запускаем Kodik…';
          }

          let chromeHideTimer = 0;
          let activeVideo = null;
          let videoListeners = [];
          let qualityEntries = [];
          let qualitySignature = '';
          let lastProviderRoots = [];
          let providerRootsDirty = true;
          let lastRootRefreshAt = 0;
          let scrubbing = false;
          let lastAudibleVolume = 1;
          let scanScheduled = false;
          let startupScanTimer = 0;
          let nextCountdownTimer = 0;
          let lastProgressReportAt = 0;
          const observedDocuments = new WeakSet();
          const activityDocuments = new WeakSet();
          const wiredFrames = new WeakSet();
          const bootstrappedVideos = new WeakSet();
          const configuredVideos = new WeakSet();

          const showPlayerLoading = () => {
            playerLoading?.classList.remove('is-hidden');
          };

          const hidePlayerLoading = () => {
            playerLoading?.classList.add('is-hidden');
          };

          const formatTime = value => {
            if (!Number.isFinite(value) || value < 0) return '0:00';
            const seconds = Math.floor(value % 60).toString().padStart(2, '0');
            const minutesTotal = Math.floor(value / 60);
            if (minutesTotal < 60) return String(minutesTotal) + ':' + seconds;
            const hours = Math.floor(minutesTotal / 60);
            const minutes = String(minutesTotal % 60).padStart(2, '0');
            return String(hours) + ':' + minutes + ':' + seconds;
          };

          const setRangeProgress = (range, ratio) => {
            const safeRatio = Math.max(0, Math.min(1, Number.isFinite(ratio) ? ratio : 0));
            range.style.setProperty('--range-progress', String(safeRatio * 100) + '%');
          };

          const setControlAvailability = available => {
            [
              playToggle,
              centerPlay,
              rewind,
              forward,
              seekRange,
              muteToggle,
              volumeRange,
              qualityToggle
            ]
              .forEach(control => {
                if (!control) return;
                control.disabled = !available;
                control.classList.toggle('is-disabled', !available);
              });
            if (!available) qualityPicker?.classList.remove('open');
          };

          const showPlayerStatus = message => {
            playerStatus.textContent = message;
            playerStatus.classList.remove('is-ready');
          };

          const hidePlayerStatus = () => {
            playerStatus.classList.add('is-ready');
          };

          const interactionIsActive = () =>
            scrubbing ||
            Boolean(activeVideo && activeVideo.paused) ||
            Boolean(document.querySelector('.source-picker.open')) ||
            Boolean(qualityPicker?.classList.contains('open')) ||
            Boolean(document.querySelector('.action:hover')) ||
            Boolean(document.querySelector('.player-button:hover')) ||
            Boolean(document.querySelector('.range:hover')) ||
            Boolean(document.querySelector('.source-option:hover')) ||
            Boolean(document.querySelector('.quality-option:hover'));

          const scheduleChromeHide = () => {
            if (!chrome) return;
            chrome.classList.remove('is-hidden');
            window.clearTimeout(chromeHideTimer);
            chromeHideTimer = window.setTimeout(() => {
              if (interactionIsActive()) {
                scheduleChromeHide();
                return;
              }
              document.querySelector('.source-picker')?.classList.remove('open');
              qualityPicker?.classList.remove('open');
              chrome.classList.add('is-hidden');
            }, controlsHideDelay);
          };

          window.hoshiraActivity = scheduleChromeHide;
          document.addEventListener('mousemove', scheduleChromeHide, { passive: true });
          document.addEventListener('mousedown', scheduleChromeHide, { passive: true });

          const postHostAction = action => {
            if (window.chrome?.webview) {
              window.chrome.webview.postMessage(action);
              return true;
            }
            if (window.hoshiraNative) {
              window.hoshiraNative(action);
              return true;
            }
            return false;
          };

          const reportPlayback = force => {
            const video = activeVideo;
            if (!video) return;
            const now = performance.now();
            if (!force && now - lastProgressReportAt < 2000) return;
            lastProgressReportAt = now;
            postHostAction(
              'playback:' +
              String(Number.isFinite(video.currentTime) ? video.currentTime : 0) + ':' +
              String(Number.isFinite(video.duration) ? video.duration : 0) + ':' +
              String(video.muted ? 0 : video.volume) + ':' +
              encodeURIComponent(qualityLabel?.textContent || '')
            );
          };

          const hideNextCountdown = () => {
            window.clearInterval(nextCountdownTimer);
            nextCountdownTimer = 0;
            if (nextCountdown) nextCountdown.style.display = 'none';
          };

          const showNextCountdown = () => {
            if (!autoplayNext || !nextCountdown) return;
            hideNextCountdown();
            let seconds = 8;
            nextSeconds.textContent = String(seconds);
            nextCountdown.style.display = 'block';
            nextCountdownTimer = window.setInterval(() => {
              seconds -= 1;
              nextSeconds.textContent = String(Math.max(0, seconds));
              if (seconds <= 0) {
                hideNextCountdown();
                postHostAction('next');
              }
            }, 1000);
          };
          nextNow?.addEventListener('click', () => {
            hideNextCountdown();
            postHostAction('next');
          });
          nextCancel?.addEventListener('click', hideNextCountdown);

          document.querySelectorAll('[data-action]').forEach(button => {
            button.addEventListener('click', event => {
              event.preventDefault();
              event.stopPropagation();
              const action = button.dataset.action || '';
              if (
                action === 'back' ||
                action === 'previous' ||
                action === 'next' ||
                action.startsWith('source:')
              ) {
                showPlayerLoading();
              }
              postHostAction(action);
              document.querySelector('.source-picker')?.classList.remove('open');
              qualityPicker?.classList.remove('open');
            });
          });
          const sourceToggle = document.getElementById('source-toggle');
          sourceToggle?.addEventListener('click', event => {
            event.preventDefault();
            event.stopPropagation();
            document.querySelector('.source-picker')?.classList.toggle('open');
            qualityPicker?.classList.remove('open');
            scheduleChromeHide();
          });

          const normalizeQualityLabel = rawValue => {
            const value = String(rawValue || '')
              .replace(/\s+/g, ' ')
              .trim();
            if (!value) return '';
            if (/^(auto|automatic|авто|автоматически)$/i.test(value)) return 'Авто';
            if (/^(source|original|original quality|исходное|оригинал|максимум)$/i.test(value)) {
              return 'Исходное';
            }
            const match = value.match(/(?:^|\D)(2160|1440|1080|900|720|576|540|480|360|240)\s*[pр]?(?:\D|$)/i);
            return match ? match[1] + 'p' : '';
          };

          const qualityCandidateLabel = element => {
            const values = [
              element?.dataset?.quality,
              element?.dataset?.resolution,
              element?.dataset?.value,
              element?.getAttribute?.('value'),
              element?.getAttribute?.('aria-label'),
              element?.getAttribute?.('title'),
              element?.textContent
            ];
            for (const value of values) {
              const label = normalizeQualityLabel(value);
              if (label) return label;
            }
            return '';
          };

          const qualityCandidateIsSelected = element => {
            if (!element) return false;
            const classes = String(element.className || '').toLowerCase();
            return (
              element.selected === true ||
              element.checked === true ||
              element.getAttribute?.('aria-selected') === 'true' ||
              element.getAttribute?.('aria-checked') === 'true' ||
              element.dataset?.selected === 'true' ||
              /(^|\s)(active|selected|current|checked)(\s|$)/.test(classes)
            );
          };

          const activateProviderQuality = entry => {
            const element = entry?.element;
            if (!element?.isConnected) {
              showPlayerStatus('Вариант качества больше недоступен');
              scheduleScan();
              return;
            }
            try {
              if (element.tagName?.toLowerCase() === 'option') {
                const select = element.parentElement;
                element.selected = true;
                if (select) {
                  select.value = element.value;
                  select.dispatchEvent(new Event('input', { bubbles: true }));
                  select.dispatchEvent(new Event('change', { bubbles: true }));
                }
              } else {
                element.click();
              }
              qualityLabel.textContent = entry.label;
              preferredQuality = '';
              reportPlayback(true);
              qualityPicker.classList.remove('open');
              showPlayerStatus('Переключаем качество на ' + entry.label + '…');
              window.setTimeout(scheduleScan, 160);
              window.setTimeout(scheduleScan, 650);
            } catch (_) {
              showPlayerStatus('Источник не разрешил сменить качество');
            }
            scheduleChromeHide();
          };

          const renderQualityOptions = entries => {
            const signature = entries
              .map(entry => entry.label + ':' + (entry.selected ? '1' : '0'))
              .join('|');
            if (signature === qualitySignature) return;
            qualitySignature = signature;
            qualityOptions.innerHTML = '';
            if (!entries.length) {
              const empty = document.createElement('div');
              empty.className = 'quality-empty';
              empty.textContent = activeVideo
                ? 'Источник пока не показал варианты'
                : 'Ищем варианты…';
              qualityOptions.appendChild(empty);
              return;
            }
            entries.forEach(entry => {
              const button = document.createElement('button');
              button.type = 'button';
              button.className = 'quality-option' + (entry.selected ? ' selected' : '');
              button.textContent = entry.label;
              button.addEventListener('click', event => {
                event.preventDefault();
                event.stopPropagation();
                activateProviderQuality(entry);
              });
              qualityOptions.appendChild(button);
            });
            const selected = entries.find(entry => entry.selected);
            if (selected) qualityLabel.textContent = selected.label;
            if (preferredQuality) {
              const preferred = entries.find(
                entry => entry.label.toLowerCase() === preferredQuality.toLowerCase()
              );
              if (preferred && !preferred.selected) {
                window.setTimeout(() => activateProviderQuality(preferred), 0);
              } else if (preferred) {
                preferredQuality = '';
              }
            }
          };

          const discoverQualityOptions = roots => {
            const selector = [
              '[data-quality]',
              '[data-resolution]',
              'option',
              'button',
              '[role="option"]',
              '[role="menuitem"]',
              '[class*="quality"] button',
              '[class*="quality"] [role="option"]',
              '[class*="quality"] [role="menuitem"]',
              '[class*="resolution"] button',
              '[class*="resolution"] [role="option"]',
              '[class*="resolution"] [role="menuitem"]',
              'button[aria-label*="качест" i]',
              'button[aria-label*="quality" i]',
              '.allplay__selects *',
              '.allplay_selects *'
            ].join(',');
            const byLabel = new Map();
            roots.forEach(root => {
              root.querySelectorAll(selector).forEach(candidate => {
                const label = qualityCandidateLabel(candidate);
                if (!label || candidate.disabled) return;
                const clickable =
                  candidate.tagName?.toLowerCase() === 'option'
                    ? candidate
                    : candidate.closest?.('button,[role="option"],[role="menuitem"],li') || candidate;
                const previous = byLabel.get(label);
                const entry = {
                  label,
                  element: clickable,
                  specificity: String(candidate.textContent || '').trim().length,
                  selected:
                    qualityCandidateIsSelected(candidate) ||
                    qualityCandidateIsSelected(clickable)
                };
                if (
                  !previous ||
                  entry.selected ||
                  (!previous.selected && entry.specificity < previous.specificity)
                ) {
                  byLabel.set(label, entry);
                }
              });
            });
            qualityEntries = Array.from(byLabel.values()).sort((left, right) => {
              if (left.label === 'Авто') return -1;
              if (right.label === 'Авто') return 1;
              if (left.label === 'Исходное') return -1;
              if (right.label === 'Исходное') return 1;
              return (parseInt(right.label, 10) || 0) - (parseInt(left.label, 10) || 0);
            });
            renderQualityOptions(qualityEntries);
          };

          const revealProviderQualityOptions = () => {
            const selector = [
              'button[class*="quality"]',
              '[class*="quality"][role="button"]',
              '[data-action*="quality"]',
              'button[class*="resolution"]',
              '[class*="resolution"][role="button"]',
              'button[aria-label*="качест" i]',
              'button[aria-label*="quality" i]',
              '.allplay__settings',
              '.allplay_settings',
              'button[class*="settings"]',
              '[class*="settings"][role="button"]'
            ].join(',');
            for (const root of lastProviderRoots) {
              const toggle = Array.from(root.querySelectorAll(selector))
                .find(element => !normalizeQualityLabel(element.textContent));
              if (toggle) {
                try {
                  toggle.click();
                } catch (_) {
                  // The next scan can still find options that already exist in the provider DOM.
                }
                break;
              }
            }
            window.setTimeout(() => {
              for (const root of lastProviderRoots) {
                const qualityMenuItem = Array.from(
                  root.querySelectorAll('button,[role="button"],[role="menuitem"],li')
                ).find(element =>
                  /^(качество|quality|разрешение|resolution)$/i.test(
                    String(element.textContent || element.getAttribute?.('aria-label') || '').trim()
                  )
                );
                if (qualityMenuItem) {
                  try {
                    qualityMenuItem.click();
                  } catch (_) {
                    // The provider can keep its variants mounted without another click.
                  }
                  break;
                }
              }
              scheduleScan();
            }, 120);
            window.setTimeout(scheduleScan, 80);
            window.setTimeout(scheduleScan, 260);
            window.setTimeout(() => {
              if (!qualityEntries.length) {
                qualitySignature = '';
                renderQualityOptions([]);
              }
            }, 600);
          };

          qualityToggle?.addEventListener('click', event => {
            event.preventDefault();
            event.stopPropagation();
            if (qualityToggle.disabled) return;
            document.querySelector('.source-picker')?.classList.remove('open');
            const opening = !qualityPicker.classList.contains('open');
            qualityPicker.classList.toggle('open', opening);
            if (opening) {
              discoverQualityOptions(lastProviderRoots);
              revealProviderQualityOptions();
            }
            scheduleChromeHide();
          });

          const updateVideoState = () => {
            const video = activeVideo;
            if (!video) {
              setControlAvailability(false);
              playToggle.classList.remove('is-playing');
              centerPlay.classList.remove('is-playing', 'is-visible');
              muteToggle.classList.remove('is-muted');
              seekRange.value = '0';
              volumeRange.value = '1';
              setRangeProgress(seekRange, 0);
              setRangeProgress(volumeRange, 1);
              currentTimeLabel.textContent = '0:00';
              durationLabel.textContent = '0:00';
              return;
            }

            const playing = !video.paused && !video.ended;
            const duration = Number.isFinite(video.duration) ? video.duration : 0;
            const currentTime = Number.isFinite(video.currentTime) ? video.currentTime : 0;
            const muted = video.muted || video.volume <= 0.001;

            setControlAvailability(true);
            qualityToggle.disabled = false;
            qualityToggle.classList.remove('is-disabled');
            playToggle.classList.toggle('is-playing', playing);
            centerPlay.classList.toggle('is-playing', playing);
            centerPlay.classList.toggle('is-visible', !playing);
            playToggle.setAttribute('aria-label', playing ? 'Пауза' : 'Воспроизвести');
            centerPlay.setAttribute('aria-label', playing ? 'Пауза' : 'Воспроизвести');
            muteToggle.classList.toggle('is-muted', muted);
            muteToggle.setAttribute('aria-label', muted ? 'Включить звук' : 'Выключить звук');

            if (!scrubbing) {
              const ratio = duration > 0 ? currentTime / duration : 0;
              seekRange.value = String(Math.round(ratio * 1000));
              setRangeProgress(seekRange, ratio);
              currentTimeLabel.textContent = formatTime(currentTime);
            }
            seekRange.disabled = duration <= 0;
            seekRange.classList.toggle('is-disabled', duration <= 0);
            durationLabel.textContent = formatTime(duration);

            const displayedVolume = muted ? 0 : video.volume;
            volumeRange.value = String(displayedVolume);
            setRangeProgress(volumeRange, displayedVolume);
          };

          const requestVideoPlay = video => {
            const result = video.play();
            if (result && typeof result.catch === 'function') {
              result.catch(() => showPlayerStatus('Источник пока не разрешил воспроизведение'));
            }
          };

          const bootstrapAllohaPlayback = video => {
            if (!isAllohaProvider || bootstrappedVideos.has(video)) return false;
            bootstrappedVideos.add(video);

            const doc = video.ownerDocument;
            const allplay = doc?.querySelector('#allplay') || doc?.querySelector('.allplay');
            const playControl = allplay?.querySelector(
              '.allplay__play, .allplay_play, button[class*="play"], [role="button"][class*="play"]'
            );
            const clickTarget =
              playControl ||
              allplay?.querySelector('.allplay__video-wrapper') ||
              video.parentElement;
            if (!clickTarget) return false;

            clickTarget.dispatchEvent(
              new MouseEvent('click', {
                bubbles: true,
                cancelable: true,
                view: doc.defaultView
              })
            );
            window.setTimeout(() => {
              if (activeVideo === video && video.paused && (video.currentSrc || video.src)) {
                requestVideoPlay(video);
              }
            }, 260);
            return true;
          };

          const togglePlayback = () => {
            const video = activeVideo;
            if (!video) {
              showPlayerStatus('Видео ещё загружается…');
              scheduleScan();
              return;
            }
            if (video.paused || video.ended) {
              showPlayerStatus('Запускаем видео…');
              if (!bootstrapAllohaPlayback(video)) {
                requestVideoPlay(video);
              }
            } else {
              video.pause();
            }
            scheduleChromeHide();
          };

          const seekBy = seconds => {
            const video = activeVideo;
            if (!video || !Number.isFinite(video.duration)) return;
            video.currentTime = Math.max(0, Math.min(video.duration, video.currentTime + seconds));
            updateVideoState();
            scheduleChromeHide();
          };

          const toggleMute = () => {
            const video = activeVideo;
            if (!video) return;
            if (video.muted || video.volume <= 0.001) {
              video.muted = false;
              video.volume = Math.max(0.05, lastAudibleVolume);
            } else {
              lastAudibleVolume = video.volume;
              video.muted = true;
            }
            updateVideoState();
            scheduleChromeHide();
          };

          const setFullscreenState = fullscreen => {
            const active = Boolean(fullscreen);
            fullscreenToggle.classList.toggle('is-fullscreen', active);
            fullscreenToggle.setAttribute(
              'aria-label',
              active ? 'Выйти из полноэкранного режима' : 'На весь экран'
            );
            fullscreenToggle.setAttribute('aria-pressed', active ? 'true' : 'false');
          };
          window.hoshiraSetFullscreenState = setFullscreenState;

          const toggleFullscreen = async () => {
            const requestedState = !fullscreenToggle.classList.contains('is-fullscreen');
            if (postHostAction('fullscreen:' + requestedState)) {
              setFullscreenState(requestedState);
              scheduleChromeHide();
              return;
            }
            try {
              if (document.fullscreenElement) {
                await document.exitFullscreen();
              } else {
                await document.documentElement.requestFullscreen();
              }
            } catch (_) {
              try {
                await activeVideo?.requestFullscreen?.();
              } catch (_) {
                showPlayerStatus('Полноэкранный режим недоступен');
              }
            }
            scheduleChromeHide();
          };

          playToggle.addEventListener('click', togglePlayback);
          centerPlay.addEventListener('click', togglePlayback);
          rewind.addEventListener('click', () => seekBy(-10));
          forward.addEventListener('click', () => seekBy(10));
          muteToggle.addEventListener('click', toggleMute);
          fullscreenToggle.addEventListener('click', toggleFullscreen);

          seekRange.addEventListener('pointerdown', () => {
            scrubbing = true;
            seekRange.classList.add('is-scrubbing');
            scheduleChromeHide();
          });
          seekRange.addEventListener('input', () => {
            const video = activeVideo;
            const duration = Number.isFinite(video?.duration) ? video.duration : 0;
            const ratio = Number(seekRange.value) / 1000;
            setRangeProgress(seekRange, ratio);
            currentTimeLabel.textContent = formatTime(duration * ratio);
          });
          const commitSeek = () => {
            const video = activeVideo;
            const duration = Number.isFinite(video?.duration) ? video.duration : 0;
            if (video && duration > 0) {
              video.currentTime = duration * (Number(seekRange.value) / 1000);
            }
            scrubbing = false;
            seekRange.classList.remove('is-scrubbing');
            updateVideoState();
            scheduleChromeHide();
          };
          seekRange.addEventListener('change', commitSeek);
          seekRange.addEventListener('pointerup', commitSeek);
          seekRange.addEventListener('pointercancel', commitSeek);

          volumeRange.addEventListener('input', () => {
            const video = activeVideo;
            if (!video) return;
            const volume = Math.max(0, Math.min(1, Number(volumeRange.value)));
            video.muted = false;
            video.volume = volume;
            if (volume > 0.001) lastAudibleVolume = volume;
            updateVideoState();
            scheduleChromeHide();
          });

          document.addEventListener('fullscreenchange', () => {
            setFullscreenState(Boolean(document.fullscreenElement));
            scheduleChromeHide();
          });

          const providerStyleText = [
            'video::-webkit-media-controls{display:none!important;-webkit-appearance:none!important}',
            'video::-webkit-media-controls-enclosure{display:none!important}',
            'video::-webkit-media-controls-panel{display:none!important}',
            'video::-webkit-media-controls-overlay-play-button{display:none!important}',
            '.vjs-control-bar,.vjs-big-play-button,.vjs-menu{opacity:0!important;visibility:hidden!important;pointer-events:none!important}',
            '.plyr__controls,.plyr__control--overlaid,.jw-controls,.jw-title,.jw-logo{opacity:0!important;visibility:hidden!important;pointer-events:none!important}',
            isAllohaProvider
              ? '.allplay__controls,.allplay_controls,.allplay__control,.allplay_control,.allplay__selectors,.allplay__selects,.allplay_selects,.allplay__settings,.allplay__logo,.allplay__watermark{opacity:0!important;visibility:hidden!important;pointer-events:none!important}'
              : '',
            isKodikProvider
              ? '.player-controls,.player_controls,.player__controls,.video-controls,.video_controls,.controls,.controls-wrapper,.controls_wrapper,.controls-container,.controls_container,.ui-controls,.ui_controls,.player-ui,.player_ui,.control-bar,.control_bar,[class*="control-panel"],[class*="control_panel"],[class*="control-bar"],[class*="control_bar"],.play-button,.play_button,.playback-button,.settings-button,.fullscreen-button,.quality-button,.episode-select,.translations,.ima-ad-container,[class*="preroll"],[id*="preroll"],[class*="advert"],[id*="advert"]{opacity:0!important;visibility:hidden!important;pointer-events:none!important}'
              : ''
          ].join('');

          const installProviderStyle = root => {
            if (!root || root.querySelector('style[data-hoshira-player-style]')) return;
            const ownerDocument = root.nodeType === Node.DOCUMENT_NODE
              ? root
              : root.ownerDocument;
            if (!ownerDocument) return;
            const providerStyle = ownerDocument.createElement('style');
            providerStyle.dataset.hoshiraPlayerStyle = 'true';
            providerStyle.textContent = providerStyleText;
            if (root.nodeType === Node.DOCUMENT_NODE) {
              root.head?.appendChild(providerStyle);
            } else {
              root.appendChild(providerStyle);
            }
          };

          const handlePlayerKey = event => {
            const target = event.target;
            const tagName = target?.tagName?.toLowerCase();
            if (
              event.code === 'Escape' &&
              fullscreenToggle.classList.contains('is-fullscreen')
            ) {
              event.preventDefault();
              event.stopPropagation();
              toggleFullscreen();
              scheduleChromeHide();
              return;
            }
            if (tagName === 'input' || tagName === 'button' || target?.isContentEditable) return;
            switch (event.code) {
              case 'Space':
              case 'KeyK':
                event.preventDefault();
                togglePlayback();
                break;
              case 'ArrowLeft':
                event.preventDefault();
                seekBy(-10);
                break;
              case 'ArrowRight':
                event.preventDefault();
                seekBy(10);
                break;
              case 'ArrowUp':
              case 'ArrowDown': {
                const video = activeVideo;
                if (!video) break;
                event.preventDefault();
                video.muted = false;
                video.volume = Math.max(
                  0,
                  Math.min(1, video.volume + (event.code === 'ArrowUp' ? 0.05 : -0.05))
                );
                if (video.volume > 0.001) lastAudibleVolume = video.volume;
                updateVideoState();
                break;
              }
              case 'KeyM':
                event.preventDefault();
                toggleMute();
                break;
              case 'KeyF':
                event.preventDefault();
                toggleFullscreen();
                break;
            }
            scheduleChromeHide();
          };

          const wireDocumentActivity = doc => {
            if (!doc || activityDocuments.has(doc)) return;
            activityDocuments.add(doc);
            doc.addEventListener('mousemove', scheduleChromeHide, { passive: true });
            doc.addEventListener('mousedown', scheduleChromeHide, { passive: true });
            doc.addEventListener('keydown', handlePlayerKey);
          };

          const wireFrame = frame => {
            if (!frame || wiredFrames.has(frame)) return;
            wiredFrames.add(frame);
            frame.addEventListener('load', () => {
              providerRootsDirty = true;
              scheduleScan();
            });
          };

          const observeProviderRoot = root => {
            const observedNode = root?.nodeType === Node.DOCUMENT_NODE
              ? root.documentElement
              : root;
            if (!observedNode || observedDocuments.has(root)) return;
            observedDocuments.add(root);
            const observer = new MutationObserver(() => {
              if (activeVideo?.isConnected) {
                activeVideo.controls = false;
                activeVideo.removeAttribute('controls');
                return;
              }
              providerRootsDirty = true;
              scheduleScan();
            });
            observer.observe(observedNode, {
              childList: true,
              subtree: true,
              attributes: true,
              attributeFilter: ['controls', 'src']
            });
          };

          const collectAccessibleRoots = (root, roots) => {
            if (!root || roots.includes(root)) return;
            roots.push(root);
            installProviderStyle(root);
            wireDocumentActivity(root);
            observeProviderRoot(root);

            root.querySelectorAll('*').forEach(element => {
              if (element.shadowRoot) {
                collectAccessibleRoots(element.shadowRoot, roots);
              }
            });
            root.querySelectorAll('iframe').forEach(frame => {
              wireFrame(frame);
              try {
                collectAccessibleRoots(frame.contentDocument, roots);
              } catch (_) {
                // A cross-origin nested frame remains usable, but cannot be controlled directly.
              }
            });
          };

          const videoScore = video => {
            if (!video?.isConnected) return -1;
            const rect = video.getBoundingClientRect();
            const visibleArea = Math.max(0, rect.width) * Math.max(0, rect.height);
            const style = video.ownerDocument?.defaultView?.getComputedStyle(video);
            const visible = style?.display !== 'none' && style?.visibility !== 'hidden';
            const playable = video.readyState >= 1 || Boolean(video.currentSrc || video.src);
            return (visible ? 1000000000 : 0) +
              (playable ? 100000000 : 0) +
              visibleArea +
              video.readyState * 10000;
          };

          const clearVideoListeners = () => {
            videoListeners.forEach(remove => remove());
            videoListeners = [];
          };

          const bindVideo = video => {
            if (!video || activeVideo === video) {
              if (video) {
                video.controls = false;
                video.removeAttribute('controls');
                updateVideoState();
              }
              return;
            }

            clearVideoListeners();
            activeVideo = video;
            activeVideo.controls = false;
            activeVideo.removeAttribute('controls');
            activeVideo.playsInline = true;
            activeVideo.setAttribute('playsinline', '');
            if (!configuredVideos.has(activeVideo)) {
              configuredVideos.add(activeVideo);
              activeVideo.muted = false;
              activeVideo.volume = startupVolume;
              const applyResume = () => {
                if (
                  resumeSeconds > 0 &&
                  Number.isFinite(activeVideo.duration) &&
                  activeVideo.duration > resumeSeconds + 3
                ) {
                  activeVideo.currentTime = resumeSeconds;
                }
              };
              applyResume();
              activeVideo.addEventListener('loadedmetadata', applyResume, { once: true });
            }
            lastAudibleVolume = activeVideo.volume > 0.001 ? activeVideo.volume : lastAudibleVolume;
            if (startupScanTimer) {
              window.clearInterval(startupScanTimer);
              startupScanTimer = 0;
            }
            hidePlayerLoading();

            const listen = (name, listener) => {
              activeVideo.addEventListener(name, listener);
              videoListeners.push(() => video.removeEventListener(name, listener));
            };
            const refresh = event => {
              hidePlayerStatus();
              updateVideoState();
              reportPlayback(event?.type !== 'timeupdate');
            };
            ['play', 'pause', 'timeupdate', 'durationchange', 'loadedmetadata',
             'volumechange', 'ended', 'seeking', 'seeked', 'canplay']
              .forEach(name => listen(name, refresh));
            listen('ended', () => {
              reportPlayback(true);
              showNextCountdown();
            });
            listen('play', hideNextCountdown);
            listen('waiting', () => {
              showPlayerStatus('Буферизация…');
              updateVideoState();
            });
            listen('playing', () => {
              hidePlayerStatus();
              updateVideoState();
              scheduleChromeHide();
            });
            listen('emptied', scheduleScan);

            hidePlayerStatus();
            updateVideoState();
            scheduleChromeHide();
          };

          function scanForVideo() {
            scanScheduled = false;
            const now = performance.now();
            let roots = lastProviderRoots;
            if (
              roots.length === 0 ||
              (providerRootsDirty && now - lastRootRefreshAt >= 420)
            ) {
              const refreshedRoots = [];
              try {
                collectAccessibleRoots(iframe.contentDocument, refreshedRoots);
              } catch (_) {
                hidePlayerStatus();
                return;
              }
              roots = refreshedRoots;
              lastProviderRoots = refreshedRoots;
              providerRootsDirty = false;
              lastRootRefreshAt = now;
            }

            const candidates = roots
              .flatMap(root => Array.from(root.querySelectorAll('video')))
              .filter(video => video.isConnected)
              .sort((left, right) => videoScore(right) - videoScore(left));
            const bestVideo = candidates[0] || null;

            if (bestVideo) {
              bindVideo(bestVideo);
            } else if (!activeVideo || !activeVideo.isConnected) {
              clearVideoListeners();
              activeVideo = null;
              setControlAvailability(false);
              hidePlayerStatus();
              updateVideoState();
            }
          }

          function scheduleScan() {
            if (scanScheduled) return;
            scanScheduled = true;
            window.requestAnimationFrame(scanForVideo);
          }

          document.addEventListener('keydown', handlePlayerKey);
          iframe.addEventListener('load', () => {
            providerRootsDirty = true;
            scheduleScan();
          });
          startupScanTimer = window.setInterval(scheduleScan, 160);
          window.setTimeout(() => {
            if (startupScanTimer) {
              window.clearInterval(startupScanTimer);
              startupScanTimer = 0;
            }
          }, 5000);
          window.setInterval(() => {
            if (!activeVideo?.isConnected) {
              scheduleScan();
              return;
            }
            activeVideo.controls = false;
            activeVideo.removeAttribute('controls');
          }, 1500);
          window.setTimeout(() => {
            hidePlayerLoading();
            if (!activeVideo && !isKodikProvider) {
              showPlayerStatus('Источник загружается дольше обычного…');
            }
          }, isKodikProvider ? 2000 : 6000);
          setControlAvailability(false);
          setRangeProgress(seekRange, 0);
          setRangeProgress(volumeRange, 1);
          iframe.src = playerUrl;
          scheduleScan();
          scheduleChromeHide();
          document.title = 'Hoshira player';
          window.chrome?.webview?.postMessage('__hoshira_ready__');
          return true;
        })();
    """.trimIndent()
}

private fun String.toBase64(): String =
    Base64.getEncoder().encodeToString(toByteArray(StandardCharsets.UTF_8))

private fun String.escapeHtml(): String = buildString(length) {
    this@escapeHtml.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character
            },
        )
    }
}
