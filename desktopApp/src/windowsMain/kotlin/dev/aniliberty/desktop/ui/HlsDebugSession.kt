package dev.aniliberty.desktop.ui

import dev.aniliberty.desktop.platformDataDirectory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.TimeSource

internal class HlsDebugSession {
    private val entries = CopyOnWriteArrayList<String>()
    private var startedAt = TimeSource.Monotonic.markNow()
    private var sessionId = newSessionId()

    val logPath: String
        get() = HlsDebugFile.path.toAbsolutePath().normalize().toString()

    fun restart() {
        entries.clear()
        sessionId = newSessionId()
        startedAt = TimeSource.Monotonic.markNow()
        record("session started")
    }

    fun record(message: String) {
        val cleanMessage = message
            .replace('\r', ' ')
            .replace('\n', ' ')
            .take(MAX_EVENT_LENGTH)
        val entry = "+${startedAt.elapsedNow().inWholeMilliseconds}ms $cleanMessage"
        entries += entry
        HlsDebugFile.append(sessionId, cleanMessage)
    }

    fun report(): String = buildString {
        appendLine("HLS debug $sessionId")
        entries.takeLast(MAX_REPORT_ENTRIES).forEach(::appendLine)
        append("log: $logPath")
    }

    private fun newSessionId(): String =
        UUID.randomUUID().toString().substring(0, 8)
}

private object HlsDebugFile {
    val path
        get() = platformDataDirectory().resolve("logs").resolve("hls-debug.log")

    @Synchronized
    fun append(sessionId: String, message: String) {
        runCatching {
            Files.createDirectories(path.parent)
            if (Files.isRegularFile(path) && Files.size(path) > MAX_LOG_BYTES) {
                Files.writeString(
                    path,
                    "",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
            }
            Files.writeString(
                path,
                "${Instant.now()} [$sessionId] $message${System.lineSeparator()}",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }
}

private const val MAX_EVENT_LENGTH = 800
private const val MAX_REPORT_ENTRIES = 40
private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
