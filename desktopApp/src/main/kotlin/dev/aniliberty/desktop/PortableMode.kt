package dev.aniliberty.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

internal fun portableDataDirectoryOrNull(
    root: Path = applicationRootDirectory(),
): Path? {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val portableRequested = Files.isRegularFile(normalizedRoot.resolve(PORTABLE_FLAG))
    if (!portableRequested || !Files.isWritable(normalizedRoot)) return null
    if (isProtectedInstallationRoot(normalizedRoot)) return null
    return normalizedRoot.resolve("data")
}

internal fun isPortableMode(): Boolean = portableDataDirectoryOrNull() != null

internal fun applicationRootDirectory(): Path {
    val javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()
    if (javaHome.fileName?.toString().equals("runtime", ignoreCase = true)) {
        javaHome.parent?.let { return it }
    }
    return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
}

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

private fun isProtectedInstallationRoot(root: Path): Boolean =
    listOfNotNull(
        System.getenv("ProgramFiles"),
        System.getenv("ProgramFiles(x86)"),
        System.getenv("ProgramW6432"),
    ).any { protectedPath ->
        root.startsWith(Path.of(protectedPath).toAbsolutePath().normalize())
    }

private const val PORTABLE_FLAG = "portable.flag"
