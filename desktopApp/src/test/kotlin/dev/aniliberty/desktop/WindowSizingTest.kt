package dev.aniliberty.desktop

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowSizingTest {
    @Test
    fun `large displays keep the preferred application size`() {
        val sizing = calculateWindowSizing(Rectangle(0, 0, 1920, 1040))

        assertEquals(1480f, sizing.initialSize.width.value)
        assertEquals(930f, sizing.initialSize.height.value)
        assertEquals(720, sizing.minimumSize.width)
        assertEquals(480, sizing.minimumSize.height)
    }

    @Test
    fun `scaled laptop work area keeps initial and minimum size on screen`() {
        val sizing = calculateWindowSizing(Rectangle(0, 0, 960, 500))

        assertEquals(902f, sizing.initialSize.width.value)
        assertEquals(470f, sizing.initialSize.height.value)
        assertEquals(720, sizing.minimumSize.width)
        assertEquals(470, sizing.minimumSize.height)
    }
}
