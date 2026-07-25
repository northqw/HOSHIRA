package dev.aniliberty.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.aniliberty.desktop.data.NetworkReleaseRepository
import dev.aniliberty.desktop.ui.HoshiraApp
import kotlinx.coroutines.delay

fun main() {
    // AWT otherwise clears a newly attached heavyweight Canvas with the
    // platform window color before its first paint. On Windows that produces
    // a single white frame between the Compose loader and WebView2.
    System.setProperty("sun.awt.noerasebackground", "true")
    System.setProperty("sun.awt.erasebackgroundonresize", "false")

    application {
        val windowState = rememberWindowState(
            size = DpSize(1480.dp, 930.dp),
            position = WindowPosition(Alignment.Center),
        )
        var placementBeforeFullscreen by remember {
            mutableStateOf(WindowPlacement.Floating)
        }
        // The application controller uses the repository as its remember key.
        // Keep this instance stable across window recompositions (for example
        // when opening the native player or changing fullscreen placement),
        // otherwise the whole navigation and account state is recreated.
        val repository = remember { NetworkReleaseRepository() }

        Window(
            onCloseRequest = ::exitApplication,
            title = "Hoshira",
            state = windowState,
        ) {
            window.minimumSize = java.awt.Dimension(1080, 720)
            applyHoshiraWindowBackground(window)

            LaunchedEffect(window) {
                delay(20)
                applyHoshiraWindowStyle(window)
            }

            HoshiraApp(
                repository = repository,
                isFullscreen = windowState.placement == WindowPlacement.Fullscreen,
                onFullscreenChange = { fullscreen ->
                    if (fullscreen) {
                        if (windowState.placement != WindowPlacement.Fullscreen) {
                            placementBeforeFullscreen = windowState.placement
                            windowState.placement = WindowPlacement.Fullscreen
                        }
                    } else if (windowState.placement == WindowPlacement.Fullscreen) {
                        windowState.placement = placementBeforeFullscreen
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
