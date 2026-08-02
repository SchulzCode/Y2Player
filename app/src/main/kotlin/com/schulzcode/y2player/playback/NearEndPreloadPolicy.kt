package com.schulzcode.y2player.playback

object NearEndPreloadPolicy {
    const val NEAR_END_PRELOAD_WINDOW_MS = 30_000L
    const val PRELOAD_SAFETY_MARGIN_MS = 5_000L

    fun effectiveThresholdMs(crossfadeMs: Long): Long =
        maxOf(NEAR_END_PRELOAD_WINDOW_MS, crossfadeMs.coerceAtLeast(0L) + PRELOAD_SAFETY_MARGIN_MS)

    fun isWithinWindow(remainingMs: Long, crossfadeMs: Long): Boolean =
        remainingMs > 0L && remainingMs <= effectiveThresholdMs(crossfadeMs)
}
