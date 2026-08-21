package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.SleepTimerMode
import com.schulzcode.y2player.playback.SleepTimerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerPresentationTest {
    @Test fun fifteenMinuteTimerInitiallyDisplaysFifteenMinutes() {
        val timer = SleepTimerController()
        timer.cycle(10_000L)
        val snapshot = timer.applyTo(PlaybackSnapshot(), 10_001L)
        val remaining = SleepTimerPresentation.remainingMs(snapshot, 10_001L)

        assertEquals(899_999L, remaining)
        assertEquals("15:00", SleepTimerPresentation.label(snapshot.sleepTimerMode, remaining))

        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.NowPlayingOptions)),
            playback = snapshot.copy(sleepTimerRemainingMs = remaining)
        )
        val timerRow = ScreenContent.rows(state)
            .filterIsInstance<ScreenRow.Action>()
            .first { it.key == "sleep_timer" }
        assertEquals("15:00", timerRow.subtitle)
    }

    @Test fun remainingTimeIsAlwaysDerivedFromTheDeadline() {
        val snapshot = PlaybackSnapshot(
            sleepTimerMode = SleepTimerMode.MINUTES_15,
            sleepTimerDeadlineElapsedMs = 901_000L,
            sleepTimerRemainingMs = 123L
        )

        assertEquals(900_000L, SleepTimerPresentation.remainingMs(snapshot, 1_000L))
        assertEquals(899_000L, SleepTimerPresentation.remainingMs(snapshot, 2_000L))
        assertEquals(0L, SleepTimerPresentation.remainingMs(snapshot, 999_999L))
    }

    @Test fun countdownUsesCleanMinutesAndSecondsWithoutEllipsis() {
        assertEquals("15:00", SleepTimerPresentation.countdown(899_999L))
        assertEquals("14:59", SleepTimerPresentation.countdown(899_000L))
        assertEquals("0:01", SleepTimerPresentation.countdown(1L))
        assertEquals("0:00", SleepTimerPresentation.countdown(0L))
        assertFalse(SleepTimerPresentation.countdown(899_999L).contains("..."))
    }

    @Test fun refreshRunsOnlyWhileANumericCountdownIsVisible() {
        assertTrue(SleepTimerPresentation.shouldRefresh(
            Screen.NowPlayingOptions,
            SleepTimerMode.MINUTES_15,
            deadlineElapsedMs = 900_000L,
            uiVisible = true
        ))
        assertFalse(SleepTimerPresentation.shouldRefresh(
            Screen.NowPlaying,
            SleepTimerMode.MINUTES_15,
            deadlineElapsedMs = 900_000L,
            uiVisible = true
        ))
        assertFalse(SleepTimerPresentation.shouldRefresh(
            Screen.NowPlayingOptions,
            SleepTimerMode.END_TRACK,
            deadlineElapsedMs = null,
            uiVisible = true
        ))
        assertFalse(SleepTimerPresentation.shouldRefresh(
            Screen.NowPlayingOptions,
            SleepTimerMode.MINUTES_15,
            deadlineElapsedMs = 900_000L,
            uiVisible = false
        ))
    }

    @Test fun reopeningAfterExpirationReportsOff() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.NowPlayingOptions)),
            playback = PlaybackSnapshot(sleepTimerMode = SleepTimerMode.OFF)
        )
        val timerRow = ScreenContent.rows(state)
            .filterIsInstance<ScreenRow.Action>()
            .first { it.key == "sleep_timer" }

        assertEquals("Off", timerRow.subtitle)
    }
}
