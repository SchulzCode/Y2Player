package com.schulzcode.y2player.playback

import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.SleepTimerMode

internal data class ScheduledSleepTimer(
    val generation: Long,
    val delayMs: Long
)

internal sealed class SleepTimerTick {
    data class Waiting(val delayMs: Long) : SleepTimerTick()
    object Expired : SleepTimerTick()
    object Stale : SleepTimerTick()
}

internal class SleepTimerController {
    private val generation = GenerationGuard()

    var mode: SleepTimerMode = SleepTimerMode.OFF
        private set
    var configuredMinutes: Int? = null
        private set
    var deadlineElapsedMs: Long? = null
        private set

    fun set(mode: SleepTimerMode, minutes: Int?, nowElapsedMs: Long): ScheduledSleepTimer? {
        val safeMinutes = if (mode == SleepTimerMode.MINUTES) {
            (minutes ?: DEFAULT_MINUTES).coerceIn(MIN_MINUTES, MAX_MINUTES)
        } else null
        this.mode = mode
        configuredMinutes = safeMinutes.takeIf { mode == SleepTimerMode.MINUTES }
        deadlineElapsedMs = configuredMinutes?.let { nowElapsedMs + it * 60_000L }
        val value = generation.advance()
        return deadlineElapsedMs?.let { deadline ->
            ScheduledSleepTimer(value, (deadline - nowElapsedMs).coerceAtLeast(1L))
        }
    }

    fun onTimer(generationValue: Long, nowElapsedMs: Long): SleepTimerTick {
        if (!generation.isCurrent(generationValue)) return SleepTimerTick.Stale
        val deadline = deadlineElapsedMs ?: return SleepTimerTick.Stale
        val remaining = deadline - nowElapsedMs
        if (remaining > 0L) return SleepTimerTick.Waiting(remaining)
        clear()
        return SleepTimerTick.Expired
    }

    fun clear() {
        generation.advance()
        mode = SleepTimerMode.OFF
        configuredMinutes = null
        deadlineElapsedMs = null
    }

    fun applyTo(snapshot: PlaybackSnapshot, nowElapsedMs: Long): PlaybackSnapshot = snapshot.copy(
        sleepTimerMode = mode,
        sleepTimerConfiguredMinutes = configuredMinutes,
        sleepTimerDeadlineElapsedMs = deadlineElapsedMs,
        sleepTimerRemainingMs = deadlineElapsedMs?.let { (it - nowElapsedMs).coerceAtLeast(0L) }
    )

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 60
        const val DEFAULT_MINUTES = 5
    }
}
