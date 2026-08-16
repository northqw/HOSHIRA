package app.hoshira.desktop.data

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

        store.updatePreferences(
            PlayerPreferences(
                preferredSource = PreferredPlayerSource.Kodik,
                startupVolume = 0.42f,
                resumeBehavior = ResumeBehavior.Ask,
            ),
        )
        store.updateProgress(progress(position = 320.0, duration = 1_200.0))
        store.updateQuality("1080p")

        val restored = UserDataStore(file).snapshot()
        assertEquals(0.42f, restored.preferences.startupVolume)
        assertEquals(ResumeBehavior.Ask, restored.preferences.resumeBehavior)
        assertEquals("1080p", restored.lastQuality)
        assertEquals(320.0, restored.history.single().resumablePositionSeconds)
        assertEquals("Kodik", restored.lastSourceByRelease[42])
    }

    @Test
    fun `episode is watched at ninety percent and no longer resumable`() {
        val watched = progress(position = 900.0, duration = 1_000.0)
            .copy(watched = isWatched(900.0, 1_000.0))

        assertTrue(watched.watched)
        assertEquals(0.0, watched.resumablePositionSeconds)
        assertFalse(isWatched(899.0, 1_000.0))
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
}
