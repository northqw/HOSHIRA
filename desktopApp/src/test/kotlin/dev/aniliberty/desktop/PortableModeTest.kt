package dev.aniliberty.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortableModeTest {
    @Test
    fun `portable data is enabled only by marker in writable app directory`() {
        val root = Files.createTempDirectory("hoshira-portable")

        assertNull(portableDataDirectoryOrNull(root))

        Files.writeString(root.resolve("portable.flag"), "portable")

        assertEquals(
            root.toAbsolutePath().normalize().resolve("data"),
            portableDataDirectoryOrNull(root),
        )
    }
}
