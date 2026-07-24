package com.schulzcode.y2player.playback

/**
 * Decides when the next track should be prepared ahead of time.
 *
 * Preparing the next player allocates a second MediaPlayer and opens the file,
 * so doing it at the start of a long track keeps that memory and file handle
 * pinned for minutes of idle playback. Instead the next track is prepared only
 * once the current one is near its end — far enough ahead that even a slow SD
 * card finishes preparing before the transition, but not the whole track early.
 *
 * The window widens for crossfade: the transition has to begin `crossfadeMs`
 * before the end, so preparation must be complete before that, with a safety
 * margin on top. A very short track is already inside the window from the start
 * and simply preloads on the first eligible tick.
 */
object NearEndPreloadPolicy {

    const val NEAR_END_PRELOAD_WINDOW_MS = 30_000L
    const val PRELOAD_SAFETY_MARGIN_MS = 5_000L

    /** How close to the end preparation must begin, accounting for crossfade. */
    fun effectiveThresholdMs(crossfadeMs: Long): Long =
        maxOf(NEAR_END_PRELOAD_WINDOW_MS, crossfadeMs + PRELOAD_SAFETY_MARGIN_MS)

    fun isWithinWindow(remainingMs: Long, crossfadeMs: Long): Boolean =
        remainingMs > 0L && remainingMs <= effectiveThresholdMs(crossfadeMs)

    data class Inputs(
        /** Playback is actively playing (not paused, preparing, or stopped). */
        val isPlaying: Boolean,
        /** A current track is loaded. */
        val hasCurrentTrack: Boolean,
        /** A valid next queue item exists to prepare. */
        val hasNextItem: Boolean,
        /** Repeat One replays the current track, so there is nothing to preload. */
        val repeatOne: Boolean,
        /** The sleep timer requires stopping after the current track. */
        val stopAfterCurrent: Boolean,
        /** A next player is already prepared or preparing. */
        val alreadyPreparedOrPreparing: Boolean,
        /** A transition/crossfade is already running. */
        val transitioning: Boolean,
        /** A preload was already attempted for the current track (do not retry every tick). */
        val attemptedForThisRequest: Boolean,
        /** Milliseconds left in the current track. */
        val remainingMs: Long,
        /** Configured crossfade length. */
        val crossfadeMs: Long
    )

    fun shouldPreload(inputs: Inputs): Boolean {
        if (!inputs.isPlaying) return false
        if (!inputs.hasCurrentTrack) return false
        if (!inputs.hasNextItem) return false
        if (inputs.repeatOne) return false
        if (inputs.stopAfterCurrent) return false
        if (inputs.alreadyPreparedOrPreparing) return false
        if (inputs.transitioning) return false
        if (inputs.attemptedForThisRequest) return false
        return isWithinWindow(inputs.remainingMs, inputs.crossfadeMs)
    }
}
