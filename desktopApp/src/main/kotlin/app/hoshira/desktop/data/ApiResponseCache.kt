package app.hoshira.desktop.data

import app.hoshira.desktop.platformCacheDirectory
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ApiResponseCache(
    private val directory: Path = platformCacheDirectory().resolve("api-responses"),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val memory = ConcurrentHashMap<String, CacheEntry>()
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun get(
        key: String,
        ttlMillis: Long,
        forceRefresh: Boolean = false,
        loader: suspend () -> String,
    ): String = keyLocks.computeIfAbsent(key) { Mutex() }.withLock {
        val cached = read(key)
        if (!forceRefresh && cached?.isFresh(ttlMillis) == true) return@withLock cached.body

        try {
            val body = loader()
            val entry = CacheEntry(nowEpochMillis(), body)
            memory[key] = entry
            try {
                write(key, entry)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // A read-only or temporarily unavailable cache must not turn a
                // successful network response into an application error.
            }
            body
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            cached?.body ?: throw error
        }
    }

    suspend fun invalidate(key: String) {
        keyLocks.computeIfAbsent(key) { Mutex() }.withLock {
            memory.remove(key)
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(fileFor(key))
            }
        }
    }

    fun clearMemory() {
        memory.clear()
    }

    private suspend fun read(key: String): CacheEntry? {
        memory[key]?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val text = String(Files.readAllBytes(fileFor(key)), UTF_8)
                val separator = text.indexOf('\n')
                if (separator <= 0) return@runCatching null
                CacheEntry(
                    storedAtEpochMillis = text.substring(0, separator).toLong(),
                    body = text.substring(separator + 1),
                )
            }.getOrNull()
        }
        if (loaded != null) memory[key] = loaded
        return loaded
    }

    private suspend fun write(key: String, entry: CacheEntry) = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val target = fileFor(key)
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        Files.write(
            temporary,
            "${entry.storedAtEpochMillis}\n${entry.body}".toByteArray(UTF_8),
        )
        runCatching {
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fileFor(key: String): Path = directory.resolve("${key.sha256()}.json")

    private fun CacheEntry.isFresh(ttlMillis: Long): Boolean =
        nowEpochMillis() - storedAtEpochMillis <= ttlMillis.coerceAtLeast(0L)
}

private data class CacheEntry(
    val storedAtEpochMillis: Long,
    val body: String,
)

private fun String.sha256(): String = MessageDigest
    .getInstance("SHA-256")
    .digest(toByteArray(UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
