package com.schulzcode.y2player.playback

/**
 * Supplies volume repeats only after Android has classified a key as held.
 *
 * Some API 19 builds deliver repeatCount=1 while the display is asleep and
 * then stay silent until key-up. Framework repeats take priority whenever they
 * continue; the fallback starts only after a quiet grace period. All times are
 * from the monotonic uptime clock used by KeyEvent and Handler.
 */
internal class VolumeKeyRepeatController(
    private val frameworkSilenceMs: Long = FRAMEWORK_SILENCE_MS,
    private val repeatIntervalMs: Long = REPEAT_INTERVAL_MS,
    private val maxHoldMs: Long = MAX_HOLD_MS
) {
    data class Result(
        val adjustment: Int? = null,
        val nextRunAtMs: Long? = null,
        val stoppedByLimit: Boolean = false
    )

    private data class Press(
        val direction: Int,
        val keyCode: Int,
        val downTime: Long,
        val deviceId: Int,
        val startedAtMs: Long,
        val lastEventTime: Long,
        val lastRepeatCount: Int,
        val nextRunAtMs: Long?
    )

    private var press: Press? = null

    val isRepeating: Boolean get() = press?.nextRunAtMs != null

    fun onDown(
        direction: Int,
        keyCode: Int,
        downTime: Long,
        deviceId: Int,
        repeatCount: Int,
        eventTime: Long,
        nowMs: Long
    ): Result {
        val normalizedDirection = if (direction > 0) 1 else -1
        val current = press
        val matching = current?.takeIf {
            samePress(it, normalizedDirection, keyCode, downTime, deviceId)
        }
        if (matching != null &&
            matching.lastEventTime == eventTime && matching.lastRepeatCount == repeatCount
        ) return Result(nextRunAtMs = matching.nextRunAtMs)

        if (repeatCount <= 0) {
            press = Press(
                direction = normalizedDirection,
                keyCode = keyCode,
                downTime = downTime,
                deviceId = deviceId,
                startedAtMs = nowMs,
                lastEventTime = eventTime,
                lastRepeatCount = 0,
                nextRunAtMs = null
            )
            return Result(adjustment = normalizedDirection)
        }

        val startedAt = matching?.startedAtMs ?: nowMs
        val deadline = saturatedAdd(startedAt, maxHoldMs)
        if (nowMs >= deadline) {
            press = null
            return Result(stoppedByLimit = true)
        }
        val nextRunAt = minOf(saturatedAdd(nowMs, frameworkSilenceMs), deadline)
        press = Press(
            direction = normalizedDirection,
            keyCode = keyCode,
            downTime = downTime,
            deviceId = deviceId,
            startedAtMs = startedAt,
            lastEventTime = eventTime,
            lastRepeatCount = repeatCount,
            nextRunAtMs = nextRunAt
        )
        return Result(adjustment = normalizedDirection, nextRunAtMs = nextRunAt)
    }

    fun onRelease(keyCode: Int, downTime: Long, deviceId: Int): Result {
        val current = press ?: return Result()
        if (!samePhysicalKey(current, keyCode, downTime, deviceId)) return Result(nextRunAtMs = current.nextRunAtMs)
        press = null
        return Result()
    }

    fun onTimer(nowMs: Long): Result {
        val current = press ?: return Result()
        val scheduled = current.nextRunAtMs ?: return Result()
        if (nowMs < scheduled) return Result(nextRunAtMs = scheduled)
        val deadline = saturatedAdd(current.startedAtMs, maxHoldMs)
        if (nowMs >= deadline) {
            press = null
            return Result(stoppedByLimit = true)
        }
        val nextRunAt = minOf(saturatedAdd(nowMs, repeatIntervalMs), deadline)
        press = current.copy(nextRunAtMs = nextRunAt)
        return Result(adjustment = current.direction, nextRunAtMs = nextRunAt)
    }

    fun oneShot(direction: Int): Result {
        press = null
        return Result(adjustment = if (direction > 0) 1 else -1)
    }

    fun cancel() {
        press = null
    }

    private fun samePress(
        current: Press,
        direction: Int,
        keyCode: Int,
        downTime: Long,
        deviceId: Int
    ): Boolean = current.direction == direction && samePhysicalKey(current, keyCode, downTime, deviceId)

    private fun samePhysicalKey(current: Press, keyCode: Int, downTime: Long, deviceId: Int): Boolean {
        if (current.keyCode != keyCode) return false
        val sameDevice = current.deviceId == 0 || deviceId == 0 || current.deviceId == deviceId
        if (!sameDevice) return false
        return current.downTime <= 0L || downTime <= 0L || current.downTime == downTime
    }

    private fun saturatedAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

    companion object {
        const val FRAMEWORK_SILENCE_MS = 180L
        const val REPEAT_INTERVAL_MS = 100L
        const val MAX_HOLD_MS = 10_000L
    }
}
