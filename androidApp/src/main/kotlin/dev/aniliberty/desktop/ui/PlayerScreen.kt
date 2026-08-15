package dev.aniliberty.desktop.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.aniliberty.android.BuildConfig
import dev.aniliberty.desktop.PlaybackSession
import dev.aniliberty.desktop.data.PlayerPreferences
import dev.aniliberty.desktop.model.EpisodeDto
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
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
    val playerUrl = session?.episode?.externalPlayerUrl
    if (session == null || playerUrl == null) {
        ErrorState(
            message = "Не удалось подготовить источник этой серии",
            onRetry = onBack,
            modifier = modifier,
        )
        return
    }

    val currentBack by rememberUpdatedState(onBack)
    val currentPlayback by rememberUpdatedState(onPlayback)
    val currentFullscreenChange by rememberUpdatedState(onFullscreenChange)
    val currentPlayEpisode by rememberUpdatedState(onPlayEpisode)
    val studioEpisodes = remember(
        session.release.id,
        session.episode.name,
        session.episode.displayPlayerName,
    ) {
        session.release.episodes
            .filter {
                it.name == session.episode.name &&
                    it.displayPlayerName == session.episode.displayPlayerName &&
                    it.externalPlayerUrl != null
            }
            .sortedBy(EpisodeDto::ordinal)
            .distinctBy(EpisodeDto::displayOrdinal)
    }
    val sourceCandidates = remember(
        session.release.id,
        session.episode.name,
        session.episode.displayOrdinal,
    ) {
        session.release.episodes
            .filter {
                it.name == session.episode.name &&
                    it.displayOrdinal == session.episode.displayOrdinal &&
                    it.externalPlayerUrl != null
            }
            .distinctBy(EpisodeDto::displayPlayerName)
            .sortedBy(EpisodeDto::displayPlayerName)
    }
    val currentIndex = studioEpisodes
        .indexOfFirst { it.id == session.episode.id }
        .takeIf { it >= 0 }
        ?: 0
    val previousEpisode = studioEpisodes.getOrNull(currentIndex - 1)
    val nextEpisode = studioEpisodes.getOrNull(currentIndex + 1)
    val currentPreviousEpisode by rememberUpdatedState(previousEpisode)
    val currentNextEpisode by rememberUpdatedState(nextEpisode)
    val currentSourceCandidates by rememberUpdatedState(sourceCandidates)
    val currentAutoplayNext by rememberUpdatedState(preferences.autoplayNext)
    val initialStartupVolume = remember(session.episode.id) { preferences.startupVolume }
    val initialPreferredQuality = remember(session.episode.id) { preferredQuality }
    var hasLoadedProvider by remember(playerUrl) { mutableStateOf(false) }
    val hostConfig = AndroidPlayerHostConfig(
        playerUrl = playerUrl,
        title = session.release.displayName,
        subtitle = listOfNotNull(session.episode.shortTitle, session.episode.name)
            .joinToString(" · "),
        position = "${currentIndex + 1} из ${studioEpisodes.size.coerceAtLeast(1)}",
        sources = sourceCandidates.map { source ->
            AndroidPlayerHostSource(
                episodeId = source.id,
                label = source.displayPlayerName,
                selected = source.id == session.episode.id,
                enabled = !source.displayPlayerName.contains("Alloha", ignoreCase = true),
            )
        },
        resumeSeconds = session.resumeSeconds,
        startupVolume = initialStartupVolume,
        preferredQuality = initialPreferredQuality,
        hasPrevious = previousEpisode != null,
        hasNext = nextEpisode != null,
        controlsHideDelayMs = preferences.controlsHideDelayMs,
        showLoading = !hasLoadedProvider,
    )

    var loading by remember(playerUrl) { mutableStateOf(true) }
    var error by remember(playerUrl) { mutableStateOf<String?>(null) }
    var playerDetected by remember(playerUrl) { mutableStateOf(false) }
    val debugStartedAt = remember(playerUrl) { SystemClock.elapsedRealtime() }
    val addDebugEvent: (String) -> Unit = { message ->
        val elapsed = SystemClock.elapsedRealtime() - debugStartedAt
        val entry = "+${elapsed}ms ${message.take(DEBUG_EVENT_MAX_LENGTH)}"
        Log.d(PLAYER_DEBUG_TAG, entry)
    }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    val chromeHolder = remember { arrayOfNulls<AndroidPlayerChromeClient>(1) }
    val webViewPackage = remember {
        WebView.getCurrentWebViewPackage()?.let { info ->
            "${info.packageName} ${info.versionName.orEmpty()}".trim()
        } ?: "не определён"
    }
    BackHandler {
        currentBack()
    }

    LaunchedEffect(playerUrl) {
        addDebugEvent("app-build=$ANDROID_BUILD_LABEL; player-host=$PLAYER_HOST_VERSION")
        addDebugEvent("open ${sanitizeUrl(playerUrl)}")
        addDebugEvent(
            "episode=${session.episode.displayOrdinal}; " +
                "dubbing=${session.episode.name.orEmpty().ifBlank { "—" }}; " +
                "source=${session.episode.displayPlayerName}",
        )
        addDebugEvent("WebView package=$webViewPackage")
        if (!isFullscreen) {
            currentFullscreenChange(true)
        }
        delay(PLAYER_DIAGNOSTIC_TIMEOUT_MS)
        if (!playerDetected) {
            loading = false
            addDebugEvent("TIMEOUT: HTML5 video не обнаружено")
            webViewHolder[0]?.let { webView ->
                webView.evaluateJavascript(PLAYER_DIAGNOSTIC_SCRIPT, null)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            chromeHolder[0]?.closeCustomView()
            webViewHolder[0]?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeJavascriptInterface(JS_BRIDGE_NAME)
                destroy()
            }
            webViewHolder[0] = null
            currentFullscreenChange(false)
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                createPlayerWebView(
                    context = context,
                    hostConfig = hostConfig,
                    onReady = {
                        loading = false
                        error = null
                    },
                    onError = { message ->
                        loading = false
                        error = message
                    },
                    onDebug = addDebugEvent,
                    onPlayerDetected = { details ->
                        if (!playerDetected) {
                            addDebugEvent("PLAYER DETECTED: $details")
                        }
                        playerDetected = true
                        loading = false
                    },
                    onProgress = {},
                    onMetrics = {},
                    onPlayback = currentPlayback,
                    onEnded = {
                        if (currentAutoplayNext) {
                            currentNextEpisode?.let(currentPlayEpisode)
                        }
                    },
                    onHostAction = { action, value ->
                        when (action) {
                            "provider-loaded" -> {
                                hasLoadedProvider = true
                                loading = false
                            }
                            "provider-error" -> {
                                loading = false
                                error = "Источник сообщил: контент не найден. Попробуйте другой плеер."
                                addDebugEvent("provider-content-error ${value.take(160)}")
                            }
                            "back" -> currentBack()
                            "previous" -> currentPreviousEpisode?.let(currentPlayEpisode)
                            "next" -> currentNextEpisode?.let(currentPlayEpisode)
                            "source" -> currentSourceCandidates
                                .firstOrNull { it.id == value }
                                ?.let(currentPlayEpisode)
                        }
                    },
                    onFullscreenChange = currentFullscreenChange,
                ).also { webView ->
                    webViewHolder[0] = webView
                    chromeHolder[0] = webView.webChromeClient as? AndroidPlayerChromeClient
                    loadPlayerHost(
                        webView = webView,
                        hostConfig = hostConfig,
                        onDebug = addDebugEvent,
                    )
                }
            },
            update = { webView ->
                val webViewState = webView.tag as? PlayerWebViewState
                if (webViewState?.url != playerUrl) {
                    loading = true
                    error = null
                    playerDetected = false
                    addDebugEvent("reload ${sanitizeUrl(playerUrl)}")
                    loadPlayerHost(
                        webView = webView,
                        hostConfig = hostConfig,
                        onDebug = addDebugEvent,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (loading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = AniColors.Orange)
                Spacer(Modifier.size(14.dp))
                Text("Загружаем плеер…", color = AniColors.TextMuted)
            }
        }

        error?.let { message ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .padding(28.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                    .background(AniColors.Surface.copy(alpha = 0.96f))
                    .padding(22.dp),
            ) {
                Text(message, color = AniColors.Text)
            }
        }

    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createPlayerWebView(
    context: Context,
    hostConfig: AndroidPlayerHostConfig,
    onReady: () -> Unit,
    onError: (String) -> Unit,
    onDebug: (String) -> Unit,
    onPlayerDetected: (String) -> Unit,
    onProgress: (Int) -> Unit,
    onMetrics: (String) -> Unit,
    onPlayback: (Double, Double, Float, String?) -> Unit,
    onEnded: () -> Unit,
    onHostAction: (String, String) -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
): WebView {
    WebView.setWebContentsDebuggingEnabled(true)
    return WebView(context).apply {
        onDebug("WebView created")
        setBackgroundColor(AndroidColor.BLACK)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = false
            loadWithOverviewMode = false
            textZoom = 100
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
        }
        setInitialScale(100)
        overScrollMode = View.OVER_SCROLL_NEVER
        val playerWebView = this
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(playerWebView, true)
        }
        addJavascriptInterface(
            AndroidPlayerBridge(
                context = context.applicationContext,
                onPlayback = onPlayback,
                onEnded = onEnded,
                onDebug = onDebug,
                onPlayerDetected = onPlayerDetected,
                onHostAction = onHostAction,
            ),
            JS_BRIDGE_NAME,
        )
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != "about:blank") {
                    onDebug("page-started ${sanitizeUrl(url)}")
                    onMetrics(collectWebViewMetrics(view))
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url != "about:blank") {
                    onDebug("page-finished ${sanitizeUrl(url)}")
                    view.evaluateJavascript(PLAYER_DIAGNOSTIC_SCRIPT, null)
                    onMetrics(collectWebViewMetrics(view))
                    onReady()
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                super.onPageCommitVisible(view, url)
                if (url != "about:blank") {
                    onDebug("page-commit-visible ${sanitizeUrl(url)}")
                    view.evaluateJavascript(PLAYER_DIAGNOSTIC_SCRIPT, null)
                    onMetrics(collectWebViewMetrics(view))
                    onReady()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                if (!request.isForMainFrame) return false
                val blocked = !isAllowedPlayerNavigation(request.url)
                onDebug(
                    "${if (blocked) "navigation-blocked" else "navigation-allowed"} " +
                        sanitizeUrl(request.url.toString()),
                )
                return blocked
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                if (request.url.path == HOSHIRA_PLAYER_FONT_PATH) {
                    return WebResourceResponse(
                        "font/ttf",
                        null,
                        200,
                        "OK",
                        mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Cache-Control" to "public, max-age=86400",
                        ),
                        context.resources.openRawResource(
                            dev.aniliberty.android.R.font.montserrat_semibold,
                        ),
                    )
                }
                if (
                    isKodikProviderUrl(
                        (view.tag as? PlayerWebViewState)?.url ?: hostConfig.playerUrl,
                    ) &&
                    shouldBlockKodikRequest(request.url.toString())
                ) {
                    onDebug("blocked Kodik advertising request ${sanitizeUrl(request.url.toString())}")
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        204,
                        "No Content",
                        mapOf(
                            "Cache-Control" to "no-store",
                            "Access-Control-Allow-Origin" to "*",
                        ),
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    onDebug(
                        "main-frame-error code=${error.errorCode} " +
                            "description=${error.description.toString().take(160)} " +
                            "url=${sanitizeUrl(request.url.toString())}",
                    )
                    onMetrics(collectWebViewMetrics(view))
                    onError("Плеер не загрузился. Проверьте подключение и попробуйте снова.")
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request.isForMainFrame) {
                    onDebug(
                        "main-frame-http status=${errorResponse.statusCode} " +
                            "reason=${errorResponse.reasonPhrase.orEmpty().take(120)} " +
                            "url=${sanitizeUrl(request.url.toString())}",
                    )
                    onMetrics(collectWebViewMetrics(view))
                    onError("Плеер вернул HTTP ${errorResponse.statusCode}. Попробуйте другой источник.")
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                onDebug(
                    "RENDERER GONE: crashed=${detail.didCrash()} " +
                        "priority=${detail.rendererPriorityAtExit()}",
                )
                onMetrics(collectWebViewMetrics(view))
                onError("Процесс Android System WebView завершился. Закройте плеер и попробуйте снова.")
                return true
            }
        }
        webChromeClient = AndroidPlayerChromeClient(
            onFullscreenChange = onFullscreenChange,
            onDebug = onDebug,
            onProgress = { progress ->
                onProgress(progress)
                onMetrics(collectWebViewMetrics(this))
            },
        )
    }
}

