package dev.aniliberty.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsWindowStyleTest {
    @Test
    fun `fullscreen style removes frame and keeps popup visible`() {
        val original =
            WS_CAPTION or
                WS_THICKFRAME or
                WS_SYSMENU or
                WS_MINIMIZEBOX or
                WS_MAXIMIZEBOX

        val fullscreen = borderlessFullscreenStyle(original)

        assertEquals(0, fullscreen and original)
        assertEquals(WS_POPUP or WS_VISIBLE, fullscreen)
    }

    @Test
    fun `fullscreen extended style removes window and client edges`() {
        val unrelatedStyle = 0x00000008
        val fullscreen = borderlessFullscreenExtendedStyle(
            WS_EX_WINDOWEDGE or WS_EX_CLIENTEDGE or unrelatedStyle,
        )

        assertEquals(unrelatedStyle, fullscreen)
    }

    private companion object {
        const val WS_VISIBLE = 0x10000000
        const val WS_POPUP = -0x80000000
        const val WS_CAPTION = 0x00C00000
        const val WS_THICKFRAME = 0x00040000
        const val WS_SYSMENU = 0x00080000
        const val WS_MINIMIZEBOX = 0x00020000
        const val WS_MAXIMIZEBOX = 0x00010000
        const val WS_EX_WINDOWEDGE = 0x00000100
        const val WS_EX_CLIENTEDGE = 0x00000200
    }
}
