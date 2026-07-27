package com.schulzcode.y2player.playback

/**
 * How close to the end of a track the next one must be prepared.
 *
 * Preparing the next track allocates a second native decoder and opens the file,
 * so doing it at the start of a long track pins that memory and file handle for
 * minutes of idle playback. It is prepared only once the current track is near
 * its end — far enough ahead that even a slow SD card finishes in time, but not
 * the whole track early.
 *
 * The window widens for crossfade: the fade has to begin `crossfadeMs` before
 * the end, so preparation must already be complete by then, with a margin. A
 * track shorter than the window is inside it from the first frame and simply
 * preloads straight away.
 *
 * This used to carry a ten-field `Inputs` record and a predicate, because the
 * service polled a position and had to re-state everything the engine already
 * knew — whether it was playing, whether a decoder was prepared, whether a
 * transition was running, whether it had already tried. The engine owns all of
 * that now and asks for the next track itself, so only the window survives.
 */
object NearEndPreloadPolicy {

    const val NEAR_END_PRELOAD_WINDOW_MS = 30_000L
    const val PRELOAD_SAFETY_MARGIN_MS = 5_000L

    /** How close to the end preparation must begin, accounting for crossfade. */
    fun effectiveThresholdMs(crossfadeMs: Long): Long =
        maxOf(NEAR_END_PRELOAD_WINDOW_MS, crossfadeMs.coerceAtLeast(0L) + PRELOAD_SAFETY_MARGIN_MS)

    fun isWithinWindow(remainingMs: Long, crossfadeMs: Long): Boolean =
        remainingMs > 0L && remainingMs <= effectiveThresholdMs(crossfadeMs)
}
