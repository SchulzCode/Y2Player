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
    var deadlineElapsedMs: Long? = null
        private set

    fun cycle(nowElapsedMs: Long): ScheduledSleepTimer? {
        mode = mode.next()
        deadlineElapsedMs = mode.durationMs?.let { nowElapsedMs + it }
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
        deadlineElapsedMs = null
    }

    fun applyTo(snapshot: PlaybackSnapshot, nowElapsedMs: Long): PlaybackSnapshot = snapshot.copy(
        sleepTimerMode = mode,
        sleepTimerDeadlineElapsedMs = deadlineElapsedMs,
        sleepTimerRemainingMs = deadlineElapsedMs?.let { (it - nowElapsedMs).coerceAtLeast(0L) }
    )
}