private class AndroidPlayerBridge(
    context: Context,
    private val onPlayback: (Double, Double, Float, String?) -> Unit,
    private val onEnded: () -> Unit,
    private val onDebug: (String) -> Unit,
    private val onPlayerDetected: (String) -> Unit,
    private val onHostAction: (String, String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @JavascriptInterface
    fun systemVolume(): Double {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val minimum = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val range = (maximum - minimum).coerceAtLeast(1)
        return ((current - minimum).toDouble() / range).coerceIn(0.0, 1.0)
    }

    @JavascriptInterface
    fun setSystemVolume(value: Double) {
        val normalized = value.coerceIn(0.0, 1.0)
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val minimum = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
        val target = minimum + (normalized * (maximum - minimum)).roundToInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    @JavascriptInterface
    fun playback(
        positionSeconds: Double,
        durationSeconds: Double,
        volume: Double,
        quality: String?,
    ) {
        mainHandler.post {
            onPlayback(
                positionSeconds.coerceAtLeast(0.0),
                durationSeconds.coerceAtLeast(0.0),
                volume.coerceIn(0.0, 1.0).toFloat(),
                quality?.takeIf(String::isNotBlank),
            )
        }
    }

    @JavascriptInterface
    fun ended() {
        mainHandler.post(onEnded)
    }

    @JavascriptInterface
    fun diagnostic(message: String) {
        mainHandler.post {
            onDebug("js: ${message.take(DEBUG_EVENT_MAX_LENGTH - 4)}")
        }
    }

    @JavascriptInterface
    fun playerDetected(width: Int, height: Int, iframeCount: Int) {
        mainHandler.post {
            onPlayerDetected("video=${width}x$height; iframes=$iframeCount")
        }
    }

    @JavascriptInterface
    fun hostAction(action: String, value: String) {
        mainHandler.post {
            onDebug("host-action=${action.take(40)}")
            onHostAction(action.take(40), value.take(200))
        }
    }
}

private class AndroidPlayerChromeClient(
    private val onFullscreenChange: (Boolean) -> Unit,
    private val onDebug: (String) -> Unit,
    private val onProgress: (Int) -> Unit,
) : WebChromeClient() {
    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        onDebug(
            "provider custom-fullscreen requested (${view.javaClass.simpleName}); " +
                "kept inline to preserve app overlay",
        )
        onFullscreenChange(true)
        callback.onCustomViewHidden()
    }

    override fun onHideCustomView() {
        onDebug("provider custom-fullscreen hidden")
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgress(newProgress)
        if (newProgress == 100 || newProgress % 25 == 0) {
            onDebug("web-progress=$newProgress")
        }
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        onDebug(
            "console ${consoleMessage.messageLevel()} line=${consoleMessage.lineNumber()}: " +
                consoleMessage.message().take(180),
        )
        return true
    }

    fun closeCustomView() = Unit
}

private data class PlayerWebViewState(
    val url: String,
)

private fun loadPlayerHost(
    webView: WebView,
    hostConfig: AndroidPlayerHostConfig,
    onDebug: (String) -> Unit,
): Unit {
    val document = androidPlayerHostDocument(hostConfig)
    val useAllohaCompatibilityMode = isAllohaProviderUrl(hostConfig.playerUrl)
    webView.settings.userAgentString = if (useAllohaCompatibilityMode) {
        ALLOHA_DESKTOP_USER_AGENT
    } else {
        WebSettings.getDefaultUserAgent(webView.context)
    }
    webView.tag = PlayerWebViewState(hostConfig.playerUrl)
    onDebug(
        if (useAllohaCompatibilityMode) {
            "user-agent=desktop Chromium compatibility mode for Alloha"
        } else {
            "user-agent=default Android WebView"
        },
    )
    onDebug(
        "host loadDataWithBaseURL; base=${sanitizeUrl(hostConfig.playerUrl)}; " +
            "documentLength=${document.length}",
    )
    webView.loadDataWithBaseURL(
        hostConfig.playerUrl,
        document,
        "text/html",
        "utf-8",
        hostConfig.playerUrl,
    )
}

private fun isAllowedPlayerNavigation(uri: Uri): Boolean {
    return uri.toString() == "about:blank" || uri.scheme.equals("https", ignoreCase = true)
}

private fun isKodikProviderUrl(url: String): Boolean {
    val host = Uri.parse(url).host.orEmpty()
    return host == "kodikplayer.com" || host.endsWith(".kodikplayer.com")
}

private fun isAllohaProviderUrl(url: String): Boolean {
    val host = Uri.parse(url).host.orEmpty()
    return host == "alloha.yani.tv" || host.endsWith(".alloha.yani.tv")
}

private fun sanitizeUrl(url: String): String {
    if (url == "about:blank") return url
    return runCatching {
        val uri = Uri.parse(url)
        buildString {
            uri.scheme?.let {
                append(it)
                append("://")
            }
            append(uri.host ?: "<no-host>")
            append(uri.path.orEmpty())
        }
    }.getOrElse { "<invalid-url>" }
}

private fun collectWebViewMetrics(webView: WebView): String {
    return "view=${webView.width}x${webView.height}px; " +
        "contentHeight=${webView.contentHeight}px; " +
        "progress=${webView.progress}; " +
        "shown=${webView.isShown}; " +
        "url=${sanitizeUrl(webView.url.orEmpty())}"
}

private val PLAYER_DIAGNOSTIC_SCRIPT = """
    (() => {
      try {
        const summary =
          'snapshot; ready=' + document.readyState +
          '; title=' + String(document.title || '').slice(0, 80) +
          '; body=' + (document.body ? document.body.childElementCount : -1) +
          '; html=' + (document.documentElement ? document.documentElement.scrollWidth + 'x' + document.documentElement.scrollHeight : 'none') +
          '; viewport=' + window.innerWidth + 'x' + window.innerHeight +
          '; iframes=' + document.querySelectorAll('iframe').length +
          '; videos=' + document.querySelectorAll('video').length +
          '; uaPlatform=' + String(navigator.userAgentData?.platform || navigator.platform || '').slice(0, 40) +
          '; uaMobile=' + String(navigator.userAgentData?.mobile ?? 'unknown');
        HoshiraAndroid.diagnostic(summary);
        return summary;
      } catch (error) {
        try { HoshiraAndroid.diagnostic('snapshot failed: ' + String(error)); } catch (_) {}
        return 'snapshot-failed';
      }
    })();
""".trimIndent()

private const val JS_BRIDGE_NAME = "HoshiraAndroid"
private const val PLAYER_DIAGNOSTIC_TIMEOUT_MS = 10_000L
private const val PLAYER_DEBUG_TAG = "HoshiraPlayer"
private const val PLAYER_HOST_VERSION = "mobile-touch-system-audio-v4"
private const val ALLOHA_DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
private val ANDROID_BUILD_LABEL =
    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
private const val DEBUG_EVENT_MAX_LENGTH = 320
