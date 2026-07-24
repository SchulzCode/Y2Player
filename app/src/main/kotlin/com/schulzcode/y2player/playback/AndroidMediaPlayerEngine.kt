package com.schulzcode.y2player.playback

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.diagnostics.DiagnosticLogger

/**
 * Creates the next owner before releasing the current one. Some API-19 vendor
 * audio stacks tear down a session's effect chain as soon as its last player
 * disappears, even when a later player reuses the same numeric session ID.
 */
internal inline fun <T> replaceSessionOwner(current: T, create: () -> T, release: (T) -> Unit): T {
    val replacement = create()
    release(current)
    return replacement
}

@Suppress("DEPRECATION")
class AndroidMediaPlayerEngine(
    context: Context,
    private val logger: DiagnosticLogger
) : PlaybackEngine {
    private data class Slot(
        val player: MediaPlayer,
        val requestId: Long,
        var state: EngineState = EngineState.EMPTY,
        var durationMs: Long = 0
    )

    private val appContext = context.applicationContext
    private val callbackHandler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
    private var listener: PlaybackEngine.Listener? = null
    private var current = Slot(createPlayer(null), 0)
    private var next: Slot? = null
    private var nextGapless = false
    private var gaplessLinked = false
    private var masterVolume = 1f
    private var transitionFraction = 0f
    private var transitionGeneration = 0L

    override val audioSessionId: Int = current.player.audioSessionId

    @Volatile
    override var state: EngineState = EngineState.EMPTY
        private set

    @Volatile
    override var isTransitioning: Boolean = false
        private set

    override fun setListener(listener: PlaybackEngine.Listener) { this.listener = listener }

    override fun prepare(track: Track, requestId: Long) {
        if (state == EngineState.RELEASED) return
        try {
            cancelTransition(discardNext = true)
            current = replaceSessionOwner(
                current = current,
                create = { Slot(createPlayer(audioSessionId), requestId, EngineState.PREPARING) },
                release = ::releaseSlot
            )
            state = EngineState.PREPARING
            logger.info("PlaybackEngine", "prepare request=$requestId ${track.absolutePath}")
            current.player.setDataSource(track.absolutePath)
            current.player.prepareAsync()
        } catch (error: Exception) {
            failCurrent(requestId, "prepare failed", error)
        }
    }

    override fun prepareNext(track: Track, requestId: Long, gapless: Boolean) {
        if (state == EngineState.RELEASED) return
        clearNext()
        nextGapless = gapless
        try {
            val slot = Slot(createPlayer(audioSessionId), requestId, EngineState.PREPARING)
            next = slot
            logger.info("PlaybackEngine", "preload request=$requestId gapless=$gapless ${track.absolutePath}")
            slot.player.setDataSource(track.absolutePath)
            slot.player.prepareAsync()
        } catch (error: Exception) {
            failNext(requestId, "preload failed", error)
        }
    }

    override fun clearNext() {
        transitionGeneration += 1
        isTransitioning = false
        transitionFraction = 0f
        runCatching { current.player.setNextMediaPlayer(null) }
        gaplessLinked = false
        next?.let(::releaseSlot)
        next = null
        applyVolumes()
    }

    override fun hasPreparedNext(requestId: Long): Boolean = next?.let {
        it.requestId == requestId && (it.state == EngineState.READY || (isTransitioning && it.state == EngineState.PLAYING))
    } == true

    override fun startPreparedNext(crossfadeMs: Long): Boolean {
        val prepared = next ?: return false
        if (state == EngineState.RELEASED) return false
        if (isTransitioning && prepared.state == EngineState.PLAYING && crossfadeMs <= 0) {
            transitionGeneration += 1
            isTransitioning = false
            transitionFraction = 0f
            releaseSlot(current)
            current = prepared
            next = null
            current.state = EngineState.PLAYING
            state = EngineState.PLAYING
            applyVolumes()
            listener?.onTransitioned(current.requestId, current.durationMs)
            return true
        }
        if (prepared.state != EngineState.READY) return false
        if (crossfadeMs > 0 && current.state == EngineState.PLAYING) return beginCrossfade(crossfadeMs)

        transitionGeneration += 1
        runCatching { current.player.setNextMediaPlayer(null) }
        gaplessLinked = false
        releaseSlot(current)
        current = prepared
        next = null
        return runCatching {
            current.player.setVolume(masterVolume, masterVolume)
            current.player.start()
            current.state = EngineState.PLAYING
            state = EngineState.PLAYING
            listener?.onTransitioned(current.requestId, current.durationMs)
            true
        }.getOrElse {
            failCurrent(current.requestId, "starting preloaded track failed", it)
            false
        }
    }

    override fun cancel() {
        if (state == EngineState.RELEASED) return
        cancelTransition(discardNext = true)
        runCatching {
            replaceSessionOwner(
                current = current,
                create = { Slot(createPlayer(audioSessionId), current.requestId + 1) },
                release = ::releaseSlot
            )
        }
            .onSuccess { current = it; state = EngineState.EMPTY }
            .onFailure {
                // Creation failed, so continuity is no longer possible. Still
                // release the old player to honor cancel's resource contract.
                releaseSlot(current)
                state = EngineState.ERROR
                logger.error("PlaybackEngine", "player recreation failed", it)
            }
    }

    override fun start() {
        if (state !in STARTABLE_STATES) return
        runCatching {
            current.player.start()
            current.state = EngineState.PLAYING
            state = EngineState.PLAYING
            applyVolumes()
        }.onFailure { failCurrent(current.requestId, "start failed", it) }
    }

    override fun pause() {
        if (state !in STARTABLE_STATES) return
        if (isTransitioning) cancelTransition(discardNext = true)
        runCatching {
            if (current.player.isPlaying) current.player.pause()
            current.state = EngineState.PAUSED
            state = EngineState.PAUSED
            applyVolumes()
        }.onFailure { failCurrent(current.requestId, "pause failed", it) }
    }


    override fun seekTo(positionMs: Long) {
        if (state !in PLAYABLE_STATES) return
        val target = positionMs.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        runCatching { current.player.seekTo(target) }.onFailure { failCurrent(current.requestId, "seek failed", it) }
    }

    override fun setVolume(volume: Float) {
        if (state == EngineState.RELEASED) return
        masterVolume = volume.coerceIn(0f, 1f)
        applyVolumes()
    }

    override fun currentPositionMs(): Long = if (state in PLAYABLE_STATES) {
        runCatching { current.player.currentPosition.toLong() }.getOrDefault(0L)
    } else 0L

    override fun durationMs(): Long = if (state in PLAYABLE_STATES) {
        current.durationMs.takeIf { it > 0 } ?: runCatching { current.player.duration.toLong() }.getOrDefault(0L)
    } else 0L

    override fun isPlaying(): Boolean = state == EngineState.PLAYING &&
        runCatching { current.player.isPlaying }.getOrDefault(false)

    override fun release() {
        if (state == EngineState.RELEASED) return
        transitionGeneration += 1
        isTransitioning = false
        next?.let(::releaseSlot)
        next = null
        releaseSlot(current)
        callbackHandler.removeCallbacksAndMessages(null)
        state = EngineState.RELEASED
        logger.info("PlaybackEngine", "released")
    }

    private fun beginCrossfade(durationMs: Long): Boolean {
        val prepared = next ?: return false
        if (prepared.state != EngineState.READY || isTransitioning) return false
        val safeDuration = durationMs.coerceAtLeast(100L)
        return runCatching {
            runCatching { current.player.setNextMediaPlayer(null) }
            gaplessLinked = false
            transitionFraction = 0f
            isTransitioning = true
            prepared.player.setVolume(0f, 0f)
            prepared.player.start()
            prepared.state = EngineState.PLAYING
            val generation = ++transitionGeneration
            val startedAt = android.os.SystemClock.uptimeMillis()
            val step = object : Runnable {
                override fun run() {
                    if (!isTransitioning || generation != transitionGeneration || next !== prepared) return
                    val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
                    transitionFraction = (elapsed.toFloat() / safeDuration).coerceIn(0f, 1f)
                    applyVolumes()
                    if (transitionFraction >= 1f) finishCrossfade(prepared)
                    else callbackHandler.postDelayed(this, CROSSFADE_STEP_MS)
                }
            }
            callbackHandler.post(step)
            true
        }.getOrElse {
            isTransitioning = false
            failNext(prepared.requestId, "crossfade start failed", it)
            false
        }
    }

    private fun finishCrossfade(prepared: Slot) {
        if (next !== prepared) return
        isTransitioning = false
        transitionFraction = 0f
        releaseSlot(current)
        current = prepared
        next = null
        current.state = EngineState.PLAYING
        state = EngineState.PLAYING
        applyVolumes()
        listener?.onTransitioned(current.requestId, current.durationMs)
    }

    private fun cancelTransition(discardNext: Boolean) {
        if (!isTransitioning && !discardNext) return
        transitionGeneration += 1
        isTransitioning = false
        transitionFraction = 0f
        if (discardNext) clearNext() else applyVolumes()
    }

    private fun applyVolumes() {
        val currentFactor = if (isTransitioning) 1f - transitionFraction else 1f
        val nextFactor = if (isTransitioning) transitionFraction else 1f
        runCatching { current.player.setVolume(masterVolume * currentFactor, masterVolume * currentFactor) }
        next?.let { slot ->
            runCatching { slot.player.setVolume(masterVolume * nextFactor, masterVolume * nextFactor) }
        }
    }

    private fun createPlayer(sessionId: Int?): MediaPlayer {
        val player = MediaPlayer()
        return try {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
            if (sessionId != null && sessionId > 0) player.audioSessionId = sessionId
            player.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
            player.setOnPreparedListener { source -> handlePrepared(source) }
            player.setOnCompletionListener { source -> handleCompletion(source) }
            player.setOnErrorListener { source, what, extra -> handleError(source, what, extra) }
            player
        } catch (error: Throwable) {
            runCatching { player.release() }
            throw error
        }
    }

    private fun handlePrepared(source: MediaPlayer) {
        if (state == EngineState.RELEASED) return
        if (source === current.player) {
            current.state = EngineState.READY
            current.durationMs = runCatching { source.duration.toLong() }.getOrDefault(0L)
            state = EngineState.READY
            applyVolumes()
            logger.info("PlaybackEngine", "prepared request=${current.requestId} duration=${current.durationMs}")
            listener?.onPrepared(current.requestId, current.durationMs)
            maybeLinkGapless()
            return
        }
        val prepared = next
        if (prepared != null && source === prepared.player) {
            prepared.state = EngineState.READY
            prepared.durationMs = runCatching { source.duration.toLong() }.getOrDefault(0L)
            applyVolumes()
            logger.info("PlaybackEngine", "preloaded request=${prepared.requestId} duration=${prepared.durationMs}")
            maybeLinkGapless()
            listener?.onNextPrepared(prepared.requestId, prepared.durationMs)
        }
    }

    private fun maybeLinkGapless() {
        val prepared = next ?: return
        if (!nextGapless || prepared.state != EngineState.READY || current.state !in PLAYABLE_STATES) return
        gaplessLinked = runCatching {
            current.player.setNextMediaPlayer(prepared.player)
            true
        }.getOrElse {
            logger.warn("PlaybackEngine", "setNextMediaPlayer failed: ${it.message}")
            false
        }
    }

    private fun handleCompletion(source: MediaPlayer) {
        if (state == EngineState.RELEASED) return
        val transitioningNext = next
        if (isTransitioning && transitioningNext != null && source === transitioningNext.player) {
            finishCrossfade(transitioningNext)
            current.state = EngineState.READY
            state = EngineState.READY
            listener?.onCompleted(current.requestId)
            return
        }
        if (source !== current.player || isTransitioning) return
        val prepared = next
        if (gaplessLinked && prepared != null && prepared.state in LINKABLE_NEXT_STATES) {
            gaplessLinked = false
            releaseSlot(current)
            current = prepared
            next = null
            current.state = EngineState.PLAYING
            state = EngineState.PLAYING
            applyVolumes()
            listener?.onTransitioned(current.requestId, current.durationMs)
        } else {
            current.state = EngineState.READY
            state = EngineState.READY
            listener?.onCompleted(current.requestId)
        }
    }

    private fun handleError(source: MediaPlayer, what: Int, extra: Int): Boolean {
        val message = "MediaPlayer error what=$what extra=$extra"
        if (source === current.player) {
            current.state = EngineState.ERROR
            state = EngineState.ERROR
            logger.error("PlaybackEngine", "$message request=${current.requestId}")
            listener?.onError(current.requestId, message)
            return true
        }
        val failed = next
        if (failed != null && source === failed.player) {
            failed.state = EngineState.ERROR
            logger.error("PlaybackEngine", "$message preload=${failed.requestId}")
            listener?.onNextError(failed.requestId, message)
            return true
        }
        // Neither slot: a player that was released or replaced is still reporting.
        // Nothing can be recovered, but silence here is the one case where a
        // vendor stack misbehaving leaves no trace at all.
        logger.warn("PlaybackEngine", "$message from an unowned player (state=$state)")
        return true
    }

    private fun releaseSlot(slot: Slot) {
        runCatching { slot.player.setOnPreparedListener(null) }
        runCatching { slot.player.setOnCompletionListener(null) }
        runCatching { slot.player.setOnErrorListener(null) }
        runCatching { slot.player.setNextMediaPlayer(null) }
        runCatching { slot.player.reset() }
        runCatching { slot.player.release() }
    }

    private fun failCurrent(requestId: Long, prefix: String, error: Throwable) {
        current.state = EngineState.ERROR
        state = EngineState.ERROR
        logger.error("PlaybackEngine", prefix, error)
        listener?.onError(requestId, "$prefix: ${error.message ?: error.javaClass.simpleName}")
    }

    private fun failNext(requestId: Long, prefix: String, error: Throwable) {
        next?.state = EngineState.ERROR
        logger.error("PlaybackEngine", prefix, error)
        listener?.onNextError(requestId, "$prefix: ${error.message ?: error.javaClass.simpleName}")
    }

    companion object {
        private const val CROSSFADE_STEP_MS = 50L
        private val PLAYABLE_STATES = setOf(EngineState.READY, EngineState.PLAYING, EngineState.PAUSED)
        // Hoisted alongside PLAYABLE_STATES: these were allocated on every
        // start, pause and completion callback.
        private val STARTABLE_STATES = setOf(EngineState.READY, EngineState.PAUSED, EngineState.PLAYING)
        private val LINKABLE_NEXT_STATES = setOf(EngineState.READY, EngineState.PLAYING)
    }
}
