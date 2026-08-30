package app.hoshira.desktop.data

import androidx.compose.runtime.Immutable
import app.hoshira.desktop.model.ImageDto
import app.hoshira.desktop.model.LabelValueDto
import app.hoshira.desktop.model.ReleaseDto
import app.hoshira.desktop.model.ReleaseNameDto
import app.hoshira.desktop.platformDataDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class PreferredPlayerSource(val displayName: String) {
    Kodik("Kodik"),
    Sibnet("Sibnet"),
    Alloha("Alloha"),
}

@Serializable
enum class ResumeBehavior(val displayName: String) {
    Automatically("Продолжать автоматически"),
    Ask("Всегда спрашивать"),
    Restart("Всегда с начала"),
}

@Immutable
@Serializable
data class PlayerPreferences(
    val preferredSource: PreferredPlayerSource = PreferredPlayerSource.Kodik,
    val autoFullscreen: Boolean = false,
    val autoplayNext: Boolean = true,
    val controlsHideDelayMs: Int = 4_800,
    val startupVolume: Float = 1f,
    val resumeBehavior: ResumeBehavior = ResumeBehavior.Automatically,
    val hardwareAcceleration: Boolean = true,
)

@Immutable
@Serializable
data class WatchProgress(
    val releaseId: Int,
    val releaseTitle: String,
    val episodeId: String,
    val episodeOrdinal: Double,
    val episodeTitle: String,
    val dubbing: String,
    val source: String,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val updatedAtEpochMillis: Long,
    val imageUrl: String? = null,
    val watched: Boolean = false,
) {
    val fraction: Float
        get() = if (durationSeconds > 0.0) {
            (positionSeconds / durationSeconds).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }

    val resumablePositionSeconds: Double
        get() = positionSeconds.takeIf {
            it >= RESUME_MINIMUM_SECONDS && !watched && fraction < WATCHED_THRESHOLD
        } ?: 0.0
}

@Immutable
@Serializable
data class SearchHistoryEntry(
    val releaseId: Int,
    val alias: String,
    val title: String,
    val year: Int? = null,
    val type: String? = null,
    val posterThumbnailUrl: String? = null,
    val posterStandardUrl: String? = null,
    val posterHighUrl: String? = null,
    val posterFullUrl: String? = null,
) {
    fun toRelease(): ReleaseDto = ReleaseDto(
        id = releaseId,
        alias = alias,
        name = ReleaseNameDto(main = title),
        year = year,
        type = type?.let { LabelValueDto(value = it, description = it) },
        poster = ImageDto(
            thumbnail = posterThumbnailUrl,
            standard = posterStandardUrl,
            high = posterHighUrl,
            full = posterFullUrl,
        ),
    )
}

@Immutable
@Serializable
data class UserDataSnapshot(
    val preferences: PlayerPreferences = PlayerPreferences(),
    val history: List<WatchProgress> = emptyList(),
    val lastQuality: String? = null,
    val lastDubbingByRelease: Map<Int, String> = emptyMap(),
    val lastSourceByRelease: Map<Int, String> = emptyMap(),
    val searchHistory: List<SearchHistoryEntry> = emptyList(),
)

