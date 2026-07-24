package dev.aniliberty.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
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
                repository = NetworkReleaseRepository(),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
