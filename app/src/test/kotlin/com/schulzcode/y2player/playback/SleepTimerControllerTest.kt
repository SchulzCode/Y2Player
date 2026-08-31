package com.schulzcode.y2player.playback

import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.SleepTimerMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerControllerTest {
    @Test fun customMinuteTimerExpiresBeforeThePlaybackStopPathRuns() {
        val timer = SleepTimerController()
        val scheduled = requireNotNull(timer.set(SleepTimerMode.MINUTES, 5, 1_000L))

        assertEquals(SleepTimerTick.Expired, timer.onTimer(scheduled.generation, 301_000L))
        assertEquals(SleepTimerMode.OFF, timer.mode)
        assertNull(timer.deadlineElapsedMs)

        val expiry = playbackServiceSource().substringAfter("SleepTimerTick.Expired ->")
            .substringBefore("is SleepTimerTick.Waiting")
        assertTrue(expiry.indexOf("syncSleepTimerSnapshot()") < expiry.indexOf("pauseInternal(PauseReason.SLEEP_TIMER)"))
    }

    @Test fun snapshotPublishesTheConfiguredMinutesAndDeadline() {
        val timer = SleepTimerController()
        timer.set(SleepTimerMode.MINUTES, 7, 10_000L)
        val snapshot = timer.applyTo(PlaybackSnapshot(), 10_001L)

        assertEquals(SleepTimerMode.MINUTES, snapshot.sleepTimerMode)
        assertEquals(7, snapshot.sleepTimerConfiguredMinutes)
        assertEquals(419_999L, snapshot.sleepTimerRemainingMs)
    }

    @Test fun minuteInputIsClampedToTheSupportedWheelRange() {
        val timer = SleepTimerController()
        timer.set(SleepTimerMode.MINUTES, 0, 0L)
        assertEquals(1, timer.configuredMinutes)
        assertEquals(60_000L, timer.deadlineElapsedMs)

        timer.set(SleepTimerMode.MINUTES, 99, 0L)
        assertEquals(60, timer.configuredMinutes)
        assertEquals(3_600_000L, timer.deadlineElapsedMs)
    }

    @Test fun boundaryModesDoNotScheduleCallbacksAndClearNormally() {
        listOf(SleepTimerMode.END_TRACK, SleepTimerMode.END_ALBUM, SleepTimerMode.END_QUEUE).forEach { mode ->
            val timer = SleepTimerController()
            assertNull(timer.set(mode, null, 0L))
            assertEquals(mode, timer.mode)
            assertNull(timer.deadlineElapsedMs)
            timer.clear()
            assertEquals(SleepTimerMode.OFF, timer.mode)
        }
    }

    @Test fun replacingOrClearingATimerInvalidatesItsOldCallback() {
        val timer = SleepTimerController()
        val old = requireNotNull(timer.set(SleepTimerMode.MINUTES, 5, 0L))
        val replacement = requireNotNull(timer.set(SleepTimerMode.MINUTES, 10, 500L))

        assertEquals(SleepTimerTick.Stale, timer.onTimer(old.generation, 300_000L))
        assertEquals(SleepTimerTick.Expired, timer.onTimer(replacement.generation, 600_500L))

        val cancelled = requireNotNull(timer.set(SleepTimerMode.MINUTES, 2, 0L))
        timer.set(SleepTimerMode.OFF, null, 1L)
        assertEquals(SleepTimerTick.Stale, timer.onTimer(cancelled.generation, 120_000L))
    }

    @Test fun earlyCallbackIsRescheduled() {
        val timer = SleepTimerController()
        val scheduled = requireNotNull(timer.set(SleepTimerMode.MINUTES, 1, 1_000L))
        val deadline = requireNotNull(timer.deadlineElapsedMs)

        assertEquals(SleepTimerTick.Waiting(1L), timer.onTimer(scheduled.generation, deadline - 1L))
        assertEquals(SleepTimerTick.Expired, timer.onTimer(scheduled.generation, deadline))
    }

    private fun playbackServiceSource(): String = File(
        repositoryRoot(),
        "app/src/main/kotlin/com/schulzcode/y2player/playback/PlaybackService.kt"
    ).readText()

    private fun repositoryRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "app/src/main/AndroidManifest.xml").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("repository root not found")
    }
}
