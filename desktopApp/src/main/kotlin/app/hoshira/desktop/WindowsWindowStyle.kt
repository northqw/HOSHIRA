package app.hoshira.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser.MONITORINFO
import com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT
import com.sun.jna.platform.win32.User32
import java.awt.Color
import java.awt.EventQueue
import java.awt.Frame
import java.util.WeakHashMap
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

internal fun setHoshiraWindowFullscreen(
    window: Frame,
    fullscreen: Boolean,
): Boolean {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        return false
    }

    // Compose can request fullscreen changes while applying or disposing an
    // AWT interop node. Win32 style/placement changes synchronously dispatch
    // resize and paint messages, so applying them in that same call stack can
    // re-enter Compose's ignoringRedrawRequests section. Always cross one AWT
    // event boundary before touching the native top-level window.
    EventQueue.invokeLater {
        setHoshiraWindowFullscreenOnAwt(window, fullscreen)
    }
    return true
}

private fun setHoshiraWindowFullscreenOnAwt(
    window: Frame,
    fullscreen: Boolean,
): Boolean = runCatching {
    val hwnd = HWND(Native.getComponentPointer(window))
    if (Pointer.nativeValue(hwnd.pointer) == 0L) return@runCatching false

    if (fullscreen) {
        if (fullscreenSnapshots.containsKey(window)) return@runCatching true

        val monitor = User32.INSTANCE.MonitorFromWindow(
            hwnd,
            MONITOR_DEFAULT_TO_NEAREST,
        ) ?: return@runCatching false
        val monitorInfo = MONITORINFO().apply {
            cbSize = size()
        }
        if (!User32.INSTANCE.GetMonitorInfo(monitor, monitorInfo).booleanValue()) {
            return@runCatching false
        }

        val placement = WINDOWPLACEMENT().apply {
            length = size()
        }
        if (!User32.INSTANCE.GetWindowPlacement(hwnd, placement).booleanValue()) {
            return@runCatching false
        }

        val style = User32.INSTANCE.GetWindowLong(hwnd, GWL_STYLE)
        val extendedStyle = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE)
        fullscreenSnapshots[window] = FullscreenSnapshot(
            style = style,
            extendedStyle = extendedStyle,
            placement = placement,
        )

        User32.INSTANCE.ShowWindow(hwnd, SW_RESTORE)
        User32.INSTANCE.SetWindowLong(
            hwnd,
            GWL_STYLE,
            borderlessFullscreenStyle(style),
        )
        User32.INSTANCE.SetWindowLong(
            hwnd,
            GWL_EXSTYLE,
            borderlessFullscreenExtendedStyle(extendedStyle),
        )
        setDwmInt(
            hwnd,
            DWMWA_WINDOW_CORNER_PREFERENCE,
            DWMWCP_DONOTROUND,
        )
        setDwmInt(hwnd, DWMWA_BORDER_COLOR, DWMWA_COLOR_NONE)

        val monitorBounds = monitorInfo.rcMonitor
        User32.INSTANCE.SetWindowPos(
            hwnd,
            HWND_TOP,
            monitorBounds.left,
            monitorBounds.top,
            (monitorBounds.right - monitorBounds.left).coerceAtLeast(1),
            (monitorBounds.bottom - monitorBounds.top).coerceAtLeast(1),
            SWP_FRAMECHANGED or SWP_SHOWWINDOW or SWP_NOOWNERZORDER,
        )
        User32.INSTANCE.SetForegroundWindow(hwnd)
        refreshFullscreenContent(window)
    } else {
        val snapshot = fullscreenSnapshots.remove(window)
            ?: return@runCatching false
        User32.INSTANCE.SetWindowLong(hwnd, GWL_STYLE, snapshot.style)
        User32.INSTANCE.SetWindowLong(
            hwnd,
            GWL_EXSTYLE,
            snapshot.extendedStyle,
        )
        User32.INSTANCE.SetWindowPlacement(hwnd, snapshot.placement)
        User32.INSTANCE.SetWindowPos(
            hwnd,
            HWND_NOTOPMOST,
            0,
            0,
            0,
            0,
            SWP_NOMOVE or
                SWP_NOSIZE or
                SWP_NOOWNERZORDER or
                SWP_FRAMECHANGED or
                SWP_SHOWWINDOW,
        )
        setDwmInt(
            hwnd,
            DWMWA_WINDOW_CORNER_PREFERENCE,
            DWMWCP_DEFAULT,
        )
        setDwmInt(hwnd, DWMWA_BORDER_COLOR, COLOR_BLACK)
        refreshFullscreenContent(window)
    }

    true
}.getOrDefault(false)

private fun refreshFullscreenContent(window: Frame) {
    window.invalidate()
    window.validate()
    window.repaint()
    window.requestFocus()
    EventQueue.invokeLater {
        window.validate()
        window.repaint()
    }
}

internal fun borderlessFullscreenStyle(style: Int): Int =
    (style and WS_OVERLAPPEDWINDOW.inv()) or WS_POPUP or WS_VISIBLE

internal fun borderlessFullscreenExtendedStyle(style: Int): Int =
    style and WS_EX_OVERLAPPEDWINDOW.inv()

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

private data class FullscreenSnapshot(
    val style: Int,
    val extendedStyle: Int,
    val placement: WINDOWPLACEMENT,
)

private val fullscreenSnapshots = WeakHashMap<Frame, FullscreenSnapshot>()
private val HWND_TOP = HWND(Pointer.createConstant(0))
private val HWND_NOTOPMOST = HWND(Pointer.createConstant(-2))

private const val GWL_STYLE = -16
private const val GWL_EXSTYLE = -20
private const val WS_VISIBLE = 0x10000000
private const val WS_POPUP = -0x80000000
private const val WS_OVERLAPPEDWINDOW = 0x00CF0000
private const val WS_EX_WINDOWEDGE = 0x00000100
private const val WS_EX_CLIENTEDGE = 0x00000200
private const val WS_EX_OVERLAPPEDWINDOW =
    WS_EX_WINDOWEDGE or WS_EX_CLIENTEDGE
private const val MONITOR_DEFAULT_TO_NEAREST = 2
private const val SW_RESTORE = 9
private const val SWP_NOSIZE = 0x0001
private const val SWP_NOMOVE = 0x0002
private const val SWP_NOOWNERZORDER = 0x0200
private const val SWP_FRAMECHANGED = 0x0020
private const val SWP_SHOWWINDOW = 0x0040

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
private const val DWMWA_BORDER_COLOR = 34
private const val DWMWA_CAPTION_COLOR = 35
private const val DWMWA_TEXT_COLOR = 36
private const val DWMWCP_DEFAULT = 0
private const val DWMWCP_DONOTROUND = 1
private const val DWMWA_COLOR_NONE = -2
private const val COLOR_BLACK = 0x000000
private const val COLOR_WHITE = 0xFFFFFF
private val HOSHIRA_WINDOW_BACKGROUND = Color(0x09, 0x0A, 0x0C)
