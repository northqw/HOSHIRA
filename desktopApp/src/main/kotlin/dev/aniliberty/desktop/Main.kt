package dev.aniliberty.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.KeyEvent
import kotlinx.coroutines.delay

fun main() {
    System.setProperty("awt.useSystemAAFontSettings", "lcd")
    System.setProperty("swing.aatext", "true")
    // AWT otherwise clears a newly attached heavyweight Canvas with the
    // platform window color before its first paint. On Windows that produces
    // a single white frame between the Compose loader and native media surface.
    System.setProperty("sun.awt.noerasebackground", "true")
    System.setProperty("sun.awt.erasebackgroundonresize", "false")
    if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        // Compose Desktop and JavaFX would otherwise both create Direct3D
        // surfaces in the same Swing hierarchy. JFXPanel can keep playing
        // audio while its off-screen D3D surface remains black. The software
        // Prism compositor keeps the JavaFX scene, video and controls visible.
        System.setProperty(
            "prism.order",
            System.getProperty("hoshira.javafx.prism", "sw"),
        )
    }

    application {
        val windowSizing = remember {
            calculateWindowSizing(
                defaultGraphicsConfiguration().workingArea(),
            )
        }
        val windowState = rememberWindowState(
            size = windowSizing.initialSize,
            position = WindowPosition(Alignment.Center),
        )
        var placementBeforeFullscreen by remember {
            mutableStateOf(WindowPlacement.Floating)
        }
        var isFullscreen by remember {
            mutableStateOf(false)
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
            window.minimumSize = windowSizing.minimumSize
            applyHoshiraWindowBackground(window)

            LaunchedEffect(window) {
                delay(20)
                applyHoshiraWindowStyle(window)
            }

            val updateFullscreen = updateFullscreen@ { requestedFullscreen: Boolean ->
                if (requestedFullscreen == isFullscreen) {
                    return@updateFullscreen
                }

                if (requestedFullscreen) {
                    if (windowState.placement != WindowPlacement.Fullscreen) {
                        placementBeforeFullscreen = windowState.placement
                        windowState.placement = WindowPlacement.Fullscreen
                    }
                } else {
                    // Compose can report the native fullscreen transition one
                    // frame later than our state callback. Always restore the
                    // remembered placement instead of depending on that lagging
                    // value, otherwise Escape/the exit button becomes a no-op.
                    windowState.placement = placementBeforeFullscreen
                }
                isFullscreen = requestedFullscreen
            }
            DisposableEffect(window) {
                val dispatcher = KeyEventDispatcher { event ->
                    if (
                        event.id == KeyEvent.KEY_PRESSED &&
                        event.keyCode == KeyEvent.VK_ESCAPE &&
                        isFullscreen
                    ) {
                        updateFullscreen(false)
                        true
                    } else {
                        false
                    }
                }
                val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                focusManager.addKeyEventDispatcher(dispatcher)
                onDispose {
                    focusManager.removeKeyEventDispatcher(dispatcher)
                }
            }

            HoshiraApp(
                repository = repository,
                isFullscreen = isFullscreen,
                onFullscreenChange = updateFullscreen,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal data class DesktopWindowSizing(
    val initialSize: DpSize,
    val minimumSize: Dimension,
)

internal fun calculateWindowSizing(workArea: Rectangle): DesktopWindowSizing {
    val availableWidth = workArea.width.coerceAtLeast(1)
    val availableHeight = workArea.height.coerceAtLeast(1)
    val initialWidth = minOf(
        DEFAULT_WINDOW_WIDTH,
        (availableWidth * WINDOW_WORK_AREA_FRACTION).toInt().coerceAtLeast(1),
    )
    val initialHeight = minOf(
        DEFAULT_WINDOW_HEIGHT,
        (availableHeight * WINDOW_WORK_AREA_FRACTION).toInt().coerceAtLeast(1),
    )

    return DesktopWindowSizing(
        initialSize = DpSize(initialWidth.dp, initialHeight.dp),
        minimumSize = Dimension(
            minOf(MINIMUM_WINDOW_WIDTH, initialWidth),
            minOf(MINIMUM_WINDOW_HEIGHT, initialHeight),
        ),
    )
}

private fun defaultGraphicsConfiguration(): GraphicsConfiguration =
    GraphicsEnvironment
        .getLocalGraphicsEnvironment()
        .defaultScreenDevice
        .defaultConfiguration

private fun GraphicsConfiguration.workingArea(): Rectangle {
    val screenBounds = bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(this)
    return Rectangle(
        screenBounds.x + insets.left,
        screenBounds.y + insets.top,
        (screenBounds.width - insets.left - insets.right).coerceAtLeast(1),
        (screenBounds.height - insets.top - insets.bottom).coerceAtLeast(1),
    )
}

private const val DEFAULT_WINDOW_WIDTH = 1480
private const val DEFAULT_WINDOW_HEIGHT = 930
private const val MINIMUM_WINDOW_WIDTH = 720
private const val MINIMUM_WINDOW_HEIGHT = 480
private const val WINDOW_WORK_AREA_FRACTION = 0.94
