package dev.aniliberty.desktop

import java.nio.file.Path

internal fun platformCacheDirectory(): Path {
    val xdgCache = System.getenv("XDG_CACHE_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
    val home = System.getProperty("user.home")
    return (xdgCache ?: Path.of(home, ".cache")).resolve("hoshira")
}
