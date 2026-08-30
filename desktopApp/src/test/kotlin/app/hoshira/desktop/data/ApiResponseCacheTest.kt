package app.hoshira.desktop.data

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiResponseCacheTest {
    @Test
    fun `fresh response is reused without calling loader`() = runBlocking {
        var now = 1_000L
        var loads = 0
        val cache = ApiResponseCache(
            directory = Files.createTempDirectory("hoshira-api-cache-fresh"),
            nowEpochMillis = { now },
        )

        assertEquals("first", cache.get("feed", 5_000L) { loads++; "first" })
        now += 4_000L
        assertEquals("first", cache.get("feed", 5_000L) { loads++; "second" })
        assertEquals(1, loads)
    }

    @Test
    fun `stale response is returned when refresh fails`() = runBlocking {
        var now = 1_000L
        val directory = Files.createTempDirectory("hoshira-api-cache-stale")
        val cache = ApiResponseCache(directory, nowEpochMillis = { now })
        cache.get("details-42", 100L) { "cached" }
        now += 101L

        val result = cache.get("details-42", 100L) { error("offline") }

        assertEquals("cached", result)
        assertEquals(
            "cached",
            ApiResponseCache(directory, nowEpochMillis = { now })
                .get("details-42", 100L) { error("still offline") },
        )
    }

    @Test
    fun `concurrent requests for one key share one load`() = runBlocking {
        val cache = ApiResponseCache(Files.createTempDirectory("hoshira-api-cache-flight"))
        val releaseLoader = CompletableDeferred<Unit>()
        var loads = 0

        val requests = List(8) {
            async {
                cache.get("feed", 5_000L) {
                    loads++
                    releaseLoader.await()
                    "shared"
                }
            }
        }
        while (loads == 0) kotlinx.coroutines.yield()
        releaseLoader.complete(Unit)

        assertEquals(List(8) { "shared" }, requests.awaitAll())
        assertEquals(1, loads)
    }

    @Test
    fun `force refresh replaces a fresh entry`() = runBlocking {
        val cache = ApiResponseCache(Files.createTempDirectory("hoshira-api-cache-force"))
        cache.get("feed", 5_000L) { "first" }

        val refreshed = cache.get("feed", 5_000L, forceRefresh = true) { "second" }

        assertEquals("second", refreshed)
        assertEquals("second", cache.get("feed", 5_000L) { "third" })
    }

    @Test
    fun `cache write failure does not hide successful response`() = runBlocking {
        val fileInsteadOfDirectory = Files.createTempFile("hoshira-api-cache-read-only", ".tmp")
        val cache = ApiResponseCache(fileInsteadOfDirectory)

        assertEquals("network", cache.get("feed", 5_000L) { "network" })
        assertEquals("network", cache.get("feed", 5_000L) { "unexpected" })
    }
}
