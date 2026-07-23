package com.schulzcode.y2player.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AlbumArtworkLoader(cacheBytes: Int = DEFAULT_CACHE_BYTES) {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(1),
        { runnable -> Thread(runnable, "y2-artwork").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val cache = object : LruCache<String, Bitmap>(cacheBytes.coerceAtLeast(MIN_CACHE_BYTES)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * One loader instance is shared process-wide (see AppContainer): the Now Playing
     * view and the RemoteControlClient both request the same 256 px decode per track,
     * so sharing halves the retriever/decode work at every track change. Cache keys
     * include the target size so differently sized requests can never collide.
     */
    fun load(path: String, targetSize: Int, callback: (String, Bitmap?) -> Unit) {
        if (closed.get()) return
        val safeTargetSize = targetSize.coerceIn(MIN_TARGET_SIZE, MAX_TARGET_SIZE)
        val key = "$path#$safeTargetSize"
        cache.get(key)?.let { bitmap -> callback(path, bitmap); return }
        executor.execute {
            if (closed.get()) return@execute
            val bitmap = read(path, safeTargetSize)
            if (!closed.get() && bitmap != null) cache.put(key, bitmap)
            if (!closed.get()) mainHandler.post { if (!closed.get()) callback(path, bitmap) }
        }
    }

    fun trimMemory() { cache.evictAll() }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        cache.evictAll()
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun read(path: String, targetSize: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val bytes = retriever.embeddedPicture ?: return null
            if (bytes.size > MAX_EMBEDDED_ART_BYTES) return null
            val safeTargetSize = targetSize
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth > MAX_IMAGE_DIMENSION || bounds.outHeight > MAX_IMAGE_DIMENSION
            ) return null
            var sample = 1
            while (bounds.outWidth / sample > safeTargetSize * 2 || bounds.outHeight / sample > safeTargetSize * 2) {
                sample *= 2
            }
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceAtLeast(1)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
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
