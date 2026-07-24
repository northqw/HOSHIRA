package dev.aniliberty.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.Color
import java.awt.Frame
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JFrame

internal fun applyHoshiraWindowStyle(window: Frame) {
    applyHoshiraWindowBackground(window)
    loadApplicationIcon()?.let { window.iconImage = it }

    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return

    runCatching {
        val hwnd = HWND(Native.getComponentPointer(window))
        setDwmInt(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, 1)
        setDwmInt(hwnd, DWMWA_CAPTION_COLOR, COLOR_BLACK)
        setDwmInt(hwnd, DWMWA_BORDER_COLOR, COLOR_BLACK)
        setDwmInt(hwnd, DWMWA_TEXT_COLOR, COLOR_WHITE)
    }
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

private fun setDwmInt(
    hwnd: HWND,
    attribute: Int,
    value: Int,
) {
    val memory = Memory(Int.SIZE_BYTES.toLong())
    memory.setInt(0, value)
    DwmApi.INSTANCE.DwmSetWindowAttribute(
        hwnd = hwnd,
        attribute = attribute,
        value = memory,
        valueSize = Int.SIZE_BYTES,
    )
}

private interface DwmApi : Library {
    fun DwmSetWindowAttribute(
        hwnd: HWND,
        attribute: Int,
        value: Pointer,
        valueSize: Int,
    ): Int

    companion object {
        val INSTANCE: DwmApi = Native.load("dwmapi", DwmApi::class.java)
    }
}

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_BORDER_COLOR = 34
private const val DWMWA_CAPTION_COLOR = 35
private const val DWMWA_TEXT_COLOR = 36
private const val COLOR_BLACK = 0x000000
private const val COLOR_WHITE = 0xFFFFFF
private val HOSHIRA_WINDOW_BACKGROUND = Color(0x09, 0x0A, 0x0C)
