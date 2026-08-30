package app.hoshira.desktop

import app.hoshira.desktop.data.CatalogFilters
import app.hoshira.desktop.data.ReleaseRepository
import app.hoshira.desktop.data.UserDataStore
import app.hoshira.desktop.model.EpisodeDto
import app.hoshira.desktop.model.HomeFeed
import app.hoshira.desktop.model.ReleaseDto
import app.hoshira.desktop.model.ReleaseNameDto
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppControllerPlaybackStateTest {
    @Test
    fun `frequent playback stays outside compose history until flush`() = runBlocking {
        val episode = EpisodeDto(
            id = "episode-1",
            name = "Studio",
            playerName = "Kodik",
            ordinal = 1.0,
            playerPageUrl = "https://example.invalid/player",
        )
        val secondEpisode = episode.copy(
            id = "episode-2",
            ordinal = 2.0,
        )
        val release = ReleaseDto(
            id = 42,
            alias = "release-42",
            name = ReleaseNameDto("Release"),
            episodes = listOf(episode, secondEpisode),
        )
        val file = Files.createTempDirectory("hoshira-controller-playback").resolve("user-data.json")
        val store = UserDataStore(file, progressSaveIntervalMillis = 60_000L)
        val controller = AppController(
            repository = PlaybackReleaseRepository(release),
            userDataStore = store,
        )
        controller.showDetails(release.id)
        controller.playEpisode(episode)

        controller.recordPlayback(10.0, 1_200.0, 0.5f, "1080p")
        controller.recordPlayback(20.0, 1_200.0, 0.5f, "1080p")
        controller.recordPlayback(30.0, 1_200.0, 0.5f, "1080p")

        assertTrue(controller.watchHistory.isEmpty())
        assertEquals(1f, controller.preferences.startupVolume)
        assertEquals(null, controller.lastQuality)
        controller.flushPlayback()
        assertEquals(30.0, controller.watchHistory.single().positionSeconds)
        assertEquals(0.5f, controller.preferences.startupVolume)
        assertEquals("1080p", controller.lastQuality)

        controller.playEpisode(secondEpisode)
        assertEquals("episode-2", controller.playbackSession?.episode?.id)
        controller.closePlayer()
        controller.playEpisode(episode)
        assertEquals("episode-1", controller.playbackSession?.episode?.id)
        assertEquals(30.0, controller.playbackSession?.resumeSeconds)
        store.awaitPendingWrites()
    }
}

private class PlaybackReleaseRepository(
    private val release: ReleaseDto,
) : ReleaseRepository {
    override suspend fun home(): HomeFeed = HomeFeed(listOf(release), emptyList(), emptyList())
    override suspend fun search(query: String): List<ReleaseDto> = listOf(release)
    override suspend fun catalog(
        limit: Int,
        offset: Int,
        filters: CatalogFilters,
    ): List<ReleaseDto> = listOf(release)

    override suspend fun details(id: Int): ReleaseDto = release
}
