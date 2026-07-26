package dev.aniliberty.desktop.ui

import dev.aniliberty.desktop.platformCacheDirectory
import dev.aniliberty.desktop.data.UserDataStore
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser.MSG
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Canvas
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.MouseInfo
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.Timer
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json

/**
 * A heavyweight AWT component whose HWND is used directly as WebView2's parent.
 *
 * WebView2 and all COM callbacks live on one dedicated Windows STA thread with
 * its own message pump. This removes SWT and preserves a regular SwingPanel
 * boundary for Compose without blocking AWT.
 */
internal class NativeWebView2PlayerPanel(
    initialUrl: String,
    initialChrome: EmbeddedPlayerChrome,
    onStateChange: (EmbeddedPlayerState) -> Unit,
    onAction: (EmbeddedPlayerAction) -> Unit,
) : Canvas() {
    private val disposed = AtomicBoolean(false)

    @Volatile
    private var requestedUrl = initialUrl

    @Volatile
    private var requestedChrome = initialChrome

    @Volatile
    private var stateCallback = onStateChange

    @Volatile
    private var actionCallback = onAction

    private var host: WindowsWebView2Host? = null
    private var pointerTracker: Timer? = null
    private var loadingTimer: Timer? = null
    private var loadingAngle = 24

    init {
        background = PLAYER_LOADING_BACKGROUND
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    runOnAwt { host?.resize() }
                }

                override fun componentMoved(event: ComponentEvent) {
                    runOnAwt { host?.notifyParentPositionChanged() }
                }
            },
        )
    }

    override fun addNotify() {
        super.addNotify()
        showLoadingSurface()
        runOnAwt {
            if (!disposed.get() && host == null && isDisplayable) {
                startHost()
            }
        }
    }

    override fun removeNotify() {
        stopLoadingAnimation()
        disposePlayer()
        super.removeNotify()
    }

    override fun paint(graphics: Graphics) {
        val canvas = graphics as? Graphics2D ?: return
        canvas.color = PLAYER_LOADING_BACKGROUND
        canvas.fillRect(0, 0, width, height)

        if (!loadingVisible) return

        val scale = graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        val spinnerSize = (38 * scale).toInt().coerceAtLeast(26)
        val strokeWidth = (3 * scale).toFloat().coerceAtLeast(2f)
        val centerX = width / 2
        val centerY = height / 2
        val spinnerX = centerX - spinnerSize / 2
        val spinnerY = centerY - spinnerSize

        canvas.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON,
        )
        canvas.stroke = BasicStroke(
            strokeWidth,
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND,
        )
        canvas.color = PLAYER_LOADING_TRACK
        canvas.drawArc(spinnerX, spinnerY, spinnerSize, spinnerSize, 0, 360)
        canvas.color = PLAYER_LOADING_ACCENT
        canvas.drawArc(spinnerX, spinnerY, spinnerSize, spinnerSize, loadingAngle, 108)

        val fontSize = (16 * scale).toFloat().coerceAtLeast(12f)
        canvas.font = Font("Segoe UI", Font.PLAIN, fontSize.toInt())
        canvas.color = PLAYER_LOADING_TEXT
        val label = PLAYER_LOADING_LABEL
        val metrics = canvas.fontMetrics
        val labelX = centerX - metrics.stringWidth(label) / 2
        val labelY = spinnerY + spinnerSize + (18 * scale).toInt() + metrics.ascent
        canvas.drawString(label, labelX, labelY)
    }

    override fun update(graphics: Graphics) {
        paint(graphics)
    }

    fun update(
        url: String,
        chrome: EmbeddedPlayerChrome,
        onStateChange: (EmbeddedPlayerState) -> Unit,
        onAction: (EmbeddedPlayerAction) -> Unit,
    ) {
        stateCallback = onStateChange
        actionCallback = onAction
        val chromeChanged = requestedChrome != chrome
        requestedChrome = chrome
        if (requestedUrl == url && !chromeChanged) return

        requestedUrl = url
        notifyState(EmbeddedPlayerState.Starting)
        runOnAwt {
            host?.navigate(url, chrome)
        }
    }

    fun disposePlayer() {
        if (!disposed.compareAndSet(false, true)) return
        stateCallback = {}
        actionCallback = {}
        runOnAwt {
            stopPointerTracking()
            host?.close()
            host = null
        }
    }

    fun setFullscreenState(fullscreen: Boolean) {
        runOnAwt {
            host?.setFullscreenState(fullscreen)
        }
    }

    private fun startHost() {
        notifyState(EmbeddedPlayerState.Starting)
        runCatching {
            WindowsWebView2Host(
                parent = this,
                onStateChange = ::notifyState,
                onAction = ::notifyAction,
            ).also { webViewHost ->
                host = webViewHost
                webViewHost.start(requestedUrl, requestedChrome)
                startPointerTracking()
            }
        }.onFailure { error ->
            notifyState(EmbeddedPlayerState.Failed(error.toDirectWebView2Message()))
        }
    }

    private fun notifyState(state: EmbeddedPlayerState) {
        runOnAwt {
            if (!disposed.get()) {
                if (state is EmbeddedPlayerState.Starting) {
                    showLoadingSurface()
                } else {
                    hideLoadingSurface()
                }
                stateCallback(state)
            }
        }
    }

    private fun notifyAction(action: EmbeddedPlayerAction) {
        runOnAwt {
            if (!disposed.get()) actionCallback(action)
        }
    }

    private fun startPointerTracking() {
        if (pointerTracker != null || disposed.get()) return

        var lastPosition: Point? = null
        var wasInside = false
        pointerTracker = Timer(120) {
            if (disposed.get()) {
                stopPointerTracking()
                return@Timer
            }

            val pointer = MouseInfo.getPointerInfo()?.location ?: return@Timer
            val origin = runCatching { locationOnScreen }.getOrNull() ?: return@Timer
            val inside =
                pointer.x >= origin.x &&
                    pointer.y >= origin.y &&
                    pointer.x < origin.x + width &&
                    pointer.y < origin.y + height
            val moved = lastPosition?.let { previous ->
                previous.x != pointer.x || previous.y != pointer.y
            } ?: true

            if (inside && (moved || !wasInside)) {
                host?.signalPointerActivity()
            }
            lastPosition = Point(pointer)
            wasInside = inside
        }.apply {
            isRepeats = true
            start()
        }
    }

    private fun stopPointerTracking() {
        pointerTracker?.stop()
        pointerTracker = null
    }

    private fun showLoadingSurface() {
        loadingVisible = true
        if (loadingTimer == null) {
            loadingTimer = Timer(16) {
                loadingAngle = Math.floorMod(loadingAngle - 5, 360)
                repaint()
            }.apply {
                isRepeats = true
                start()
            }
        }
        repaint()
    }

    private fun hideLoadingSurface() {
        loadingVisible = false
        stopLoadingAnimation()
        repaint()
    }

    private fun stopLoadingAnimation() {
        loadingTimer?.stop()
        loadingTimer = null
    }

    @Volatile
    private var loadingVisible = true
}

