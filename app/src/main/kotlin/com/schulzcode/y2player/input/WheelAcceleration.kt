package com.schulzcode.y2player.input

internal class WheelAcceleration {
    private var lastEventTimeMs = Long.MIN_VALUE
    private var lastDirection = 0
    private var fastEventCount = 0

    fun delta(direction: Int, eventTimeMs: Long, enabled: Boolean): Int {
        val normalizedDirection = if (direction < 0) -1 else 1
        if (!enabled) {
            reset()
            return normalizedDirection
        }

        val interval = if (lastEventTimeMs == Long.MIN_VALUE) Long.MAX_VALUE
        else (eventTimeMs - lastEventTimeMs).coerceAtLeast(0L)
        if (normalizedDirection != lastDirection || interval > MEDIUM_INTERVAL_MS) {
            fastEventCount = 0
        } else {
            fastEventCount += 1
        }
        lastDirection = normalizedDirection
        lastEventTimeMs = eventTimeMs

        val step = when {
            interval <= FAST_INTERVAL_MS && fastEventCount >= FAST_EVENTS_FOR_LARGE_STEP -> LARGE_STEP
            interval <= MEDIUM_INTERVAL_MS && fastEventCount >= FAST_EVENTS_FOR_SMALL_STEP -> SMALL_STEP
            else -> 1
        }
        return normalizedDirection * step
    }

    fun reset() {
        lastEventTimeMs = Long.MIN_VALUE
        lastDirection = 0
        fastEventCount = 0
    }

    companion object {
        // One slower interval restores precise movement instead of leaving a
        // decaying accumulator that could jump after the user stops.
        const val MEDIUM_INTERVAL_MS = 180L
        const val FAST_INTERVAL_MS = 90L
        const val SMALL_STEP = 3
        const val LARGE_STEP = 5
        private const val FAST_EVENTS_FOR_SMALL_STEP = 2
        private const val FAST_EVENTS_FOR_LARGE_STEP = 4
    }
}
