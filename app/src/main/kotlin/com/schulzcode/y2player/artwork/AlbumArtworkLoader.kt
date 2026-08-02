package com.schulzcode.y2player.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.schulzcode.y2player.playback.NativeAudio
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AlbumArtworkLoader(cacheBytes: Int = DEFAULT_CACHE_BYTES) {
    private val requestLock = Any()
    private val inFlight = mutableMapOf<String, MutableList<(String, Bitmap?) -> Unit>>()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(1),
        { runnable -> Thread(runnable, "y2-artwork").apply { isDaemon = true } },
        RejectedExecutionHandler { runnable, pool ->
            (pool.queue.poll() as? ArtworkTask)?.discard()
            if (!pool.isShutdown) pool.execute(runnable)
        }
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(cacheBytes.coerceAtLeast(MIN_CACHE_BYTES)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(path: String, targetSize: Int, callback: (String, Bitmap?) -> Unit) {
        val safeTargetSize = targetSize.coerceIn(MIN_TARGET_SIZE, MAX_TARGET_SIZE)
        val key = "$path#$safeTargetSize"
        cache.get(key)?.let { bitmap -> callback(path, bitmap); return }
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
        private val key: String,
        private val path: String,
        private val targetSize: Int
    ) : Runnable {
        override fun run() {
            val bitmap = read(path, targetSize)
            if (bitmap != null) cache.put(key, bitmap)
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

    fun trimMemory() { cache.evictAll() }

    private fun read(path: String, targetSize: Int): Bitmap? {
        val bytes = runCatching {
            NativeAudio.nativeReadArtwork(path, MAX_EMBEDDED_ART_BYTES)
        }.getOrNull() ?: return null
        if (bytes.size > MAX_EMBEDDED_ART_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > MAX_IMAGE_DIMENSION || bounds.outHeight > MAX_IMAGE_DIMENSION
        ) return null
        var sample = 1
        while (bounds.outWidth / sample > targetSize * 2 || bounds.outHeight / sample > targetSize * 2) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }

    companion object {
        private const val DEFAULT_CACHE_BYTES = 2 * 1024 * 1024
        private const val MIN_CACHE_BYTES = 256 * 1024
        private const val MAX_EMBEDDED_ART_BYTES = 8 * 1024 * 1024
        private const val MIN_TARGET_SIZE = 32
        private const val MAX_TARGET_SIZE = 1024
        private const val MAX_IMAGE_DIMENSION = 16_384
    }
}
