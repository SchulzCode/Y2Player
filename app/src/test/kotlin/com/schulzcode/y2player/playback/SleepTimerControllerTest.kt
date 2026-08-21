package com.schulzcode.y2player.playback

import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.SleepTimerMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerControllerTest {
    @Test fun activeTimerExpiresBeforeThePlaybackStopPathRuns() {
        val timer = SleepTimerController()
        val scheduled = requireNotNull(timer.cycle(1_000L))

        assertEquals(
            SleepTimerTick.Expired,
            timer.onTimer(scheduled.generation, 1_000L + requireNotNull(timer.mode.durationMs))
        )
        assertEquals(SleepTimerMode.OFF, timer.mode)
        assertNull(timer.deadlineElapsedMs)

        val source = playbackServiceSource()
        val expiry = source.substringAfter("SleepTimerTick.Expired ->")
            .substringBefore("is SleepTimerTick.Waiting")
        assertTrue(expiry.indexOf("syncSleepTimerSnapshot()") < expiry.indexOf("pauseInternal(PauseReason.SLEEP_TIMER)"))
    }

    @Test fun expiredTimerIsInactiveInThePublishedSnapshot() {
        val timer = SleepTimerController()
        val scheduled = requireNotNull(timer.cycle(0L))
        val deadline = requireNotNull(timer.deadlineElapsedMs)
        val active = timer.applyTo(PlaybackSnapshot(), 1L)
        assertEquals(SleepTimerMode.MINUTES_15, active.sleepTimerMode)

        timer.onTimer(scheduled.generation, deadline)
        val expired = timer.applyTo(active, deadline)

        assertEquals(SleepTimerMode.OFF, expired.sleepTimerMode)
        assertNull(expired.sleepTimerRemainingMs)
    }

    @Test fun manuallyCancelledTimerStaysOffAndCannotBeRestoredByItsCallback() {
        val timer = SleepTimerController()
        val cancelled = requireNotNull(timer.cycle(10L))

        repeat(SleepTimerMode.values().size - 1) { timer.cycle(20L + it) }

        assertEquals(SleepTimerMode.OFF, timer.mode)
        assertNull(timer.deadlineElapsedMs)
        assertEquals(
            SleepTimerTick.Stale,
            timer.onTimer(cancelled.generation, 10L + requireNotNull(SleepTimerMode.MINUTES_15.durationMs))
        )
        assertEquals(SleepTimerMode.OFF, timer.mode)
    }

    @Test fun trackAlbumAndQueueModesRemainBoundaryBasedAndClearAfterStopping() {
        listOf(SleepTimerMode.END_TRACK, SleepTimerMode.END_ALBUM, SleepTimerMode.END_QUEUE).forEach { target ->
            val timer = SleepTimerController()
            var scheduled: ScheduledSleepTimer? = null
            repeat(target.ordinal) { scheduled = timer.cycle(it.toLong()) }

            assertEquals(target, timer.mode)
            assertNull(scheduled)
            assertNull(timer.deadlineElapsedMs)

            timer.clear()
            assertEquals(SleepTimerMode.OFF, timer.mode)
        }
    }

    @Test fun aNewTimerWorksAfterThePreviousTimerExpired() {
        val timer = SleepTimerController()
        val first = requireNotNull(timer.cycle(0L))
        timer.onTimer(first.generation, requireNotNull(timer.mode.durationMs))

        val secondStartedAt = 2_000_000L
        val second = requireNotNull(timer.cycle(secondStartedAt))
        assertEquals(SleepTimerMode.MINUTES_15, timer.mode)
        assertEquals(
            SleepTimerTick.Expired,
            timer.onTimer(second.generation, secondStartedAt + requireNotNull(timer.mode.durationMs))
        )
        assertEquals(SleepTimerMode.OFF, timer.mode)
    }

    @Test fun replacingATimerInvalidatesTheOldCallbackAndKeepsTheNewDeadline() {
        val timer = SleepTimerController()
        val old = requireNotNull(timer.cycle(0L))
        val replacementStartedAt = 500L
        val replacement = requireNotNull(timer.cycle(replacementStartedAt))

        assertEquals(SleepTimerMode.MINUTES_30, timer.mode)
        assertEquals(SleepTimerTick.Stale, timer.onTimer(old.generation, requireNotNull(SleepTimerMode.MINUTES_15.durationMs)))
        assertEquals(
            SleepTimerTick.Expired,
            timer.onTimer(replacement.generation, replacementStartedAt + requireNotNull(SleepTimerMode.MINUTES_30.durationMs))
        )
    }

    @Test fun anEarlyCallbackIsRescheduledInsteadOfLeavingAnElapsedTimerActive() {
        val timer = SleepTimerController()
        val scheduled = requireNotNull(timer.cycle(1_000L))
        val deadline = requireNotNull(timer.deadlineElapsedMs)

        assertEquals(
            SleepTimerTick.Waiting(1L),
            timer.onTimer(scheduled.generation, deadline - 1L)
        )
        assertEquals(SleepTimerMode.MINUTES_15, timer.mode)
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
