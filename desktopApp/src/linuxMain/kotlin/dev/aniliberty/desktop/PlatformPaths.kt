package dev.aniliberty.desktop

import java.nio.file.Path

internal fun platformDataDirectory(): Path {
    portableDataDirectoryOrNull()?.let { return it }
    val xdgData = System.getenv("XDG_DATA_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
    val home = System.getProperty("user.home")
    return (xdgData ?: Path.of(home, ".local", "share")).resolve("hoshira")
}

internal fun platformCacheDirectory(): Path {
    portableDataDirectoryOrNull()?.let { return it.resolve("cache") }
    val xdgCache = System.getenv("XDG_CACHE_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
    val home = System.getProperty("user.home")
    return (xdgCache ?: Path.of(home, ".cache")).resolve("hoshira")
}
