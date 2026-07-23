package com.schulzcode.y2player.playback

import com.schulzcode.y2player.core.model.Track

enum class EngineState { EMPTY, PREPARING, READY, PLAYING, PAUSED, ERROR, RELEASED }

interface PlaybackEngine {
    interface Listener {
        fun onPrepared(requestId: Long, durationMs: Long)
        fun onNextPrepared(requestId: Long, durationMs: Long)
        fun onTransitioned(requestId: Long, durationMs: Long)
        fun onCompleted(requestId: Long)
        fun onError(requestId: Long, message: String)
        fun onNextError(requestId: Long, message: String)
    }

    val state: EngineState
    val audioSessionId: Int
    val isTransitioning: Boolean
    fun setListener(listener: Listener)
    fun prepare(track: Track, requestId: Long)
    fun prepareNext(track: Track, requestId: Long, gapless: Boolean)
    fun clearNext()
    fun hasPreparedNext(requestId: Long): Boolean
    fun startPreparedNext(crossfadeMs: Long = 0): Boolean
    fun cancel()
    fun start()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
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
    override fun prepareNext(track: Track, requestId: Long, gapless: Boolean) { listener?.onNextError(requestId, reason) }
    override fun clearNext() = Unit
    override fun hasPreparedNext(requestId: Long): Boolean = false
    override fun startPreparedNext(crossfadeMs: Long): Boolean = false
    override fun cancel() = Unit
    override fun start() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun currentPositionMs(): Long = 0
    override fun durationMs(): Long = 0
    override fun isPlaying(): Boolean = false
    override fun release() = Unit
}
