package app.hoshira.desktop

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache

@UnstableApi
internal object PlaybackMediaCache {
    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            context.cacheDir.resolve(STREAMING_MEDIA_CACHE_DIRECTORY),
            LeastRecentlyUsedCacheEvictor(STREAMING_MEDIA_CACHE_MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { instance = it }
    }

    fun release() {
        synchronized(this) {
            instance?.release()
            instance = null
        }
    }
}

internal const val STREAMING_MEDIA_CACHE_MAX_BYTES = 50L * 1024L * 1024L
private const val STREAMING_MEDIA_CACHE_DIRECTORY = "streaming-media"
