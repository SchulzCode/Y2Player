package com.schulzcode.y2player.input

internal class WheelAcceleration {
    private var lastEventTimeMs = Long.MIN_VALUE
    private var lastDirection = 0
    private var fastEventCount = 0
    private var alphabetScrubbing = false

    fun movement(
        direction: Int,
        eventTimeMs: Long,
        enabled: Boolean,
        alphabetEnabled: Boolean
    ): WheelMovement {
        val normalizedDirection = if (direction < 0) -1 else 1
        if (!enabled) {
            reset()
            return WheelMovement.Rows(normalizedDirection)
        }

        val interval = if (lastEventTimeMs == Long.MIN_VALUE) Long.MAX_VALUE
        else (eventTimeMs - lastEventTimeMs).coerceAtLeast(0L)

        if (alphabetScrubbing) {
            if (alphabetEnabled && interval <= ALPHABET_EXIT_INTERVAL_MS) {
                lastDirection = normalizedDirection
                lastEventTimeMs = eventTimeMs
                return WheelMovement.Alphabet(normalizedDirection)
            }
            alphabetScrubbing = false
        }

        if (normalizedDirection != lastDirection || interval > MEDIUM_INTERVAL_MS) {
            fastEventCount = 0
        } else {
            fastEventCount += 1
        }
        lastDirection = normalizedDirection
        lastEventTimeMs = eventTimeMs

        if (alphabetEnabled && interval <= ALPHABET_INTERVAL_MS &&
            fastEventCount >= FAST_EVENTS_FOR_ALPHABET
        ) {
            alphabetScrubbing = true
            return WheelMovement.Alphabet(normalizedDirection)
        }

        val step = when {
            interval <= FAST_INTERVAL_MS && fastEventCount >= FAST_EVENTS_FOR_LARGE_STEP -> LARGE_STEP
            interval <= MEDIUM_INTERVAL_MS && fastEventCount >= FAST_EVENTS_FOR_SMALL_STEP -> SMALL_STEP
            else -> 1
        }
        return WheelMovement.Rows(normalizedDirection * step)
    }

    fun delta(direction: Int, eventTimeMs: Long, enabled: Boolean): Int {
        return (movement(direction, eventTimeMs, enabled, alphabetEnabled = false) as WheelMovement.Rows).delta
    }

    fun reset() {
        lastEventTimeMs = Long.MIN_VALUE
        lastDirection = 0
        fastEventCount = 0
        alphabetScrubbing = false
    }

    companion object {
        // One slower interval restores precise movement instead of leaving a
        // decaying accumulator that could jump after the user stops.
        const val MEDIUM_INTERVAL_MS = 180L
        const val FAST_INTERVAL_MS = 90L
        // Require about 400 ms of very fast movement before entering. The wider
        // exit interval adds enough hysteresis that one uneven detent does not flicker modes.
        const val ALPHABET_INTERVAL_MS = 65L
        const val ALPHABET_EXIT_INTERVAL_MS = 130L
        const val SMALL_STEP = 3
        const val LARGE_STEP = 5
        private const val FAST_EVENTS_FOR_SMALL_STEP = 2
        private const val FAST_EVENTS_FOR_LARGE_STEP = 4
        private const val FAST_EVENTS_FOR_ALPHABET = 8
    }
}

internal sealed interface WheelMovement {
    data class Rows(val delta: Int) : WheelMovement
    data class Alphabet(val direction: Int) : WheelMovement
}
