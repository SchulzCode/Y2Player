package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.SleepTimerMode

internal object SleepTimerPresentation {
    fun remainingMs(playback: PlaybackSnapshot, nowElapsedMs: Long): Long? =
        playback.sleepTimerDeadlineElapsedMs
            ?.let { (it - nowElapsedMs).coerceAtLeast(0L) }
            ?: playback.sleepTimerRemainingMs?.coerceAtLeast(0L)

    fun countdown(remainingMs: Long): String {
        val value = remainingMs.coerceAtLeast(0L)
        val seconds = value / 1_000L + if (value % 1_000L == 0L) 0L else 1L
        return "${seconds / 60L}:${(seconds % 60L).toString().padStart(2, '0')}"
    }

    fun label(mode: SleepTimerMode, remainingMs: Long?): String =
        if (mode.durationMs != null && remainingMs != null) countdown(remainingMs) else mode.label

    fun shouldRefresh(
        screen: Screen,
        mode: SleepTimerMode,
        deadlineElapsedMs: Long?,
        uiVisible: Boolean
    ): Boolean = uiVisible && screen == Screen.NowPlayingOptions &&
        mode.durationMs != null && deadlineElapsedMs != null
}
