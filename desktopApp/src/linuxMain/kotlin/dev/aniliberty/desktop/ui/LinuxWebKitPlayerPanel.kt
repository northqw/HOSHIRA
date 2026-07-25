package dev.aniliberty.desktop.ui

import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Color
import java.awt.EventQueue
import java.awt.MouseInfo
import java.awt.Panel
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer
import kotlin.concurrent.thread
import org.eclipse.swt.SWT
import org.eclipse.swt.awt.SWT_AWT
import org.eclipse.swt.browser.Browser
import org.eclipse.swt.browser.BrowserFunction
import org.eclipse.swt.browser.LocationListener
import org.eclipse.swt.browser.ProgressAdapter
import org.eclipse.swt.browser.ProgressEvent
import org.eclipse.swt.layout.FillLayout
import org.eclipse.swt.widgets.Display
import org.eclipse.swt.widgets.Shell

/**
 * Linux browser host backed by the system WebKitGTK/GStreamer stack.
 *
 * The public class name intentionally matches the Windows implementation so
 * the Compose player screen stays platform-neutral. One panel, browser and
 * WebKit process are kept alive while the player route is open; switching an
 * episode only navigates that existing browser.
 */
internal class NativeWebView2PlayerPanel(
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
    private var activeChrome: EmbeddedPlayerChrome? = null
    private var hostInstalled = false

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
        if (requestedUrl == url && requestedChrome == chrome) return

        requestedUrl = url
        requestedChrome = chrome
        notifyState(EmbeddedPlayerState.Starting)
        display?.postSafely {
            browser?.takeUnless(Browser::isDisposed)?.let { currentBrowser ->
                load(currentBrowser, url, chrome)
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
        thread(name = "hoshira-linux-webkit", isDaemon = true) {
            runBrowserLoop()
        }
    }

    private fun runBrowserLoop() {
        try {
            Display.setAppName("Hoshira")
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

            val swtBrowser = Browser(swtShell, SWT.NONE).apply {
                setJavascriptEnabled(true)
                setText(PLAYER_LOADING_HTML)
            }
            browser = swtBrowser
            startPointerTracking()

            val actionBridge = object : BrowserFunction(swtBrowser, "hoshiraNative") {
                override fun function(arguments: Array<out Any?>): Any? {
                    handlePlayerMessage(arguments.firstOrNull()?.toString().orEmpty())
                    return null
                }
            }
            swtBrowser.addOpenWindowListener { event ->
                event.required = true
                event.browser = null
            }
            swtBrowser.addLocationListener(
                LocationListener.changingAdapter { event ->
                    if (event.top && hostInstalled) event.doit = false
                },
            )
            swtBrowser.addProgressListener(
                object : ProgressAdapter() {
                    override fun completed(event: ProgressEvent) {
                        installPlayerHost(swtBrowser)
                    }
                },
            )

            swtShell.open()
            applyEmbeddedBounds(swtShell, swtBrowser)
            startBoundsSynchronizer(swtDisplay, swtShell, swtBrowser)
            swtDisplay.asyncExec {
                if (!disposed.get() && !swtBrowser.isDisposed) {
                    load(swtBrowser, requestedUrl, requestedChrome)
                }
            }

            while (!disposed.get() && !swtShell.isDisposed) {
                if (!swtDisplay.readAndDispatch()) swtDisplay.sleep()
            }

            if (!actionBridge.isDisposed) actionBridge.dispose()
            if (!swtShell.isDisposed) swtShell.dispose()
            if (!swtDisplay.isDisposed) swtDisplay.dispose()
        } catch (error: Throwable) {
            notifyState(EmbeddedPlayerState.Failed(error.toPlayerMessage()))
        } finally {
            stopPointerTracking()
            runCatching { shell?.takeUnless(Shell::isDisposed)?.dispose() }
            runCatching { display?.takeUnless(Display::isDisposed)?.dispose() }
            browser = null
            shell = null
            display = null
        }
    }

    private fun installPlayerHost(currentBrowser: Browser) {
        val url = activeUrl ?: return
        val chrome = activeChrome ?: return
        if (hostInstalled || currentBrowser.isDisposed) return
        hostInstalled = true

        val installed = runCatching {
            currentBrowser.evaluate(playerHostScript(url, chrome)) == true
        }.getOrDefault(false)
        if (installed) {
            notifyState(EmbeddedPlayerState.Ready)
        } else {
            notifyState(
                EmbeddedPlayerState.Failed(
                    "WebKitGTK не смог встроить страницу плеера.",
                ),
            )
        }
    }

    private fun handlePlayerMessage(message: String) {
        when (message) {
            "__hoshira_ready__" -> notifyState(EmbeddedPlayerState.Ready)
            "back" -> notifyAction(EmbeddedPlayerAction.Back)
            "previous" -> notifyAction(EmbeddedPlayerAction.Previous)
            "next" -> notifyAction(EmbeddedPlayerAction.Next)
            else -> message
                .removePrefix("source:")
                .takeIf { message.startsWith("source:") && it.isNotBlank() }
                ?.let { notifyAction(EmbeddedPlayerAction.SelectSource(it)) }
        }
    }

    private fun load(
        currentBrowser: Browser,
        url: String,
        chrome: EmbeddedPlayerChrome,
    ) {
        val validUrl = runCatching { URI(url) }
            .getOrNull()
            ?.takeIf { it.scheme == "https" || it.scheme == "http" }
            ?.toASCIIString()
        if (validUrl == null) {
            notifyState(
                EmbeddedPlayerState.Failed(
                    "Источник вернул некорректную ссылку на плеер.",
                ),
            )
            return
        }

        activeUrl = validUrl
        activeChrome = chrome
        hostInstalled = false
        val opened = if (isAllohaUrl(validUrl)) {
            currentBrowser.setUrl(
                validUrl,
                null,
                arrayOf(
                    "Referer: $ALLOHA_ORIGIN",
                    "Origin: ${ALLOHA_ORIGIN.removeSuffix("/")}",
                ),
            )
        } else {
            currentBrowser.setUrl(validUrl)
        }
        if (!opened) {
            notifyState(
                EmbeddedPlayerState.Failed(
                    "WebKitGTK не смог открыть страницу плеера.",
                ),
            )
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

    private fun startPointerTracking() {
        EventQueue.invokeLater {
            if (disposed.get() || pointerTracker != null) return@invokeLater

            var lastPosition: Point? = null
            var wasInside = false
            pointerTracker = Timer(100) {
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
}

private fun Display.postSafely(block: () -> Unit) {
    if (isDisposed) return
    asyncExec {
        if (!isDisposed) block()
    }
}

private fun Throwable.toPlayerMessage(): String {
    val details = message.orEmpty().ifBlank { javaClass.simpleName }
    return if (
        details.contains("webkit", ignoreCase = true) ||
        details.contains("browser", ignoreCase = true)
    ) {
        "Не удалось запустить WebKitGTK. Установите WebKitGTK и пакеты GStreamer: $details"
    } else {
        "Не удалось запустить встроенный плеер: $details"
    }
}

private fun isAllohaUrl(url: String): Boolean =
    runCatching { URI(url).host }
        .getOrNull()
        ?.equals("alloha.yani.tv", ignoreCase = true)
        ?: false

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
