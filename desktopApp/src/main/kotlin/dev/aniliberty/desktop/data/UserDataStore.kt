package dev.aniliberty.desktop.data

import dev.aniliberty.desktop.platformDataDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

@Serializable
data class UserDataSnapshot(
    val preferences: PlayerPreferences = PlayerPreferences(),
    val history: List<WatchProgress> = emptyList(),
    val lastQuality: String? = null,
    val lastDubbingByRelease: Map<Int, String> = emptyMap(),
    val lastSourceByRelease: Map<Int, String> = emptyMap(),
)

class UserDataStore(
    private val file: Path = platformDataDirectory().resolve("user-data.json"),
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    @Volatile
    private var current: UserDataSnapshot = load()

    fun snapshot(): UserDataSnapshot = current

    fun updatePreferences(preferences: PlayerPreferences): UserDataSnapshot = update {
        copy(preferences = preferences)
    }

    fun updateQuality(quality: String?): UserDataSnapshot = update {
        copy(lastQuality = quality?.takeIf(String::isNotBlank))
    }

    fun updateProgress(progress: WatchProgress): UserDataSnapshot = update {
        val updated = (history.filterNot {
            it.releaseId == progress.releaseId && it.episodeId == progress.episodeId
        } + progress)
            .sortedByDescending(WatchProgress::updatedAtEpochMillis)
            .take(MAX_HISTORY_ENTRIES)
        copy(
            history = updated,
            lastDubbingByRelease = lastDubbingByRelease + (progress.releaseId to progress.dubbing),
            lastSourceByRelease = lastSourceByRelease + (progress.releaseId to progress.source),
        )
    }

    fun clearHistory(): UserDataSnapshot = update { copy(history = emptyList()) }

    private fun update(transform: UserDataSnapshot.() -> UserDataSnapshot): UserDataSnapshot =
        synchronized(lock) {
            val updated = current.transform()
            // A preferences click must never terminate the application if the
            // storage disappears or becomes temporarily read-only.
            runCatching { save(updated) }
            current = updated
            updated
        }

    private fun load(): UserDataSnapshot = runCatching {
        if (!Files.exists(file)) return@runCatching UserDataSnapshot()
        json.decodeFromString<UserDataSnapshot>(Files.readString(file))
    }.getOrDefault(UserDataSnapshot())

    private fun save(snapshot: UserDataSnapshot) {
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(snapshot))
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
