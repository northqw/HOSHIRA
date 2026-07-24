package dev.aniliberty.desktop

import java.nio.file.Path

internal fun platformCacheDirectory(): Path {
    val localData = System.getenv("LOCALAPPDATA")
        ?.takeIf(String::isNotBlank)
        ?: System.getProperty("user.home")
        ?: System.getProperty("java.io.tmpdir")
    return Path.of(localData, "Hoshira", "cache")
}
