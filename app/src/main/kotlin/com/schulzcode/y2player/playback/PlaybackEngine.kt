package com.schulzcode.y2player.playback

import com.schulzcode.y2player.core.model.Track

enum class EngineState { EMPTY, PREPARING, READY, PLAYING, PAUSED, ERROR, RELEASED }

interface PlaybackEngine {
    interface Listener {
        fun onPrepared(requestId: Long, durationMs: Long)

        /**
         * Playback has actually begun: the output is playing and decoded PCM has
         * reached it.
         *
         * This exists because the engine is asynchronous and `state == PLAYING`
         * immediately after [start] proved nothing — the calling thread had just
         * written that value itself. Entering the foreground, recording a play
         * and publishing PLAYING all belong here, on evidence, rather than on
         * the assumption that the command succeeded.
         */
        fun onStarted(requestId: Long)

        /**
         * The engine is close enough to the end of [currentRequestId] that it
         * needs the next track now, and asks once per track.
         *
         * The split of responsibility: the engine knows *when*, down to the
         * frame, because it owns the decoder and the playback head. The service
         * knows *which*, because it owns the queue, shuffle, repeat, the sleep
         * timer and what is actually readable on disk. Neither has to model the
         * other's half any more, and the service no longer polls a position four
         * times a second to work out something the engine already knew.
         */
        fun onNextTrackNeeded(currentRequestId: Long)

        fun onNextPrepared(requestId: Long, durationMs: Long)

        /**
         * The engine has changed which decoder it owns, effective immediately.
         *
         * Deliberately separate from [onTransitioned]. Internal promotion and
         * the audible boundary are up to one AudioTrack buffer apart for a
         * gapless join, and during that window the service used to still
         * believe the old track was current — so a pause could run
         * `clearPreload()` and destroy the very request id [onTransitioned] is
         * gated on, stranding the queue and the UI on the previous track while
         * the new one played.
         *
         * The service must move ownership here — current track, active request,
         * queue position, preload bookkeeping — and nothing else. Notification,
         * foreground state and the published snapshot stay with [onTransitioned]
         * so that what the listener sees still matches what they hear.
         */
        fun onTrackPromoted(previousRequestId: Long, promotedRequestId: Long, durationMs: Long)

        /**
         * The promoted track is now audible: the engine defers this until the
         * AudioTrack playback head has reached the submitted boundary between
         * the two tracks.
         */
        fun onTransitioned(requestId: Long, durationMs: Long)
        fun onCompleted(requestId: Long)
        /**
         * [failure] tells the service whether the file itself is at fault. It
         * defaults to UNKNOWN so that internal callers raising their own errors
         * (a prepare timeout, an unusable duration) cannot accidentally condemn
         * a track they know nothing about.
         */
        fun onError(requestId: Long, message: String, failure: PlaybackFailure = PlaybackFailure.UNKNOWN)
        fun onNextError(requestId: Long, message: String)
    }

    val state: EngineState
    val audioSessionId: Int

    /**
     * True while two decoders are being mixed together. Read by the service for
     * resource decisions only — never for timing, which the engine now owns.
     */
    val isTransitioning: Boolean

    fun setListener(listener: Listener)

    /**
     * How the current track should be joined to the prepared next one.
     *
     * `crossfadeMs > 0` fades them together, ending at the current track's end.
     * Otherwise [gaplessEnabled] chooses between a seamless promotion at EOF and
     * an ordinary one after the output has drained.
     */
    fun configureTransition(gaplessEnabled: Boolean, crossfadeMs: Long)

    fun prepare(track: Track, requestId: Long)
    fun prepareNext(track: Track, requestId: Long)
    fun clearNext()

    /**
     * Switches to the prepared next track immediately, for an explicit skip.
     * Returns false when nothing is prepared. Timed transitions do not use this.
     */
    fun skipToPreparedNext(): Boolean
    fun cancel()
    fun start()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)

    /**
     * Left/right balance in `-100..100`; see [AudioBalance].
     *
     * Separate from [setVolume] because the two compose: volume is the ramping
     * value that fades and crossfades own, balance is a fixed per-channel scale on
     * top of it. Folding balance into the volume argument would have meant every
     * ramp step recomputing it, and a ramp that forgot to would silently recentre.
     */
    fun setBalance(balance: Int)
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
    override fun setVolume(volume: Float) = Unit
    override fun setBalance(balance: Int) = Unit
    override fun currentPositionMs(): Long = 0
    override fun durationMs(): Long = 0
    override fun isPlaying(): Boolean = false
    override fun release() = Unit
}
