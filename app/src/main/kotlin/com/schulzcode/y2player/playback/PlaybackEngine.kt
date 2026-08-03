package com.schulzcode.y2player.playback

import com.schulzcode.y2player.core.model.Track

enum class EngineState { EMPTY, PREPARING, READY, PLAYING, PAUSED, ERROR, RELEASED }

interface PlaybackEngine {
    interface Listener {
        fun onPrepared(requestId: Long, durationMs: Long)

        fun onStarted(requestId: Long)

        fun onNextTrackNeeded(currentRequestId: Long)

        fun onNextPrepared(requestId: Long, durationMs: Long)

        fun onTrackPromoted(previousRequestId: Long, promotedRequestId: Long, durationMs: Long)

        fun onTransitioned(requestId: Long, durationMs: Long)
        fun onCompleted(requestId: Long)
        fun onError(requestId: Long, message: String, failure: PlaybackFailure = PlaybackFailure.UNKNOWN)
        fun onNextError(requestId: Long, message: String)
    }

    val state: EngineState
    val audioSessionId: Int

    val isTransitioning: Boolean

    fun setListener(listener: Listener)

    fun configureTransition(gaplessEnabled: Boolean, crossfadeMs: Long)

    fun prepare(track: Track, requestId: Long)
    fun prepareNext(track: Track, requestId: Long)
    fun clearNext()

    fun skipToPreparedNext(): Boolean
    fun cancel()
    fun start()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setOutputGain(gain: Float, onApplied: (() -> Unit)? = null)

    fun setBalance(balance: Int)

    fun configureReplayGain(mode: ReplayGainMode, shuffling: Boolean)
    fun currentPositionMs(): Long
    fun durationMs(): Long
    fun isPlaying(): Boolean
    fun release()
}

internal class UnavailablePlaybackEngine(private val reason: String) : PlaybackEngine {
    private var listener: PlaybackEngine.Listener? = null
    override val state: EngineState = EngineState.ERROR
    override val audioSessionId: Int = 0
    override val isTransitioning: Boolean = false
    override fun setListener(listener: PlaybackEngine.Listener) { this.listener = listener }
    override fun prepare(track: Track, requestId: Long) { listener?.onError(requestId, reason) }
    override fun configureTransition(gaplessEnabled: Boolean, crossfadeMs: Long) = Unit
    override fun prepareNext(track: Track, requestId: Long) { listener?.onNextError(requestId, reason) }
    override fun clearNext() = Unit
    override fun skipToPreparedNext(): Boolean = false
    override fun cancel() = Unit
    override fun start() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setOutputGain(gain: Float, onApplied: (() -> Unit)?) { onApplied?.invoke() }
    override fun setBalance(balance: Int) = Unit
    override fun configureReplayGain(mode: ReplayGainMode, shuffling: Boolean) = Unit
    override fun currentPositionMs(): Long = 0
    override fun durationMs(): Long = 0
    override fun isPlaying(): Boolean = false
    override fun release() = Unit
}
