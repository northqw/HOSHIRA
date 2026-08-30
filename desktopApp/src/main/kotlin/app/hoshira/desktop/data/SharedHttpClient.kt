package app.hoshira.desktop.data

import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.TlsVersion

object SharedHttpClient {
    private val connectionPool = ConnectionPool(
        maxIdleConnections = 8,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES,
    )

    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .retryOnConnectionFailure(false)
            .build()
    }

    val api: OkHttpClient by lazy {
        base.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(24, TimeUnit.SECONDS)
            .writeTimeout(24, TimeUnit.SECONDS)
            .build()
    }

    val images: OkHttpClient by lazy {
        base.newBuilder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = IMAGE_MAX_REQUESTS
                    maxRequestsPerHost = IMAGE_MAX_REQUESTS_PER_HOST
                },
            )
            .addInterceptor(ImageConcurrencyInterceptor(IMAGE_MAX_CONCURRENT_REQUESTS))
            .connectionSpecs(listOf(IMAGE_TLS_CONNECTION_SPEC))
            .retryOnConnectionFailure(true)
            .connectTimeout(IMAGE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(IMAGE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    val media: OkHttpClient by lazy {
        base.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(24, TimeUnit.SECONDS)
            .build()
    }

    val geo: OkHttpClient by lazy {
        base.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }
}

internal class ImageConcurrencyInterceptor(
    val maxConcurrentRequests: Int,
) : Interceptor {
    private val permits = Semaphore(maxConcurrentRequests, true)

    init {
        require(maxConcurrentRequests > 0)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            permits.acquire()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Image request interrupted while waiting for a network slot", error)
        }
        return try {
            chain.proceed(chain.request())
        } finally {
            permits.release()
        }
    }
}

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(response))
                } else {
                    response.close()
                }
            }
        },
    )
}

internal fun shouldRetryHttpRequest(
    method: String,
    completedAttempts: Int,
    statusCode: Int? = null,
    transportFailure: Boolean = false,
): Boolean = method.equals("GET", ignoreCase = true) &&
    completedAttempts < MAX_SAFE_GET_ATTEMPTS &&
    (transportFailure || statusCode in RETRYABLE_HTTP_STATUS_CODES)

private val RETRYABLE_HTTP_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)
private val IMAGE_TLS_CONNECTION_SPEC = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
    .tlsVersions(TlsVersion.TLS_1_2)
    .build()
private const val MAX_SAFE_GET_ATTEMPTS = 2
private const val IMAGE_MAX_REQUESTS = 12
private const val IMAGE_MAX_REQUESTS_PER_HOST = 2
private const val IMAGE_MAX_CONCURRENT_REQUESTS = 2
private const val IMAGE_CONNECT_TIMEOUT_SECONDS = 20L
private const val IMAGE_READ_TIMEOUT_SECONDS = 45L