private class WindowsWebView2Host(
    private val parent: Canvas,
    private val onStateChange: (EmbeddedPlayerState) -> Unit,
    private val onAction: (EmbeddedPlayerAction) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val handlers = mutableListOf<ComHandler>()
    private val parentWindow = HWND(Native.getComponentPointer(parent))
    private val commandQueue = ConcurrentLinkedQueue<() -> Unit>()

    private var oleInitialized = false
    private var coreClosed = false
    private var webViewThreadId = 0
    private var nativeHostWindow: HWND? = null
    private var environment: Pointer? = null
    private var environment2: Pointer? = null
    private var controller: Pointer? = null
    private var webView: Pointer? = null
    private var webView2: Pointer? = null
    private var activeUrl: String? = null
    private var activeChrome: EmbeddedPlayerChrome? = null
    private var hostInstalling = false
    private var hostInstalled = false
    private var initializationScriptId: String? = null
    private var navigationGeneration = 0L
    @Volatile
    private var requestedWidth = readParentClientSize().width
    @Volatile
    private var requestedHeight = readParentClientSize().height
    @Volatile
    private var requestedVisible = parent.isShowing

    fun start(url: String, chrome: EmbeddedPlayerChrome) {
        activeUrl = url
        activeChrome = chrome

        thread(
            name = "hoshira-native-webview2",
            isDaemon = true,
        ) {
            runWebViewThread()
        }
    }

    fun navigate(url: String, chrome: EmbeddedPlayerChrome) {
        if (closed.get()) return
        post {
            activeUrl = url
            activeChrome = chrome
            hostInstalling = false
            hostInstalled = false
            resizeCore()

            val currentWebView = webView ?: return@post
            onStateChange(EmbeddedPlayerState.Starting)
            preparePlayerNavigation(currentWebView, url, chrome)
        }
    }

    fun resize() {
        if (closed.get()) return
        val clientSize = readParentClientSize()
        requestedWidth = clientSize.width
        requestedHeight = clientSize.height
        requestedVisible = parent.isShowing
        post(::resizeCore)
    }

    /**
     * AWT component bounds use logical user-space units on HiDPI displays,
     * while child HWND and WebView2 controller bounds use native client
     * pixels. Reading the Canvas HWND avoids applying the scale twice (or not
     * at all) and also follows per-monitor DPI changes.
     */
    private fun readParentClientSize(): Dimension {
        val clientBounds = RECT()
        if (User32.INSTANCE.GetClientRect(parentWindow, clientBounds)) {
            return Dimension(
                (clientBounds.right - clientBounds.left).coerceAtLeast(1),
                (clientBounds.bottom - clientBounds.top).coerceAtLeast(1),
            )
        }

        val transform = parent.graphicsConfiguration?.defaultTransform
        return Dimension(
            (parent.width * (transform?.scaleX ?: 1.0)).roundToInt().coerceAtLeast(1),
            (parent.height * (transform?.scaleY ?: 1.0)).roundToInt().coerceAtLeast(1),
        )
    }

    fun notifyParentPositionChanged() {
        if (closed.get()) return
        post {
            controller?.call(HOST_CONTROLLER_NOTIFY_PARENT_POSITION_CHANGED)
        }
    }

    fun signalPointerActivity() {
        if (closed.get()) return
        post {
            if (hostInstalled) {
                executeScript("window.hoshiraActivity && window.hoshiraActivity();")
            }
        }
    }

    fun setFullscreenState(fullscreen: Boolean) {
        if (closed.get()) return
        post {
            if (hostInstalled) {
                executeScript(
                    "window.hoshiraSetFullscreenState && " +
                        "window.hoshiraSetFullscreenState($fullscreen);",
                )
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        commandQueue.add(::closeCore)
        wakeWebViewThread()
    }

    private fun runWebViewThread() {
        try {
            webViewThreadId = Kernel32.INSTANCE.GetCurrentThreadId()
            initializeComAndHostWindow()
            drainCommands()
            if (!closed.get()) {
                requestEnvironment()
            }

            val message = MSG()
            while (!coreClosed) {
                val result = User32.INSTANCE.GetMessage(message, null, 0, 0)
                if (result <= 0) break
                if (
                    message.message == WEBVIEW_TASK_MESSAGE &&
                    message.hWnd.isNullWindowHandle()
                ) {
                    drainCommands()
                } else {
                    User32.INSTANCE.TranslateMessage(message)
                    User32.INSTANCE.DispatchMessage(message)
                }
            }
        } catch (error: Throwable) {
            fail(error.toDirectWebView2Message())
        } finally {
            closeCore()
        }
    }

    private fun initializeComAndHostWindow() {
        val oleResult = Ole32.INSTANCE.OleInitialize(Pointer.NULL).toInt()
        webView2Log("OleInitialize=${oleResult.toHexHResult()}")
        if (oleResult < 0 && oleResult != RPC_E_CHANGED_MODE) {
            error("Не удалось инициализировать Windows COM: ${oleResult.toHexHResult()}")
        }
        if (oleResult == RPC_E_CHANGED_MODE) {
            error("Поток WebView2 уже использует несовместимую COM-модель.")
        }
        oleInitialized = oleResult >= 0

        nativeHostWindow = User32.INSTANCE.CreateWindowEx(
            0,
            "STATIC",
            "",
            WS_CHILD or WS_CLIPCHILDREN or WS_CLIPSIBLINGS,
            0,
            0,
            requestedWidth,
            requestedHeight,
            parentWindow,
            null,
            null,
            null,
        ) ?: error("Windows не создала дочернее окно для WebView2.")
        webView2Log(
            "native host HWND=0x" +
                Pointer.nativeValue(nativeHostWindow?.pointer).toString(16).uppercase(),
        )
    }

    private fun requestEnvironment() {
        WebView2NativeRuntime.configureHardwareAcceleration()
        val environmentHandler = CompletionHandler(
            interfaceId = IID_CORE_WEBVIEW2_CREATE_ENVIRONMENT_COMPLETED_HANDLER,
        ) { errorCode, createdEnvironment ->
            when {
                closed.get() -> Unit
                errorCode < 0 || createdEnvironment.isNullPointer() ->
                    fail("WebView2 Runtime не создал окружение: ${errorCode.toHexHResult()}")

                else -> onEnvironmentCreated(createdEnvironment)
            }
        }.retain()

        val result = WebView2NativeRuntime.loader.CreateCoreWebView2EnvironmentWithOptions(
            null,
            WString(WebView2NativeRuntime.userDataDirectory.absolutePath),
            Pointer.NULL,
            environmentHandler.pointer,
        )
        if (result < 0) {
            fail("Не удалось запустить WebView2 Runtime: ${result.toHexHResult()}")
            return
        }
        webView2Log("environment requested (${result.toHexHResult()})")
    }

    private fun resizeCore() {
        if (coreClosed) return
        val hostWindow = nativeHostWindow ?: return
        User32.INSTANCE.MoveWindow(
            hostWindow,
            0,
            0,
            requestedWidth,
            requestedHeight,
            true,
        )
        val currentController = controller ?: return
        val clientBounds = RECT()
        if (!User32.INSTANCE.GetClientRect(hostWindow, clientBounds)) return

        val bounds = WebViewRect(
            left = 0,
            top = 0,
            right = (clientBounds.right - clientBounds.left).coerceAtLeast(1),
            bottom = (clientBounds.bottom - clientBounds.top).coerceAtLeast(1),
        )
        bounds.write()
        val boundsResult = currentController.call(HOST_CONTROLLER_PUT_BOUNDS, bounds)
        val shouldBeVisible = requestedVisible && hostInstalled
        val visibilityResult = currentController.call(
            HOST_CONTROLLER_PUT_IS_VISIBLE,
            if (shouldBeVisible) 1 else 0,
        )
        User32.INSTANCE.ShowWindow(
            hostWindow,
            if (shouldBeVisible) SW_SHOWNA else SW_HIDE,
        )
        webView2Log(
            "resize ${bounds.right}x${bounds.bottom}, " +
                "bounds=${boundsResult.toHexHResult()}, " +
                "visible=${visibilityResult.toHexHResult()}",
        )
    }

    private fun closeCore() {
        if (coreClosed) return
        coreClosed = true
        controller?.call(HOST_CONTROLLER_CLOSE)
        webView2.releaseIfPresent()
        webView.releaseIfPresent()
        controller.releaseIfPresent()
        environment2.releaseIfPresent()
        environment.releaseIfPresent()
        webView2 = null
        webView = null
        controller = null
        environment2 = null
        environment = null
        handlers.clear()
        nativeHostWindow?.let(User32.INSTANCE::DestroyWindow)
        nativeHostWindow = null

        if (oleInitialized) {
            Ole32.INSTANCE.OleUninitialize()
            oleInitialized = false
        }
    }

    private fun post(command: () -> Unit) {
        if (closed.get()) return
        commandQueue.add(command)
        wakeWebViewThread()
    }

    private fun drainCommands() {
        while (true) {
            val command = commandQueue.poll() ?: break
            command()
        }
    }

    private fun wakeWebViewThread() {
        val threadId = webViewThreadId
        if (threadId == 0) return
        User32.INSTANCE.PostThreadMessage(
            threadId,
            WEBVIEW_TASK_MESSAGE,
            WPARAM(0),
            LPARAM(0),
        )
    }

    private fun onEnvironmentCreated(createdEnvironment: Pointer) {
        if (closed.get()) return

        createdEnvironment.addRefIfPresent()
        environment = createdEnvironment
        environment2 = createdEnvironment.queryInterface(IID_CORE_WEBVIEW2_ENVIRONMENT_2)
        webView2Log("environment created, environment2=${environment2 != null}")

        val controllerHandler = CompletionHandler(
            interfaceId = IID_CORE_WEBVIEW2_CREATE_CONTROLLER_COMPLETED_HANDLER,
        ) { errorCode, createdController ->
            when {
                closed.get() -> Unit
                errorCode < 0 || createdController.isNullPointer() ->
                    fail("WebView2 не создал контроллер: ${errorCode.toHexHResult()}")

                else -> onControllerCreated(createdController)
            }
        }.retain()

        val result = createdEnvironment.call(
            ENVIRONMENT_CREATE_CONTROLLER,
            nativeHostWindow?.pointer ?: Pointer.NULL,
            controllerHandler.pointer,
        )
        webView2Log(
            "controller requested for HWND=0x" +
                Pointer.nativeValue(nativeHostWindow?.pointer).toString(16).uppercase() +
                " (${result.toHexHResult()})",
        )
        if (result < 0) {
            fail("Не удалось привязать WebView2 к окну: ${result.toHexHResult()}")
        }
    }

    private fun onControllerCreated(createdController: Pointer) {
        if (closed.get()) return

        createdController.addRefIfPresent()
        controller = createdController
        webView2Log("controller created")
        createdController.queryInterface(IID_CORE_WEBVIEW2_CONTROLLER_2)?.let { controller2 ->
            val blackBackground = CoreWebView2Color(
                a = 0xFF.toByte(),
                r = 0,
                g = 0,
                b = 0,
            ).apply { write() }
            val backgroundResult = controller2.call(
                HOST_CONTROLLER2_PUT_DEFAULT_BACKGROUND_COLOR,
                blackBackground,
            )
            webView2Log(
                "default background set to black (${backgroundResult.toHexHResult()})",
            )
            controller2.releaseIfPresent()
        }
        val webViewOut = PointerByReference()
        val webViewResult = createdController.call(
            HOST_CONTROLLER_GET_CORE_WEBVIEW2,
            webViewOut,
        )
        val createdWebView = webViewOut.value
        if (webViewResult < 0 || createdWebView.isNullPointer()) {
            fail("WebView2 не вернул браузерный интерфейс: ${webViewResult.toHexHResult()}")
            return
        }

        webView = createdWebView
        webView2 = createdWebView.queryInterface(IID_CORE_WEBVIEW2_2)
        webView2Log("webview created, webview2=${webView2 != null}")
        configureSettings(createdWebView)
        installEventHandlers(createdWebView)
        resizeCore()

        val url = activeUrl
        val chrome = activeChrome
        if (url == null || chrome == null) {
            fail("Источник не вернул ссылку на плеер.")
        } else {
            // Start the next WebView2 async operation only after the controller
            // completion callback has returned to this STA's message loop.
            post {
                if (webView === createdWebView && !closed.get()) {
                    preparePlayerNavigation(createdWebView, url, chrome)
                }
            }
        }
    }

    private fun configureSettings(currentWebView: Pointer) {
        val settingsOut = PointerByReference()
        val result = currentWebView.call(CORE_WEBVIEW2_GET_SETTINGS, settingsOut)
        val settings = settingsOut.value
        if (result < 0 || settings.isNullPointer()) return

        settings.call(SETTINGS_PUT_IS_SCRIPT_ENABLED, 1)
        settings.call(SETTINGS_PUT_IS_WEB_MESSAGE_ENABLED, 1)
        settings.call(SETTINGS_PUT_ARE_DEFAULT_SCRIPT_DIALOGS_ENABLED, 0)
        settings.call(SETTINGS_PUT_IS_STATUS_BAR_ENABLED, 0)
        settings.call(SETTINGS_PUT_ARE_DEFAULT_CONTEXT_MENUS_ENABLED, 0)
        settings.releaseIfPresent()
    }

    private fun installEventHandlers(currentWebView: Pointer) {
        addEventHandler(
            currentWebView,
            CORE_WEBVIEW2_ADD_NAVIGATION_STARTING,
            IID_CORE_WEBVIEW2_NAVIGATION_STARTING_EVENT_HANDLER,
        ) { _, args ->
            webView2Log("NavigationStarting")
            if (hostInstalled && !args.isNullPointer()) {
                args.call(NAVIGATION_STARTING_ARGS_PUT_CANCEL, 1)
            }
            S_OK
        }

        addEventHandler(
            currentWebView,
            CORE_WEBVIEW2_ADD_NAVIGATION_COMPLETED,
            IID_CORE_WEBVIEW2_NAVIGATION_COMPLETED_EVENT_HANDLER,
        ) { _, args ->
            val success = IntByReference()
            val result = if (args.isNullPointer()) {
                E_POINTER
            } else {
                args.call(NAVIGATION_COMPLETED_ARGS_GET_IS_SUCCESS, success)
            }
            webView2Log(
                "NavigationCompleted result=${result.toHexHResult()}, success=${success.value}",
            )
            S_OK
        }

        addEventHandler(
            currentWebView,
            CORE_WEBVIEW2_ADD_NEW_WINDOW_REQUESTED,
            IID_CORE_WEBVIEW2_NEW_WINDOW_REQUESTED_EVENT_HANDLER,
        ) { _, args ->
            if (!args.isNullPointer()) {
                args.call(NEW_WINDOW_REQUESTED_ARGS_PUT_HANDLED, 1)
            }
            S_OK
        }

        val installedResourceFilters = KODIK_WEB_RESOURCE_FILTERS.count { pattern ->
            currentWebView.call(
                CORE_WEBVIEW2_ADD_WEB_RESOURCE_REQUESTED_FILTER,
                WString(pattern),
                CORE_WEBVIEW2_WEB_RESOURCE_CONTEXT_ALL,
            ) >= 0
        }
        if (installedResourceFilters > 0) {
            addEventHandler(
                currentWebView,
                CORE_WEBVIEW2_ADD_WEB_RESOURCE_REQUESTED,
                IID_CORE_WEBVIEW2_WEB_RESOURCE_REQUESTED_EVENT_HANDLER,
            ) { _, args ->
                if (!args.isNullPointer()) {
                    handleWebResourceRequested(args)
                }
                S_OK
            }
        } else {
            webView2Log(
                "Kodik ad request filters unavailable",
            )
        }

        addEventHandler(
            currentWebView,
            CORE_WEBVIEW2_ADD_WEB_MESSAGE_RECEIVED,
            IID_CORE_WEBVIEW2_WEB_MESSAGE_RECEIVED_EVENT_HANDLER,
        ) { _, args ->
            if (!args.isNullPointer()) {
                handleWebMessage(args)
            }
            S_OK
        }
    }

    private fun handleWebResourceRequested(args: Pointer) {
        if (!isKodikUrl(activeUrl.orEmpty())) return

        val requestOut = PointerByReference()
        val requestResult = args.call(
            WEB_RESOURCE_REQUESTED_ARGS_GET_REQUEST,
            requestOut,
        )
        val request = requestOut.value
        if (requestResult < 0 || request.isNullPointer()) return

        val requestUri = try {
            val uriOut = PointerByReference()
            val uriResult = request.call(WEB_RESOURCE_REQUEST_GET_URI, uriOut)
            val uriPointer = uriOut.value
            if (uriResult < 0 || uriPointer.isNullPointer()) {
                null
            } else {
                try {
                    uriPointer.getWideString(0)
                } finally {
                    Ole32.INSTANCE.CoTaskMemFree(uriPointer)
                }
            }
        } finally {
            request.releaseIfPresent()
        }
        if (requestUri == null || !shouldBlockKodikRequest(requestUri)) return

        val currentEnvironment = environment ?: return
        val responseOut = PointerByReference()
        val responseResult = currentEnvironment.call(
            ENVIRONMENT_CREATE_WEB_RESOURCE_RESPONSE,
            Pointer.NULL,
            204,
            WString("Blocked"),
            WString("Content-Type: text/plain\r\nCache-Control: no-store\r\n"),
            responseOut,
        )
        val response = responseOut.value
        if (responseResult < 0 || response.isNullPointer()) return

        try {
            args.call(WEB_RESOURCE_REQUESTED_ARGS_PUT_RESPONSE, response)
            webView2Log("blocked Kodik advertising request: ${requestUri.redactedHostPath()}")
        } finally {
            response.releaseIfPresent()
        }
    }

    private fun addEventHandler(
        owner: Pointer,
        methodIndex: Int,
        interfaceId: String,
        callback: (Pointer, Pointer) -> Int,
    ) {
        val handler = EventHandler(interfaceId, callback).retain()
        val token = LongByReference()
        val result = owner.call(methodIndex, handler.pointer, token)
        webView2Log(
            "event $methodIndex registered (${result.toHexHResult()}, token=${token.value})",
        )
        if (result < 0) {
            fail("Не удалось подключить событие WebView2: ${result.toHexHResult()}")
        }
    }

    private fun navigateCore(currentWebView: Pointer, url: String) {
        val validUrl = runCatching { URI(url) }
            .getOrNull()
            ?.takeIf { it.scheme == "https" || it.scheme == "http" }
            ?.toASCIIString()

        if (validUrl == null) {
            fail("Источник вернул некорректную ссылку на плеер.")
            return
        }
        val result = if (
            isAllohaUrl(validUrl) &&
            environment2 != null &&
            webView2 != null
        ) {
            navigateWithHeaders(
                url = validUrl,
                headers = "Referer: $ALLOHA_ORIGIN\r\n",
            )
        } else {
            currentWebView.call(CORE_WEBVIEW2_NAVIGATE, WString(validUrl))
        }

        if (result < 0) {
            fail("WebView2 не смог открыть страницу плеера: ${result.toHexHResult()}")
        } else {
            webView2Log("navigation started: $validUrl")
        }
    }

    private fun navigateWithHeaders(url: String, headers: String): Int {
        val currentEnvironment2 = environment2 ?: return E_NOINTERFACE
        val currentWebView2 = webView2 ?: return E_NOINTERFACE
        val requestOut = PointerByReference()
        val requestResult = currentEnvironment2.call(
            ENVIRONMENT2_CREATE_WEB_RESOURCE_REQUEST,
            WString(url),
            WString("GET"),
            Pointer.NULL,
            WString(headers),
            requestOut,
        )
        val request = requestOut.value
        if (requestResult < 0 || request.isNullPointer()) return requestResult

        return try {
            currentWebView2.call(
                CORE_WEBVIEW2_2_NAVIGATE_WITH_WEB_RESOURCE_REQUEST,
                request,
            )
        } finally {
            request.releaseIfPresent()
        }
    }

    private fun preparePlayerNavigation(
        currentWebView: Pointer,
        url: String,
        chrome: EmbeddedPlayerChrome,
    ) {
        val generation = ++navigationGeneration
        hostInstalling = true
        removeInitializationScript(currentWebView)

        val handler = CompletionHandler(
            interfaceId = IID_CORE_WEBVIEW2_ADD_SCRIPT_COMPLETED_HANDLER,
        ) { errorCode, scriptIdPointer ->
            val scriptId = if (scriptIdPointer.isNullPointer()) {
                null
            } else {
                scriptIdPointer.getWideString(0)
            }
            if (closed.get() || generation != navigationGeneration) {
                scriptId?.let { staleId ->
                    currentWebView.call(
                        CORE_WEBVIEW2_REMOVE_SCRIPT_ON_DOCUMENT_CREATED,
                        WString(staleId),
                    )
                }
                return@CompletionHandler
            }

            if (errorCode < 0 || scriptId.isNullOrBlank()) {
                hostInstalling = false
                fail(
                    "WebView2 не подготовил интерфейс плеера: ${errorCode.toHexHResult()}",
                )
                return@CompletionHandler
            }

            initializationScriptId = scriptId
            webView2Log("document-created script registered")
            // Keep requests serialized: leave the AddScript completion
            // callback before starting the provider navigation.
            post {
                if (generation == navigationGeneration && webView === currentWebView) {
                    navigateCore(currentWebView, url)
                }
            }
        }.retain()

        val result = currentWebView.call(
            CORE_WEBVIEW2_ADD_SCRIPT_ON_DOCUMENT_CREATED,
            WString(playerDocumentCreatedScript(url, chrome)),
            handler.pointer,
        )
        if (result < 0) {
            hostInstalling = false
            fail("WebView2 не принял интерфейс плеера: ${result.toHexHResult()}")
        }
    }

    private fun removeInitializationScript(currentWebView: Pointer) {
        val scriptId = initializationScriptId ?: return
        val result = currentWebView.call(
            CORE_WEBVIEW2_REMOVE_SCRIPT_ON_DOCUMENT_CREATED,
            WString(scriptId),
        )
        webView2Log("previous document-created script removed (${result.toHexHResult()})")
        initializationScriptId = null
    }

    private fun executeScript(
        script: String,
        completion: ((Int, String?) -> Unit)? = null,
    ) {
        val currentWebView = webView ?: return
        val handler = CompletionHandler(
            interfaceId = IID_CORE_WEBVIEW2_EXECUTE_SCRIPT_COMPLETED_HANDLER,
        ) { errorCode, result ->
            val resultJson = if (result.isNullPointer()) null else result.getWideString(0)
            completion?.invoke(errorCode, resultJson)
        }.retain()
        val result = currentWebView.call(
            CORE_WEBVIEW2_EXECUTE_SCRIPT,
            WString(script),
            handler.pointer,
        )
        if (result < 0) {
            completion?.invoke(result, null)
        }
    }

    private fun handleWebMessage(args: Pointer) {
        val messageOut = PointerByReference()
        val result = args.call(
            WEB_MESSAGE_RECEIVED_ARGS_GET_MESSAGE_AS_JSON,
            messageOut,
        )
        val rawMessage = messageOut.value
        if (result < 0 || rawMessage.isNullPointer()) return

        val json = try {
            rawMessage.getWideString(0)
        } finally {
            Ole32.INSTANCE.CoTaskMemFree(rawMessage)
        }
        val message = runCatching {
            Json.decodeFromString<String>(json)
        }.getOrNull() ?: return

        when (message) {
            "__hoshira_ready__" -> {
                if (!hostInstalled) {
                    hostInstalling = false
                    hostInstalled = true
                    webView2Log("player host installed")
                    resizeCore()
                    onStateChange(EmbeddedPlayerState.Ready)
                }
            }

            "back" -> onAction(EmbeddedPlayerAction.Back)
            "previous" -> onAction(EmbeddedPlayerAction.Previous)
            "next" -> onAction(EmbeddedPlayerAction.Next)
            "fullscreen:true" -> onAction(EmbeddedPlayerAction.SetFullscreen(true))
            "fullscreen:false" -> onAction(EmbeddedPlayerAction.SetFullscreen(false))
            else -> when {
                message.startsWith("source:") -> message
                    .removePrefix("source:")
                    .takeIf(String::isNotBlank)
                    ?.let { onAction(EmbeddedPlayerAction.SelectSource(it)) }
                message.startsWith("playback:") -> {
                    val values = message.removePrefix("playback:").split(":", limit = 4)
                    if (values.size == 4) {
                        val position = values[0].toDoubleOrNull()
                        val duration = values[1].toDoubleOrNull()
                        val volume = values[2].toFloatOrNull()
                        if (position != null && duration != null && volume != null) {
                            onAction(
                                EmbeddedPlayerAction.Playback(
                                    positionSeconds = position,
                                    durationSeconds = duration,
                                    volume = volume,
                                    quality = URLDecoder.decode(
                                        values[3],
                                        StandardCharsets.UTF_8,
                                    ).takeIf(String::isNotBlank),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun fail(message: String) {
        webView2Log("failure: $message")
        if (!closed.get()) {
            onStateChange(EmbeddedPlayerState.Failed(message))
        }
    }

    private fun <T : ComHandler> T.retain(): T = also(handlers::add)
}

private object WebView2NativeRuntime {
    private const val DISABLE_GPU_ARGUMENTS = "--disable-gpu --disable-gpu-compositing"

    fun configureHardwareAcceleration() {
        if (!UserDataStore().snapshot().preferences.hardwareAcceleration) {
            Kernel32.INSTANCE.SetEnvironmentVariable(
                "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS",
                DISABLE_GPU_ARGUMENTS,
            )
        }
    }

    val userDataDirectory: File by lazy {
        platformCacheDirectory().resolve("browser/webview2").toFile().apply {
            mkdirs()
        }
    }

    val loader: WebView2Loader by lazy {
        val runtimeDirectory = platformCacheDirectory()
            .resolve("native/webview2/x64")
            .toFile()
            .apply {
            mkdirs()
        }
        val loaderFile = File(runtimeDirectory, "WebView2Loader.dll")
        val loaderResource = checkNotNull(
            WebView2NativeRuntime::class.java.getResourceAsStream(
                "/native/windows-x64/WebView2Loader.dll",
            ),
        ) {
            "В сборке отсутствует WebView2Loader.dll"
        }

        loaderResource.use { input ->
            if (!loaderFile.isFile || loaderFile.length() == 0L) {
                Files.copy(
                    input,
                    loaderFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
        Native.load(loaderFile.absolutePath, WebView2Loader::class.java)
    }

    private fun localAppDataDirectory(): File =
        System.getenv("LOCALAPPDATA")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File(System.getProperty("user.home"), "AppData/Local")
}

private interface WebView2Loader : StdCallLibrary {
    fun CreateCoreWebView2EnvironmentWithOptions(
        browserExecutableFolder: WString?,
        userDataFolder: WString?,
        environmentOptions: Pointer?,
        environmentCreatedHandler: Pointer,
    ): Int
}

private abstract class ComHandler(
    interfaceId: String,
    @Suppress("unused")
    private val invokeFunction: StdCallLibrary.StdCallCallback,
) {
    private val references = AtomicInteger(1)
    private val vtable = Memory((Native.POINTER_SIZE * 4).toLong())
    private val instance = Memory(Native.POINTER_SIZE.toLong())
    private val supportedInterfaceId = Guid.GUID(interfaceId).apply { write() }
    private val unknownInterfaceId = Guid.GUID(IID_IUNKNOWN).apply { write() }

    @Suppress("unused")
    private val queryInterface = QueryInterfaceCallback { _, requestedInterface, objectOut ->
        if (objectOut.isNullPointer()) {
            E_POINTER
        } else if (
            requestedInterface.matchesGuid(unknownInterfaceId) ||
            requestedInterface.matchesGuid(supportedInterfaceId)
        ) {
            webView2Log("callback $interfaceId QueryInterface accepted")
            objectOut.setPointer(0, instance)
            references.incrementAndGet()
            S_OK
        } else {
            webView2Log(
                "callback $interfaceId QueryInterface rejected " +
                    requestedInterface.guidBytesForLog(),
            )
            objectOut.setPointer(0, Pointer.NULL)
            E_NOINTERFACE
        }
    }

    @Suppress("unused")
    private val addRef = ReferenceCallback {
        references.incrementAndGet()
    }

    @Suppress("unused")
    private val release = ReferenceCallback {
        references.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    val pointer: Pointer
        get() = instance

    init {
        vtable.setPointer(
            Native.POINTER_SIZE * 0L,
            CallbackReference.getFunctionPointer(queryInterface),
        )
        vtable.setPointer(
            Native.POINTER_SIZE * 1L,
            CallbackReference.getFunctionPointer(addRef),
        )
        vtable.setPointer(
            Native.POINTER_SIZE * 2L,
            CallbackReference.getFunctionPointer(release),
        )
        vtable.setPointer(
            Native.POINTER_SIZE * 3L,
            CallbackReference.getFunctionPointer(invokeFunction),
        )
        instance.setPointer(0, vtable)
    }
}

private class CompletionHandler(
    interfaceId: String,
    callback: (Int, Pointer) -> Unit,
) : ComHandler(
    interfaceId,
    CompletionCallback { _, errorCode, result ->
        webView2Log("completion $interfaceId invoked (${errorCode.toHexHResult()})")
        callback(errorCode, result)
        S_OK
    },
)

private class EventHandler(
    interfaceId: String,
    callback: (Pointer, Pointer) -> Int,
) : ComHandler(
    interfaceId,
    EventCallback { _, sender, args ->
        webView2Log("event $interfaceId invoked")
        callback(sender, args)
    },
)

private fun interface QueryInterfaceCallback : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, interfaceId: Pointer, objectOut: Pointer): Int
}

private fun interface ReferenceCallback : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer): Int
}

private fun interface CompletionCallback : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, errorCode: Int, result: Pointer): Int
}

private fun interface EventCallback : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, sender: Pointer, args: Pointer): Int
}

@Structure.FieldOrder("left", "top", "right", "bottom")
class WebViewRect(
    @JvmField var left: Int = 0,
    @JvmField var top: Int = 0,
    @JvmField var right: Int = 0,
    @JvmField var bottom: Int = 0,
) : Structure(), Structure.ByValue

@Structure.FieldOrder("a", "r", "g", "b")
class CoreWebView2Color(
    @JvmField var a: Byte = 0,
    @JvmField var r: Byte = 0,
    @JvmField var g: Byte = 0,
    @JvmField var b: Byte = 0,
) : Structure(), Structure.ByValue

private fun Pointer.call(methodIndex: Int, vararg arguments: Any?): Int {
    val vtable = getPointer(0)
    val functionPointer = vtable.getPointer(methodIndex.toLong() * Native.POINTER_SIZE)
    val function = Function.getFunction(functionPointer, Function.ALT_CONVENTION)
    return function.invokeInt(arrayOf(this, *arguments))
}

private fun Pointer.queryInterface(interfaceId: String): Pointer? {
    val guid = Guid.GUID(interfaceId).apply { write() }
    val result = PointerByReference()
    val status = call(IUNKNOWN_QUERY_INTERFACE, guid.pointer, result)
    return result.value?.takeUnless(Pointer::isNullPointer).takeIf { status >= 0 }
}

private fun Pointer?.matchesGuid(expected: Guid.GUID): Boolean {
    val actual = this?.takeUnless(Pointer::isNullPointer) ?: return false
    return actual.getByteArray(0, GUID_SIZE_BYTES).contentEquals(
        expected.pointer.getByteArray(0, GUID_SIZE_BYTES),
    )
}

private fun Pointer?.guidBytesForLog(): String {
    val actual = this?.takeUnless(Pointer::isNullPointer) ?: return "<null>"
    return actual
        .getByteArray(0, GUID_SIZE_BYTES)
        .joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xFF) }
}

private fun Pointer?.releaseIfPresent() {
    this?.takeUnless(Pointer::isNullPointer)?.call(IUNKNOWN_RELEASE)
}

private fun Pointer?.addRefIfPresent() {
    this?.takeUnless(Pointer::isNullPointer)?.call(IUNKNOWN_ADD_REF)
}

private fun Pointer?.isNullPointer(): Boolean =
    this == null || Pointer.nativeValue(this) == 0L

private fun HWND?.isNullWindowHandle(): Boolean =
    this == null || Pointer.nativeValue(pointer) == 0L

private fun isAllohaUrl(url: String): Boolean =
    runCatching { URI(url).host.equals("alloha.yani.tv", ignoreCase = true) }
        .getOrDefault(false)

private fun isKodikUrl(url: String): Boolean =
    runCatching {
        val host = URI(url).host.orEmpty()
        host.equals("kodikplayer.com", ignoreCase = true) ||
            host.endsWith(".kodikplayer.com", ignoreCase = true)
    }.getOrDefault(false)

private fun String.redactedHostPath(): String =
    runCatching {
        URI(this).let { uri ->
            "${uri.host.orEmpty()}${uri.path.orEmpty()}"
        }
    }.getOrDefault("advertising resource")

private fun runOnAwt(block: () -> Unit) {
    if (EventQueue.isDispatchThread()) {
        block()
    } else {
        EventQueue.invokeLater(block)
    }
}

private fun Throwable.toDirectWebView2Message(): String {
    val details = message.orEmpty()
    return when {
        details.contains("WebView2", ignoreCase = true) ||
            details.contains("Edge", ignoreCase = true) ->
            "Не удалось запустить Microsoft Edge WebView2. Установите или обновите WebView2 Runtime."

        else -> "Не удалось запустить встроенный плеер: ${details.ifBlank { "неизвестная ошибка" }}"
    }
}

private fun playerDocumentCreatedScript(
    playerUrl: String,
    chrome: EmbeddedPlayerChrome,
): String {
    val installer = playerHostScript(playerUrl, chrome)
        .removePrefix("return ")
    return """
        (() => {
          if (window !== window.top) return;
          let installed = false;
          const install = () => {
            if (installed) return;
            if (!document.head || !document.body) {
              window.setTimeout(install, 16);
              return;
            }
            installed = true;
            window.stop();
            $installer
          };
          document.addEventListener('DOMContentLoaded', install, { once: true });
          window.setTimeout(install, 64);
        })();
    """.trimIndent()
}

private fun Int.toHexHResult(): String =
    "0x${toUInt().toString(16).uppercase().padStart(8, '0')}"

private fun webView2Log(message: String) {
    if (java.lang.Boolean.getBoolean("hoshira.webview2.debug")) {
        System.err.println("[Hoshira WebView2] $message")
    }
}

private const val ALLOHA_ORIGIN = "https://alloha.yani.tv/"
private const val PLAYER_LOADING_LABEL = "Загружаем плеер…"
private val PLAYER_LOADING_BACKGROUND = Color(0x09, 0x0A, 0x0C)
private val PLAYER_LOADING_TRACK = Color(0xFF, 0x4D, 0x00, 52)
private val PLAYER_LOADING_ACCENT = Color(0xFF, 0x4D, 0x00)
private val PLAYER_LOADING_TEXT = Color(0xAA, 0xAE, 0xB6)

private const val S_OK = 0
private const val E_POINTER = -2147467261
private const val E_NOINTERFACE = -2147467262
private const val RPC_E_CHANGED_MODE = -2147417850

private const val WS_CHILD = 0x40000000
private const val WS_CLIPCHILDREN = 0x02000000
private const val WS_CLIPSIBLINGS = 0x04000000
private const val SW_HIDE = 0
private const val SW_SHOWNA = 8
private const val WEBVIEW_TASK_MESSAGE = 0x8000 + 72
private const val GUID_SIZE_BYTES = 16

private const val IUNKNOWN_QUERY_INTERFACE = 0
private const val IUNKNOWN_ADD_REF = 1
private const val IUNKNOWN_RELEASE = 2

private const val ENVIRONMENT_CREATE_CONTROLLER = 3
private const val ENVIRONMENT_CREATE_WEB_RESOURCE_RESPONSE = 4
private const val ENVIRONMENT2_CREATE_WEB_RESOURCE_REQUEST = 8

private const val HOST_CONTROLLER_PUT_IS_VISIBLE = 4
private const val HOST_CONTROLLER_PUT_BOUNDS = 6
private const val HOST_CONTROLLER_NOTIFY_PARENT_POSITION_CHANGED = 23
private const val HOST_CONTROLLER_CLOSE = 24
private const val HOST_CONTROLLER_GET_CORE_WEBVIEW2 = 25
private const val HOST_CONTROLLER2_PUT_DEFAULT_BACKGROUND_COLOR = 27

private const val CORE_WEBVIEW2_GET_SETTINGS = 3
private const val CORE_WEBVIEW2_NAVIGATE = 5
private const val CORE_WEBVIEW2_ADD_NAVIGATION_STARTING = 7
private const val CORE_WEBVIEW2_ADD_NAVIGATION_COMPLETED = 15
private const val CORE_WEBVIEW2_ADD_SCRIPT_ON_DOCUMENT_CREATED = 27
private const val CORE_WEBVIEW2_REMOVE_SCRIPT_ON_DOCUMENT_CREATED = 28
private const val CORE_WEBVIEW2_EXECUTE_SCRIPT = 29
private const val CORE_WEBVIEW2_ADD_WEB_MESSAGE_RECEIVED = 34
private const val CORE_WEBVIEW2_ADD_NEW_WINDOW_REQUESTED = 44
private const val CORE_WEBVIEW2_ADD_WEB_RESOURCE_REQUESTED = 55
private const val CORE_WEBVIEW2_ADD_WEB_RESOURCE_REQUESTED_FILTER = 57
private const val CORE_WEBVIEW2_2_NAVIGATE_WITH_WEB_RESOURCE_REQUEST = 63

private const val CORE_WEBVIEW2_WEB_RESOURCE_CONTEXT_ALL = 0

private const val SETTINGS_PUT_IS_SCRIPT_ENABLED = 4
private const val SETTINGS_PUT_IS_WEB_MESSAGE_ENABLED = 6
private const val SETTINGS_PUT_ARE_DEFAULT_SCRIPT_DIALOGS_ENABLED = 8
private const val SETTINGS_PUT_IS_STATUS_BAR_ENABLED = 10
private const val SETTINGS_PUT_ARE_DEFAULT_CONTEXT_MENUS_ENABLED = 14

private const val NAVIGATION_STARTING_ARGS_PUT_CANCEL = 8
private const val NAVIGATION_COMPLETED_ARGS_GET_IS_SUCCESS = 3
private const val NEW_WINDOW_REQUESTED_ARGS_PUT_HANDLED = 6
private const val WEB_MESSAGE_RECEIVED_ARGS_GET_MESSAGE_AS_JSON = 4
private const val WEB_RESOURCE_REQUESTED_ARGS_GET_REQUEST = 3
private const val WEB_RESOURCE_REQUESTED_ARGS_PUT_RESPONSE = 5
private const val WEB_RESOURCE_REQUEST_GET_URI = 3

private const val IID_CORE_WEBVIEW2_ENVIRONMENT_2 =
    "{41F3632B-5EF4-404F-AD82-2D606C5A9A21}"
private const val IID_CORE_WEBVIEW2_CONTROLLER_2 =
    "{C979903E-D4CA-4228-92EB-47EE3FA96EAB}"
private const val IID_CORE_WEBVIEW2_2 =
    "{9E8F0CF8-E670-4B5E-B2BC-73E061E3184C}"
private const val IID_IUNKNOWN =
    "{00000000-0000-0000-C000-000000000046}"
private const val IID_CORE_WEBVIEW2_CREATE_ENVIRONMENT_COMPLETED_HANDLER =
    "{4E8A3389-C9D8-4BD2-B6B5-124FEE6CC14D}"
private const val IID_CORE_WEBVIEW2_CREATE_CONTROLLER_COMPLETED_HANDLER =
    "{6C4819F3-C9B7-4260-8127-C9F5BDE7F68C}"
private const val IID_CORE_WEBVIEW2_NAVIGATION_STARTING_EVENT_HANDLER =
    "{9ADBE429-F36D-432B-9DDC-F8881FBD76E3}"
private const val IID_CORE_WEBVIEW2_NAVIGATION_COMPLETED_EVENT_HANDLER =
    "{D33A35BF-1C49-4F98-93AB-006E0533FE1C}"
private const val IID_CORE_WEBVIEW2_NEW_WINDOW_REQUESTED_EVENT_HANDLER =
    "{D4C185FE-C81C-4989-97AF-2D3FA7AB5651}"
private const val IID_CORE_WEBVIEW2_WEB_MESSAGE_RECEIVED_EVENT_HANDLER =
    "{57213F19-00E6-49FA-8E07-898EA01ECBD2}"
private const val IID_CORE_WEBVIEW2_WEB_RESOURCE_REQUESTED_EVENT_HANDLER =
    "{AB00B74C-15F1-4646-80E8-E76341D25D71}"
private const val IID_CORE_WEBVIEW2_EXECUTE_SCRIPT_COMPLETED_HANDLER =
    "{49511172-CC67-4BCA-9923-137112F4C4CC}"
private const val IID_CORE_WEBVIEW2_ADD_SCRIPT_COMPLETED_HANDLER =
    "{B99369F3-9B11-47B5-BC6F-8E7895FCEA17}"
