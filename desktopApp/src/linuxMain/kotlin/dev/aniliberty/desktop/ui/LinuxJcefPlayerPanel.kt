package dev.aniliberty.desktop.ui

import dev.aniliberty.desktop.platformCacheDirectory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.EventQueue
import java.awt.Panel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JLabel
import javax.swing.SwingConstants
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest

/**
 * Chromium host used by the Linux desktop build.
 *
 * The public class name intentionally matches the Windows host so the Compose
 * player screen remains platform-neutral. JCEF is initialized away from AWT's
 * event thread because the first launch may need to unpack its native bundle.
 */
internal class NativeWebView2PlayerPanel(
    initialUrl: String,
    initialChrome: EmbeddedPlayerChrome,
    onStateChange: (EmbeddedPlayerState) -> Unit,
    onAction: (EmbeddedPlayerAction) -> Unit,
) : Panel(BorderLayout()) {
    private val disposed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    @Volatile
    private var requestedUrl = initialUrl

    @Volatile
    private var requestedChrome = initialChrome

    @Volatile
    private var stateCallback = onStateChange

    @Volatile
    private var actionCallback = onAction

    @Volatile
    private var activeUrl: String? = null

    @Volatile
    private var activeChrome: EmbeddedPlayerChrome? = null

    @Volatile
    private var client: CefClient? = null

    @Volatile
    private var browser: CefBrowser? = null

    init {
        background = Color.BLACK
        add(
            JLabel("Подготавливаем браузерный движок…", SwingConstants.CENTER).apply {
                foreground = Color(0xAA, 0xAE, 0xB6)
                background = Color.BLACK
                isOpaque = true
            },
            BorderLayout.CENTER,
        )
        start()
    }

    fun update(
        url: String,
        chrome: EmbeddedPlayerChrome,
        onStateChange: (EmbeddedPlayerState) -> Unit,
        onAction: (EmbeddedPlayerAction) -> Unit,
    ) {
        requestedUrl = url
        requestedChrome = chrome
        stateCallback = onStateChange
        actionCallback = onAction
        val currentBrowser = browser ?: return
        if (activeUrl != url || activeChrome != chrome) {
            stateCallback(EmbeddedPlayerState.Starting)
            navigate(currentBrowser, url, chrome)
        }
    }

    fun disposePlayer() {
        if (!disposed.compareAndSet(false, true)) return
        val currentBrowser = browser
        val currentClient = client
        browser = null
        client = null
        runCatching { currentBrowser?.close(true) }
        runCatching { currentClient?.dispose() }
        EventQueue.invokeLater {
            removeAll()
            revalidate()
            repaint()
        }
    }

    private fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread(
            {
                runCatching {
                    val app = LinuxJcefRuntime.app()
                    if (disposed.get()) return@runCatching
                    val createdClient = app.createClient()
                    configureClient(createdClient)
                    val createdBrowser = createdClient.createBrowser(
                        "about:blank",
                        false,
                        false,
                    )
                    client = createdClient
                    browser = createdBrowser
                    EventQueue.invokeLater {
                        if (disposed.get()) {
                            createdBrowser.close(true)
                            createdClient.dispose()
                            return@invokeLater
                        }
                        removeAll()
                        add(createdBrowser.uiComponent, BorderLayout.CENTER)
                        revalidate()
                        repaint()
                        navigate(
                            createdBrowser,
                            requestedUrl,
                            requestedChrome,
                        )
                    }
                }.onFailure { error ->
                    notifyFailure(
                        "Не удалось запустить Chromium для Linux: " +
                            error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                    )
                }
            },
            "hoshira-linux-jcef",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun configureClient(createdClient: CefClient) {
        val messageRouter = CefMessageRouter.create()
        messageRouter.addHandler(
            object : CefMessageRouterHandlerAdapter() {
                override fun onQuery(
                    browser: CefBrowser,
                    frame: CefFrame,
                    queryId: Long,
                    request: String,
                    persistent: Boolean,
                    callback: CefQueryCallback,
                ): Boolean {
                    handlePlayerMessage(request)
                    callback.success("")
                    return true
                }
            },
            true,
        )
        createdClient.addMessageRouter(messageRouter)

        createdClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadStart(
                    browser: CefBrowser,
                    frame: CefFrame,
                    transitionType: CefRequest.TransitionType,
                ) {
                    if (!frame.isMain) return
                    installPlayerHost(browser)
                }

                override fun onLoadEnd(
                    browser: CefBrowser,
                    frame: CefFrame,
                    httpStatusCode: Int,
                ) {
                    if (!frame.isMain) return
                    installPlayerHost(browser)
                }

                override fun onLoadError(
                    browser: CefBrowser,
                    frame: CefFrame,
                    errorCode: CefLoadHandler.ErrorCode,
                    errorText: String,
                    failedUrl: String,
                ) {
                    if (!frame.isMain || errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) {
                        return
                    }
                    notifyFailure(
                        "Источник не загрузился ($errorCode): " +
                            errorText.ifBlank { failedUrl },
                    )
                }
            },
        )

        createdClient.addLifeSpanHandler(
            object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    browser: CefBrowser,
                    frame: CefFrame,
                    targetUrl: String,
                    targetFrameName: String,
                ): Boolean = true
            },
        )

        val resourcePolicy = object : CefResourceRequestHandlerAdapter() {
            override fun onBeforeResourceLoad(
                browser: CefBrowser,
                frame: CefFrame,
                request: CefRequest,
            ): Boolean {
                val currentUrl = activeUrl.orEmpty()
                return isKodikUrl(currentUrl) &&
                    shouldBlockKodikRequest(request.url.orEmpty())
            }
        }
        createdClient.addRequestHandler(
            object : CefRequestHandlerAdapter() {
                override fun getResourceRequestHandler(
                    browser: CefBrowser,
                    frame: CefFrame,
                    request: CefRequest,
                    isNavigation: Boolean,
                    isDownload: Boolean,
                    requestInitiator: String,
                    disableDefaultHandling: BoolRef,
                ): CefResourceRequestHandler = resourcePolicy
            },
        )
    }

    private fun navigate(
        currentBrowser: CefBrowser,
        url: String,
        chrome: EmbeddedPlayerChrome,
    ) {
        if (disposed.get()) return
        val normalizedUrl = url.takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        } ?: run {
            notifyFailure("Источник вернул некорректную ссылку на плеер.")
            return
        }

        activeUrl = normalizedUrl
        activeChrome = chrome
        if (isAllohaUrl(normalizedUrl)) {
            val request = CefRequest.create()
            request.url = normalizedUrl
            request.method = "GET"
            request.setHeaderMap(
                hashMapOf(
                    "Referer" to ALLOHA_ORIGIN,
                    "Origin" to ALLOHA_ORIGIN.removeSuffix("/"),
                ),
            )
            currentBrowser.loadRequest(request)
        } else {
            currentBrowser.loadURL(normalizedUrl)
        }
    }

    private fun installPlayerHost(currentBrowser: CefBrowser) {
        val url = activeUrl ?: return
        val chrome = activeChrome ?: return
        currentBrowser.executeJavaScript(
            linuxPlayerDocumentScript(url, chrome),
            currentBrowser.url,
            0,
        )
    }

    private fun handlePlayerMessage(message: String) {
        when (message) {
            "__hoshira_ready__" -> notifyState(EmbeddedPlayerState.Ready)
            "back" -> actionCallback(EmbeddedPlayerAction.Back)
            "previous" -> actionCallback(EmbeddedPlayerAction.Previous)
            "next" -> actionCallback(EmbeddedPlayerAction.Next)
            else -> message
                .removePrefix("source:")
                .takeIf { message.startsWith("source:") && it.isNotBlank() }
                ?.let { actionCallback(EmbeddedPlayerAction.SelectSource(it)) }
        }
    }

    private fun notifyFailure(message: String) {
        if (!disposed.get()) notifyState(EmbeddedPlayerState.Failed(message))
    }

    private fun notifyState(state: EmbeddedPlayerState) {
        EventQueue.invokeLater {
            if (!disposed.get()) stateCallback(state)
        }
    }
}