class UserDataStore(
    private val file: Path = platformDataDirectory().resolve("user-data.json"),
    private val progressSaveIntervalMillis: Long = PROGRESS_SAVE_INTERVAL_MILLIS,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()
    private val writer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "hoshira-user-data-writer").apply { isDaemon = true }
    }
    private var revision = 0L
    private var persistedRevision = 0L
    private var scheduledWrite: ScheduledFuture<*>? = null
    private var scheduledWriteAtNanos = Long.MAX_VALUE
    private var scheduledToken = 0L
    private var lastWriteError: Throwable? = null

    @Volatile
    internal var completedWriteCount: Int = 0
        private set

    @Volatile
    private var current: UserDataSnapshot = load()

    fun snapshot(): UserDataSnapshot = current

    fun updatePreferences(preferences: PlayerPreferences): UserDataSnapshot = update(0L) {
        copy(preferences = preferences)
    }

    fun updateQuality(quality: String?): UserDataSnapshot = update(0L) {
        copy(lastQuality = quality?.takeIf(String::isNotBlank))
    }

    fun updateProgress(progress: WatchProgress): UserDataSnapshot = update(progressSaveIntervalMillis) {
        withProgress(progress)
    }

    fun updatePlayback(
        progress: WatchProgress,
        preferences: PlayerPreferences,
        quality: String?,
    ): UserDataSnapshot = update(progressSaveIntervalMillis) {
        withProgress(progress).copy(
            preferences = preferences,
            lastQuality = quality?.takeIf(String::isNotBlank) ?: lastQuality,
        )
    }

    fun clearHistory(): UserDataSnapshot = update(0L) { copy(history = emptyList()) }

    fun recordSearchVisit(release: ReleaseDto): UserDataSnapshot = update(0L) {
        val entry = SearchHistoryEntry(
            releaseId = release.id,
            alias = release.alias,
            title = release.displayName,
            year = release.year,
            type = release.type?.description ?: release.type?.value,
            posterThumbnailUrl = release.posterThumbnailUrl,
            posterStandardUrl = release.posterStandardUrl,
            posterHighUrl = release.posterHighUrl,
            posterFullUrl = release.posterFullUrl,
        )
        copy(
            searchHistory = (listOf(entry) + searchHistory.filterNot {
                it.releaseId == release.id
            }).take(MAX_SEARCH_HISTORY_ENTRIES),
        )
    }

    fun requestFlush() {
        synchronized(lock) {
            if (revision > persistedRevision) scheduleWriteLocked(0L)
        }
    }

    internal fun awaitPendingWrites() {
        val targetRevision = synchronized(lock) {
            if (revision <= persistedRevision) return
            scheduleWriteLocked(0L)
            revision
        }

        while (true) {
            val pending = synchronized(lock) { scheduledWrite }
            pending?.get()
            synchronized(lock) {
                if (persistedRevision >= targetRevision) return
                lastWriteError?.let { throw IllegalStateException("Could not persist user data", it) }
                scheduleWriteLocked(0L)
            }
        }
    }

    private fun UserDataSnapshot.withProgress(progress: WatchProgress): UserDataSnapshot {
        val updated = (history.filterNot {
            it.releaseId == progress.releaseId && it.episodeId == progress.episodeId
        } + progress)
            .sortedByDescending(WatchProgress::updatedAtEpochMillis)
            .take(MAX_HISTORY_ENTRIES)
        return copy(
            history = updated,
            lastDubbingByRelease = lastDubbingByRelease + (progress.releaseId to progress.dubbing),
            lastSourceByRelease = lastSourceByRelease + (progress.releaseId to progress.source),
        )
    }

    private fun update(
        writeDelayMillis: Long,
        transform: UserDataSnapshot.() -> UserDataSnapshot,
    ): UserDataSnapshot =
        synchronized(lock) {
            val updated = current.transform()
            if (updated == current) return@synchronized current
            current = updated
            revision++
            scheduleWriteLocked(writeDelayMillis)
            updated
        }

    private fun scheduleWriteLocked(delayMillis: Long) {
        val requestedAtNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(delayMillis.coerceAtLeast(0L))
        val existing = scheduledWrite
        if (existing != null && !existing.isDone && scheduledWriteAtNanos <= requestedAtNanos) return

        existing?.cancel(false)
        val token = ++scheduledToken
        scheduledWriteAtNanos = requestedAtNanos
        scheduledWrite = writer.schedule(
            { persistScheduled(token) },
            delayMillis.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
    }

    private fun persistScheduled(token: Long) {
        val (snapshot, snapshotRevision) = synchronized(lock) {
            if (token != scheduledToken) return
            current to revision
        }
        val result = runCatching { save(snapshot) }

        synchronized(lock) {
            if (result.isSuccess) {
                persistedRevision = maxOf(persistedRevision, snapshotRevision)
                completedWriteCount++
                lastWriteError = null
            } else {
                lastWriteError = result.exceptionOrNull()
            }
            if (token == scheduledToken) {
                scheduledWrite = null
                scheduledWriteAtNanos = Long.MAX_VALUE
                if (result.isSuccess && revision > persistedRevision) {
                    scheduleWriteLocked(progressSaveIntervalMillis)
                }
            }
        }
    }

    private fun load(): UserDataSnapshot = runCatching {
        if (!Files.exists(file)) return@runCatching UserDataSnapshot()
        json.decodeFromString<UserDataSnapshot>(String(Files.readAllBytes(file), UTF_8))
    }.getOrDefault(UserDataSnapshot())

    private fun save(snapshot: UserDataSnapshot) {
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.write(temporary, json.encodeToString(snapshot).toByteArray(UTF_8))
        runCatching {
            Files.move(
                temporary,
                file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal fun isWatched(positionSeconds: Double, durationSeconds: Double): Boolean =
    durationSeconds > 0.0 &&
        positionSeconds / durationSeconds >= WATCHED_THRESHOLD

const val WATCHED_THRESHOLD = 0.9f
private const val RESUME_MINIMUM_SECONDS = 15.0
private const val MAX_HISTORY_ENTRIES = 200
private const val MAX_SEARCH_HISTORY_ENTRIES = 30
private const val PROGRESS_SAVE_INTERVAL_MILLIS = 10_000L
