package app.hoshira.desktop.data

import java.io.IOException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okio.Timeout

class SharedHttpClientTest {
    @Test
    fun `derived clients reuse one connection pool`() {
        assertSame(SharedHttpClient.base.connectionPool, SharedHttpClient.api.connectionPool)
        assertSame(SharedHttpClient.base.connectionPool, SharedHttpClient.images.connectionPool)
        assertSame(SharedHttpClient.base.connectionPool, SharedHttpClient.media.connectionPool)
        assertSame(SharedHttpClient.base.connectionPool, SharedHttpClient.geo.connectionPool)
    }

    @Test
    fun `image client tolerates slow tls without flooding one host`() {
        assertTrue(SharedHttpClient.images.retryOnConnectionFailure)
        assertEquals(20_000, SharedHttpClient.images.connectTimeoutMillis)
        assertEquals(45_000, SharedHttpClient.images.readTimeoutMillis)
        assertEquals(12, SharedHttpClient.images.dispatcher.maxRequests)
        assertEquals(2, SharedHttpClient.images.dispatcher.maxRequestsPerHost)
        assertEquals(listOf(TlsVersion.TLS_1_2), SharedHttpClient.images.connectionSpecs.single().tlsVersions)
        assertEquals(
            2,
            SharedHttpClient.images.interceptors
                .filterIsInstance<ImageConcurrencyInterceptor>()
                .single()
                .maxConcurrentRequests,
        )
    }

    @Test
    fun `only transient safe get failures are retried once`() {
        assertTrue(shouldRetryHttpRequest("GET", completedAttempts = 1, statusCode = 503))
        assertTrue(shouldRetryHttpRequest("GET", completedAttempts = 1, transportFailure = true))
        assertFalse(shouldRetryHttpRequest("GET", completedAttempts = 2, statusCode = 503))
        assertFalse(shouldRetryHttpRequest("GET", completedAttempts = 1, statusCode = 404))
        assertFalse(shouldRetryHttpRequest("POST", completedAttempts = 1, statusCode = 503))
        assertFalse(shouldRetryHttpRequest("PUT", completedAttempts = 1, transportFailure = true))
        assertFalse(shouldRetryHttpRequest("DELETE", completedAttempts = 1, statusCode = 500))
    }

    @Test
    fun `cancelling coroutine cancels okhttp call`() = runBlocking {
        val call = PendingCall()
        val request = launch { call.awaitResponse() }
        yield()

        request.cancelAndJoin()

        assertTrue(call.isCanceled())
    }
}

private class PendingCall : Call {
    private val request = Request.Builder().url("https://example.invalid/").build()
    private var callback: Callback? = null
    private var executed = false
    private var canceled = false

    override fun request(): Request = request
    override fun execute(): Response = error("Not used")
    override fun enqueue(responseCallback: Callback) {
        executed = true
        callback = responseCallback
    }

    override fun cancel() {
        canceled = true
        callback?.onFailure(this, IOException("Canceled"))
    }

    override fun isExecuted(): Boolean = executed
    override fun isCanceled(): Boolean = canceled
    override fun timeout(): Timeout = Timeout.NONE
    override fun clone(): Call = PendingCall()
}
