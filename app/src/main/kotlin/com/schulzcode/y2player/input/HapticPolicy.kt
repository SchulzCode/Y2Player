package com.schulzcode.y2player.input

// Durations, not intensities: API 19 has only Vibrator.vibrate(long). Under
// 12 ms an ERM often will not move; over 30 ms it buzzes instead of clicking.
enum class HapticLevel(val storageId: String, val label: String, val durationMs: Long) {
    OFF("off", "Off", 0L),
    LIGHT("light", "Light", 12L),
    MEDIUM("medium", "Medium", 20L),
    STRONG("strong", "Strong", 30L);

    val enabled: Boolean get() = durationMs > 0L

    fun next(): HapticLevel = values()[(ordinal + 1) % values().size]

    companion object {
        fun fromStorage(value: String?): HapticLevel =
            values().firstOrNull { it.storageId == value || it.name == value } ?: OFF
    }
}

class HapticRateLimiter(private val minIntervalMs: Long = MIN_INTERVAL_MS) {
    private var lastPulseAt = Long.MIN_VALUE
    private var suppressed = 0
    private var pulses = 0

    @Synchronized
    fun allow(nowMs: Long): Boolean {
        if (lastPulseAt != Long.MIN_VALUE && nowMs - lastPulseAt < minIntervalMs) {
            suppressed++
            return false
        }
        lastPulseAt = nowMs
        pulses++
        return true
    }

    @Synchronized
    fun reset() { lastPulseAt = Long.MIN_VALUE }

    @Synchronized
    fun drainCounters(): IntArray {
        val values = intArrayOf(pulses, suppressed)
        pulses = 0
        suppressed = 0
        return values
    }

    companion object {
        const val MIN_INTERVAL_MS = 55L
    }
}

object HapticPolicy {
    const val USB_CONNECTION_DURATION_MS = 500L

    fun shouldPulse(level: HapticLevel, available: Boolean, accepted: Boolean): Boolean =
        available && level.enabled && accepted

    fun usbConnectionDuration(level: HapticLevel, available: Boolean): Long =
        if (level.enabled && available) USB_CONNECTION_DURATION_MS else 0L
}