private object LinuxJcefRuntime {
    @Volatile
    private var instance: CefApp? = null

    fun app(): CefApp {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: buildApp().also { instance = it }
        }
    }

    private fun buildApp(): CefApp {
        val installDirectory = platformCacheDirectory()
            .resolve("jcef")
            .toFile()
            .apply(File::mkdirs)
        val builder = CefAppBuilder()
        builder.setInstallDir(installDirectory)
        builder.cefSettings.windowless_rendering_enabled = false
        builder.cefSettings.cache_path = platformCacheDirectory()
            .resolve("chromium")
            .toString()
        builder.addJcefArgs(
            "--autoplay-policy=no-user-gesture-required",
            "--disable-background-networking",
            "--disable-component-update",
            "--disable-default-apps",
            "--disable-features=TranslateUI",
        )
        builder.setAppHandler(
            object : MavenCefAppHandlerAdapter() {
                override fun stateHasChanged(state: CefApp.CefAppState) = Unit
            },
        )
        return builder.build()
    }
}

private fun linuxPlayerDocumentScript(
    playerUrl: String,
    chrome: EmbeddedPlayerChrome,
): String {
    val installer = playerHostScript(playerUrl, chrome)
        .removePrefix("return ")
    return """
        (() => {
          if (window !== window.top || window.__hoshiraHostInstalled) return;
          window.chrome = window.chrome || {};
          window.chrome.webview = {
            postMessage: message => window.cefQuery({
              request: String(message),
              onSuccess: () => {},
              onFailure: () => {}
            })
          };
          const install = () => {
            if (window.__hoshiraHostInstalled) return;
            if (!document.head || !document.body) {
              window.setTimeout(install, 16);
              return;
            }
            window.__hoshiraHostInstalled = true;
            window.stop();
            $installer
          };
          document.addEventListener('DOMContentLoaded', install, { once: true });
          window.setTimeout(install, 32);
        })();
    """.trimIndent()
}

private fun isAllohaUrl(url: String): Boolean =
    runCatching { java.net.URI(url).host }
        .getOrNull()
        ?.equals("alloha.yani.tv", ignoreCase = true)
        ?: false

private fun isKodikUrl(url: String): Boolean =
    runCatching { java.net.URI(url).host.orEmpty() }
        .getOrDefault("")
        .let { host ->
            host.equals("kodikplayer.com", ignoreCase = true) ||
                host.endsWith(".kodikplayer.com", ignoreCase = true)
        }

private const val ALLOHA_ORIGIN = "https://alloha.yani.tv/"
