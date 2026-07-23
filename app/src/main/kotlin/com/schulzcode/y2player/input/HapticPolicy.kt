package com.schulzcode.y2player.input

/**
 * Wheel haptic strength.
 *
 * ## What "strength" actually means on this device
 * API 19 has only `Vibrator.vibrate(long)`. There is no amplitude control —
 * `VibrationEffect.createOneShot(ms, amplitude)` arrived in API 26, and the Y2's
 * eccentric-rotating-mass motor has no amplitude input anyway. The only variable
 * is **how long the motor is energised**, and because an ERM takes time to spin
 * up, a longer pulse both lasts longer and reaches a higher peak. So the levels
 * below are pulse durations, and the UI says so rather than pretending to be an
 * intensity control.
 *
 * [LIGHT] is 12 ms because shorter pulses frequently fail to move an ERM at all;
 * [STRONG] stops at 30 ms because beyond roughly that point the pulse stops
 * feeling like a detent and starts feeling like a buzz, and each millisecond is
 * motor current drawn from a small battery.
 */
enum class HapticLevel(val storageId: String, val label: String, val durationMs: Long) {
    OFF("off", "Off", 0L),
    LIGHT("light", "Light", 12L),
    MEDIUM("medium", "Medium", 20L),
    STRONG("strong", "Strong", 30L);

    val enabled: Boolean get() = durationMs > 0L

    fun next(): HapticLevel = values()[(ordinal + 1) % values().size]

    companion object {
        /** Unknown or absent stored values fall back to Off, never to a buzzing device. */
        fun fromStorage(value: String?): HapticLevel =
            values().firstOrNull { it.storageId == value || it.name == value } ?: OFF
    }
}

/**
 * Decides whether a wheel detent earns a pulse, and how often pulses may fire.
 *
 * Pure and host-JVM testable: time is passed in, never read. The caller supplies
 * `SystemClock.uptimeMillis()`.
 */
class HapticRateLimiter(private val minIntervalMs: Long = MIN_INTERVAL_MS) {

    private var lastPulseAt = Long.MIN_VALUE
    private var suppressed = 0
    private var pulses = 0

    /**
     * True when a pulse may fire now.
     *
     * A fast wheel spin can emit detents every ~20 ms. The motor cannot follow
     * that — pulses would overlap into one continuous buzz and, worse, queue
     * behind each other so the device keeps vibrating after the wheel stops.
     * Dropping intermediate detents keeps the feedback aligned with the wheel and
     * bounds the work done per spin. Scrolling itself is never delayed: this
     * decides only whether to vibrate.
     */
    fun allow(nowMs: Long): Boolean {
        if (lastPulseAt != Long.MIN_VALUE && nowMs - lastPulseAt < minIntervalMs) {
            suppressed++
            return false
        }
        lastPulseAt = nowMs
        pulses++
        return true
    }

    /** Clears the window; used when input stops so the next detent is immediate. */
    fun reset() { lastPulseAt = Long.MIN_VALUE }

    fun pulseCount(): Int = pulses
    fun suppressedCount(): Int = suppressed

    /** Reads and zeroes the counters for one aggregated log line. */
    fun drainCounters(): IntArray {
        val values = intArrayOf(pulses, suppressed)
        pulses = 0
        suppressed = 0
        return values
    }

    companion object {
        /**
         * ~18 pulses/second maximum. Chosen to exceed a comfortable scroll rate
         * (roughly 8–10 detents/second) while cutting a fast spin down to a
         * distinct tick rather than a continuous buzz.
         */
        const val MIN_INTERVAL_MS = 55L
    }
}

/** Pure decision rules for wheel feedback. */
object HapticPolicy {

    /**
     * A detent deserves feedback only when it did something.
     *
     * [stateChanged] is reference inequality of the reduced state: the reducer
     * returns the same instance when a wheel event changes nothing (an empty
     * list, a single-row screen at its bound). On Now Playing the wheel adjusts
     * volume, which is carried out by an effect rather than a state change, so
     * that screen is accepted explicitly.
     *
     * Duplicate and bounced key events never reach this point — HardwareKeyGate
     * has already dropped them.
     */
    fun shouldPulse(level: HapticLevel, available: Boolean, onNowPlaying: Boolean, stateChanged: Boolean): Boolean =
        available && level.enabled && (stateChanged || onNowPlaying)
}
