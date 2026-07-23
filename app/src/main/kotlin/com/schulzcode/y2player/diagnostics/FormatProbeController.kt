package com.schulzcode.y2player.diagnostics

import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.schulzcode.y2player.core.model.Track
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

@Suppress("DEPRECATION")
class FormatProbeController(
    private val logger: DiagnosticLogger,
    private val onFinished: (List<FormatProbeResult>) -> Unit,
    private val onCancelled: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val workerThread = HandlerThread("y2-format-probe").apply { start() }
    private val handler = Handler(workerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var queue: ArrayDeque<Track> = ArrayDeque()
    private val results = mutableListOf<FormatProbeResult>()
    private var player: MediaPlayer? = null
    @Volatile private var running = false
    private var current: Track? = null
    // Advanced from both the worker thread (next) and the caller thread
    // (cancel/shutdown), so the increment must be atomic.
    private val generation = java.util.concurrent.atomic.AtomicLong(0)
    private var currentCompleted = false
    private var timeoutRunnable: Runnable? = null
    private var playWindowRunnable: Runnable? = null
    private var seekTimeoutRunnable: Runnable? = null

    fun start(tracks: List<Track>): Boolean {
        synchronized(this) {
            if (running) return false
            running = true
        }
        handler.post {
            val samples = selectSamples(tracks) { File(it.absolutePath).canRead() }
            if (samples.isEmpty()) {
                running = false
                mainHandler.post { onError("No readable tracks are available for format testing") }
            } else {
                results.clear()
                queue = ArrayDeque(samples)
                logger.info("FormatProbe", "starting ${samples.size} format samples")
                next()
            }
        }
        return true
    }

    fun cancel() {
        if (!running) return
        logger.warn("FormatProbe", "probe cancelled")
        generation.incrementAndGet()
        running = false
        handler.post {
            clearCallbacks()
            releasePlayer()
            current = null
            queue.clear()
            mainHandler.post(onCancelled)
        }
    }

    fun shutdown() {
        generation.incrementAndGet()
        running = false
        handler.post {
            clearCallbacks()
            releasePlayer()
            current = null
            queue.clear()
            workerThread.quitSafely()
        }
    }

    private fun next() {
        clearCallbacks()
        releasePlayer()
        if (!running) return
        val track = queue.pollFirst()
        if (track == null) {
            finish()
            return
        }
        current = track
        currentCompleted = false
        val token = generation.incrementAndGet()
        val extension = track.extension.ifBlank { "unknown" }.uppercase(Locale.US)
        val local = MediaPlayer()
        player = local
        try {
            local.setAudioStreamType(AudioManager.STREAM_MUSIC)
            local.setVolume(0f, 0f)
            local.setOnPreparedListener { prepared ->
                if (!isCurrent(token, prepared)) return@setOnPreparedListener
                timeoutRunnable?.let(handler::removeCallbacks)
                try {
                    val duration = prepared.duration.coerceAtLeast(0)
                    prepared.start()
                    playWindowRunnable = Runnable {
                        if (!isCurrent(token, prepared)) return@Runnable
                        try {
                            if (duration > 1_000) {
                                prepared.setOnSeekCompleteListener { seeked ->
                                    if (!isCurrent(token, seeked)) return@setOnSeekCompleteListener
                                    seekTimeoutRunnable?.let(handler::removeCallbacks)
                                    runCatching { seeked.pause() }
                                    record(token, true, "prepare/play/seek succeeded · ${duration} ms")
                                }
                                seekTimeoutRunnable = Runnable {
                                    record(token, false, "seek did not complete")
                                }.also { handler.postDelayed(it, SEEK_TIMEOUT_MS) }
                                prepared.seekTo((duration / 2).coerceAtMost(10_000))
                            } else {
                                prepared.pause()
                                record(token, true, "prepare/play succeeded · ${duration} ms")
                            }
                        } catch (error: Throwable) {
                            record(token, false, "runtime test failed: ${error.javaClass.simpleName}")
                        }
                    }.also { handler.postDelayed(it, PLAY_WINDOW_MS) }
                } catch (error: Throwable) {
                    record(token, false, "start failed: ${error.javaClass.simpleName}")
                }
            }
            local.setOnErrorListener { source, what, extra ->
                if (!isCurrent(token, source)) return@setOnErrorListener true
                record(token, false, "MediaPlayer error what=$what extra=$extra")
                true
            }
            local.setDataSource(track.absolutePath)
            local.prepareAsync()
            timeoutRunnable = Runnable {
                record(token, false, "timed out after ${TIMEOUT_MS / 1_000}s")
            }.also { handler.postDelayed(it, TIMEOUT_MS) }
            logger.info("FormatProbe", "testing $extension ${track.absolutePath}")
        } catch (error: Throwable) {
            record(token, false, "prepare failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        }
    }

    private fun record(token: Long, success: Boolean, message: String) {
        if (!running || token != generation.get() || currentCompleted) return
        currentCompleted = true
        clearCallbacks()
        val track = current ?: return
        val extension = track.extension.ifBlank { "unknown" }.uppercase(Locale.US)
        results += FormatProbeResult(extension, success, message)
        if (success) logger.info("FormatProbe", "$extension $message")
        else logger.warn("FormatProbe", "$extension $message")
        handler.post(::next)
    }

    private fun finish() {
        clearCallbacks()
        releasePlayer()
        running = false
        current = null
        val value = results.toList()
        mainHandler.post { onFinished(value) }
    }

    private fun isCurrent(token: Long, source: MediaPlayer): Boolean =
        running && !currentCompleted && token == generation.get() && source === player

    private fun clearCallbacks() {
        timeoutRunnable?.let(handler::removeCallbacks)
        playWindowRunnable?.let(handler::removeCallbacks)
        seekTimeoutRunnable?.let(handler::removeCallbacks)
        timeoutRunnable = null
        playWindowRunnable = null
        seekTimeoutRunnable = null
    }

    private fun releasePlayer() {
        val local = player
        player = null
        if (local != null) {
            runCatching { local.setOnPreparedListener(null) }
            runCatching { local.setOnSeekCompleteListener(null) }
            runCatching { local.setOnErrorListener(null) }
            runCatching { local.release() }
        }
    }

    companion object {
        internal fun selectSamples(tracks: List<Track>, canRead: (Track) -> Boolean): List<Track> {
            val samples = LinkedHashMap<String, Track>()
            tracks.forEach { track ->
                val extension = track.extension
                if (track.available && extension !in samples && canRead(track)) samples[extension] = track
            }
            return samples.values.sortedBy { it.extension }
        }

        private const val PLAY_WINDOW_MS = 700L
        private const val SEEK_TIMEOUT_MS = 2_000L
        private const val TIMEOUT_MS = 8_000L
    }
}
