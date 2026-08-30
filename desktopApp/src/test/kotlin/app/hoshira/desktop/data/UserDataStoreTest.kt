package app.hoshira.desktop.data

import app.hoshira.desktop.model.ImageDto
import app.hoshira.desktop.model.ReleaseDto
import app.hoshira.desktop.model.ReleaseNameDto
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserDataStoreTest {
    @Test
    fun `progress and player preferences survive reload`() {
        val directory = Files.createTempDirectory("hoshira-user-data-test")
        val file = directory.resolve("user-data.json")
        val store = UserDataStore(file)

        store.updatePlayback(
            progress = progress(position = 320.0, duration = 1_200.0),
            preferences = PlayerPreferences(
                preferredSource = PreferredPlayerSource.Kodik,
                startupVolume = 0.42f,
                resumeBehavior = ResumeBehavior.Ask,
            ),
            quality = "1080p",
        )
        store.awaitPendingWrites()

        val restored = UserDataStore(file).snapshot()
        assertEquals(0.42f, restored.preferences.startupVolume)
        assertEquals(ResumeBehavior.Ask, restored.preferences.resumeBehavior)
        assertEquals("1080p", restored.lastQuality)
        assertEquals(320.0, restored.history.single().resumablePositionSeconds)
        assertEquals("Kodik", restored.lastSourceByRelease[42])
    }

    @Test
    fun `frequent playback updates are coalesced until flush`() {
        val directory = Files.createTempDirectory("hoshira-user-data-coalescing-test")
        val file = directory.resolve("user-data.json")
        val store = UserDataStore(file, progressSaveIntervalMillis = 60_000L)

        store.updatePlayback(progress(10.0, 1_200.0), PlayerPreferences(), "720p")
        store.updatePlayback(progress(20.0, 1_200.0), PlayerPreferences(), "1080p")
        store.updatePlayback(progress(30.0, 1_200.0), PlayerPreferences(), "1080p")

        assertEquals(0, store.completedWriteCount)
        assertFalse(Files.exists(file))

        store.requestFlush()
        store.awaitPendingWrites()

        assertEquals(1, store.completedWriteCount)
        assertEquals(30.0, UserDataStore(file).snapshot().history.single().positionSeconds)
    }

    @Test
    fun `episode is watched at ninety percent and no longer resumable`() {
        val watched = progress(position = 900.0, duration = 1_000.0)
            .copy(watched = isWatched(900.0, 1_000.0))

        assertTrue(watched.watched)
        assertEquals(0.0, watched.resumablePositionSeconds)
        assertFalse(isWatched(899.0, 1_000.0))
    }

    @Test
    fun `opened search cards survive reload and most recent visit comes first`() {
        val file = Files.createTempDirectory("hoshira-search-history-test")
            .resolve("user-data.json")
        val store = UserDataStore(file)
        val first = searchRelease(1, "Первый")
        val second = searchRelease(2, "Второй")

        store.recordSearchVisit(first)
        store.recordSearchVisit(second)
        store.recordSearchVisit(first.copy(name = ReleaseNameDto("Первый обновлённый")))
        store.awaitPendingWrites()

        val restored = UserDataStore(file).snapshot().searchHistory
        assertEquals(listOf(1, 2), restored.map(SearchHistoryEntry::releaseId))
        assertEquals("Первый обновлённый", restored.first().title)
        assertEquals(
            "https://example.com/1.webp",
            restored.first().toRelease().posterStandardUrl,
        )
    }

    private fun progress(position: Double, duration: Double) = WatchProgress(
        releaseId = 42,
        releaseTitle = "Release",
        episodeId = "episode-1",
        episodeOrdinal = 1.0,
        episodeTitle = "1 серия",
        dubbing = "Studio",
        source = "Kodik",
        positionSeconds = position,
        durationSeconds = duration,
        updatedAtEpochMillis = 1L,
    )

    private fun searchRelease(id: Int, title: String) = ReleaseDto(
        id = id,
        alias = "release-$id",
        name = ReleaseNameDto(title),
        year = 2026,
        poster = ImageDto(standard = "https://example.com/$id.webp"),
    )
}
