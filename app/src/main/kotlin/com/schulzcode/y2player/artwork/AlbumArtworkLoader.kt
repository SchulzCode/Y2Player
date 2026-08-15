package com.schulzcode.y2player.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.schulzcode.y2player.playback.NativeAudio
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AlbumArtworkLoader(cacheBytes: Int = DEFAULT_CACHE_BYTES) {
    private val requestLock = Any()
    private val inFlight = mutableMapOf<ArtworkRequestKey, MutableList<(String, Bitmap?) -> Unit>>()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(MAX_PENDING_TASKS),
        { runnable -> Thread(runnable, "y2-artwork").apply { isDaemon = true } },
        RejectedExecutionHandler { runnable, pool ->
            (pool.queue.poll() as? ArtworkTask)?.discard()
            if (!pool.isShutdown) pool.execute(runnable)
        }
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sourceResolver = ArtworkSourceResolver(
        maximumBytes = MAX_ARTWORK_BYTES,
        readEmbedded = { path, maximumBytes -> NativeAudio.nativeReadArtwork(path, maximumBytes) }
    )
    private val cache = object : LruCache<ArtworkRequestKey, CachedArtwork>(cacheBytes.coerceAtLeast(MIN_CACHE_BYTES)) {
        override fun sizeOf(key: ArtworkRequestKey, value: CachedArtwork): Int = value.bitmap?.byteCount ?: 1
    }

    fun load(path: String, targetSize: Int, callback: (String, Bitmap?) -> Unit) {
        val modifiedAt = runCatching { File(path).lastModified() }.getOrDefault(0L)
        load(path, modifiedAt, 0L, targetSize, callback)
    }

    fun load(
        path: String,
        fileModifiedAt: Long,
        libraryRevision: Long,
        targetSize: Int,
        callback: (String, Bitmap?) -> Unit
    ) {
        val safeTargetSize = targetSize.coerceIn(MIN_TARGET_SIZE, MAX_TARGET_SIZE)
        val key = ArtworkRequestKey(
            source = ArtworkSourceKey(path, fileModifiedAt, libraryRevision),
            targetSize = safeTargetSize
        )
        cache.get(key)?.let { cached -> callback(path, cached.bitmap); return }
        val shouldSubmit = synchronized(requestLock) {
            val callbacks = inFlight[key]
            if (callbacks != null) {
                callbacks += callback
                false
            } else {
                inFlight[key] = mutableListOf(callback)
                true
            }
        }
        if (shouldSubmit) executor.execute(ArtworkTask(key, path, safeTargetSize))
    }

    private inner class ArtworkTask(
        private val key: ArtworkRequestKey,
        private val path: String,
        private val targetSize: Int
    ) : Runnable {
        override fun run() {
            val bitmap = read(key.source, targetSize)
            cache.put(key, CachedArtwork(bitmap))
            deliver(bitmap)
        }

        fun discard() = deliver(null)

        private fun deliver(bitmap: Bitmap?) {
            val callbacks = synchronized(requestLock) { inFlight.remove(key) }.orEmpty()
            if (callbacks.isNotEmpty()) {
                mainHandler.post { callbacks.forEach { it(path, bitmap) } }
            }
        }
    }

    fun trimMemory() {
        cache.evictAll()
        sourceResolver.clear()
    }

    private fun read(source: ArtworkSourceKey, targetSize: Int): Bitmap? =
        sourceResolver.resolve(source, targetSize, ::decode)

    private fun decode(bytes: ByteArray, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        } catch (_: RuntimeException) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > MAX_IMAGE_DIMENSION || bounds.outHeight > MAX_IMAGE_DIMENSION
        ) return null
        var sample = 1
        while (bounds.outWidth / sample > targetSize * 2 || bounds.outHeight / sample > targetSize * 2) {
            sample *= 2
        }
        return try {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceAtLeast(1)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private data class CachedArtwork(val bitmap: Bitmap?)

    companion object {
        private const val DEFAULT_CACHE_BYTES = 2 * 1024 * 1024
        private const val MIN_CACHE_BYTES = 256 * 1024
        private const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024
        private const val MAX_PENDING_TASKS = 4
        private const val MIN_TARGET_SIZE = 32
        private const val MAX_TARGET_SIZE = 1024
        private const val MAX_IMAGE_DIMENSION = 16_384
    }
}
