package app.hoshira.android

import android.app.ActivityManager
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.hoshira.desktop.data.NetworkReleaseRepository
import app.hoshira.desktop.data.SharedHttpClient
import app.hoshira.desktop.ui.HoshiraApp
import app.hoshira.desktop.ui.HoshiraMobileApp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toPath

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidEnvironment.initialize(applicationContext)
        val activityManager = getSystemService(ActivityManager::class.java)
        val memoryCachePercent = if (activityManager?.isLowRamDevice == true) {
            LOW_RAM_IMAGE_MEMORY_CACHE_PERCENT
        } else {
            IMAGE_MEMORY_CACHE_PERCENT
        }
        val imageCacheDirectory = applicationContext.cacheDir
            .resolve("images")
            .absolutePath
            .toPath()
        setContent {
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .components {
                        add(OkHttpNetworkFetcherFactory(callFactory = { SharedHttpClient.images }))
                    }
                    .memoryCache {
                        MemoryCache.Builder()
                            .maxSizePercent(context, memoryCachePercent)
                            .build()
                    }
                    .diskCache {
                        DiskCache.Builder()
                            .directory(imageCacheDirectory)
                            .maxSizeBytes(ANDROID_IMAGE_DISK_CACHE_BYTES)
                            .build()
                    }
                    .build()
            }
            val configuration = LocalConfiguration.current
            val isTelevision = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
                Configuration.UI_MODE_TYPE_TELEVISION
            val isTablet = configuration.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP
            val repository = remember { NetworkReleaseRepository() }
            var isFullscreen by remember { mutableStateOf(false) }

            val updateFullscreen: (Boolean) -> Unit = { fullscreen ->
                if (fullscreen != isFullscreen) {
                    setPlayerFullscreen(fullscreen, isTablet)
                    isFullscreen = fullscreen
                }
            }

            if (isTelevision || isTablet) {
                HoshiraApp(
                    repository = repository,
                    isFullscreen = isFullscreen,
                    onFullscreenChange = updateFullscreen,
                    isTelevision = isTelevision,
                    platformBackHandler = { enabled, onBack ->
                        BackHandler(enabled = enabled, onBack = onBack)
                    },
                )
            } else {
                HoshiraMobileApp(
                    repository = repository,
                    isFullscreen = isFullscreen,
                    onFullscreenChange = updateFullscreen,
                )
            }
        }
    }

    private fun setPlayerFullscreen(fullscreen: Boolean, tablet: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (!tablet) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = if (tablet) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    private companion object {
        const val TABLET_MIN_WIDTH_DP = 600
        const val IMAGE_MEMORY_CACHE_PERCENT = 0.15
        const val LOW_RAM_IMAGE_MEMORY_CACHE_PERCENT = 0.10
        const val ANDROID_IMAGE_DISK_CACHE_BYTES = 320L * 1024L * 1024L
    }
}
