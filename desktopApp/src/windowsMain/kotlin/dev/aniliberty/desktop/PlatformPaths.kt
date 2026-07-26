package dev.aniliberty.desktop

import java.nio.file.Path

internal fun platformDataDirectory(): Path =
    portableDataDirectoryOrNull() ?: Path.of(localAppDataRoot(), "Hoshira")

internal fun platformCacheDirectory(): Path {
    return platformDataDirectory().resolve("cache")
}

private fun localAppDataRoot(): String =
    System.getenv("LOCALAPPDATA")
        ?.takeIf(String::isNotBlank)
        ?: System.getProperty("user.home")
        ?: System.getProperty("java.io.tmpdir")
