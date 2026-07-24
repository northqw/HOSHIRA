package dev.aniliberty.desktop

import java.awt.Color
import java.awt.Frame
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JFrame

internal fun applyHoshiraWindowStyle(window: Frame) {
    applyHoshiraWindowBackground(window)
    loadApplicationIcon()?.let { window.iconImage = it }
}

internal fun applyHoshiraWindowBackground(window: Frame) {
    window.background = HOSHIRA_WINDOW_BACKGROUND
    if (window is JFrame) {
        window.rootPane.background = HOSHIRA_WINDOW_BACKGROUND
        window.layeredPane.background = HOSHIRA_WINDOW_BACKGROUND
        window.contentPane.background = HOSHIRA_WINDOW_BACKGROUND
        window.rootPane.isOpaque = true
        window.layeredPane.isOpaque = true
        (window.contentPane as? JComponent)?.isOpaque = true
    }
}

private fun loadApplicationIcon(): java.awt.Image? =
    runCatching {
        object {}.javaClass.getResourceAsStream("/icons/hoshira.png")
            ?.use(ImageIO::read)
    }.getOrNull()

private val HOSHIRA_WINDOW_BACKGROUND = Color(0x09, 0x0A, 0x0C)
