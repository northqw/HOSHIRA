package app.hoshira.android

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.hoshira.desktop.data.NetworkReleaseRepository
import app.hoshira.desktop.ui.HoshiraApp
import app.hoshira.desktop.ui.HoshiraMobileApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidEnvironment.initialize(applicationContext)
        setContent {
            val configuration = LocalConfiguration.current
            val isTablet = configuration.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP
            val repository = remember { NetworkReleaseRepository() }
            var isFullscreen by remember { mutableStateOf(false) }

            val updateFullscreen: (Boolean) -> Unit = { fullscreen ->
                if (fullscreen != isFullscreen) {
                    setPlayerFullscreen(fullscreen, isTablet)
                    isFullscreen = fullscreen
                }
            }

            if (isTablet) {
                HoshiraApp(
                    repository = repository,
                    isFullscreen = isFullscreen,
                    onFullscreenChange = updateFullscreen,
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
    }
}
