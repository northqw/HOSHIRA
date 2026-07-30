package dev.aniliberty.desktop

import dev.aniliberty.android.cacheDirectory
import dev.aniliberty.android.dataDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

internal fun platformDataDirectory(): Path = dataDirectory()

internal fun platformCacheDirectory(): Path = cacheDirectory()

internal fun isPortableMode(): Boolean = false

internal fun clearHoshiraCaches(): Boolean = runCatching {
    val cache = platformCacheDirectory().toAbsolutePath().normalize()
    if (!Files.exists(cache)) return@runCatching true
    Files.walk(cache).use { paths ->
        paths
            .filter { it != cache }
            .sorted(Comparator.reverseOrder())
            .forEach { path -> runCatching { Files.deleteIfExists(path) } }
    }
    true
}.getOrDefault(false)
