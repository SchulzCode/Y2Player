package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearEndPreloadPolicyTest {

    /** A fully eligible set of inputs; individual tests flip one field. */
    private fun eligible(
        isPlaying: Boolean = true,
        hasCurrentTrack: Boolean = true,
        hasNextItem: Boolean = true,
        repeatOne: Boolean = false,
        stopAfterCurrent: Boolean = false,
        alreadyPreparedOrPreparing: Boolean = false,
        transitioning: Boolean = false,
        attemptedForThisRequest: Boolean = false,
        remainingMs: Long = 20_000L,
        crossfadeMs: Long = 0L
    ) = NearEndPreloadPolicy.Inputs(
        isPlaying, hasCurrentTrack, hasNextItem, repeatOne, stopAfterCurrent,
        alreadyPreparedOrPreparing, transitioning, attemptedForThisRequest, remainingMs, crossfadeMs
    )

    @Test fun pausedNeverPreloads() {
        assertFalse(NearEndPreloadPolicy.shouldPreload(eligible(isPlaying = false)))
    }

    @Test fun playingOutsideThresholdDoesNotPreload() {
        // 4-minute track with 3 minutes left: far outside the 30 s window.
        assertFalse(NearEndPreloadPolicy.shouldPreload(eligible(remainingMs = 180_000L)))
    }

    @Test fun playingInsideThresholdPreloads() {
        assertTrue(NearEndPreloadPolicy.shouldPreload(eligible(remainingMs = 20_000L)))
    }

    @Test fun shortTrackInsideThresholdPreloads() {
        // A 12 s track is inside the window for its whole length.
        assertTrue(NearEndPreloadPolicy.shouldPreload(eligible(remainingMs = 12_000L)))
    }

    @Test fun repeatOneSuppressesPreload() {
        assertFalse(NearEndPreloadPolicy.shouldPreload(eligible(repeatOne = true)))
    }

    @Test fun endOfCurrentTrackSleepModeSuppressesPreload() {
        assertFalse(NearEndPreloadPolicy.shouldPreload(eligible(stopAfterCurrent = true)))
    }

    @Test fun existingPreparedNextSuppressesDuplicate() {
        assertFalse(NearEndPreloadPolicy.shouldPreload(eligible(alreadyPreparedOrPreparing = true)))
    }

    @Test fun transitionInProgressSuppressesPreload() {
        assertFalse(NearEndPreloadPolicy.shouldPreload(eligible(transitioning = true)))
    }

    @Test fun noNextItemDoesNotPreload() {
        assertFalse(NearEndPreloadPolicy.shouldPreload(eligible(hasNextItem = false)))
    }

    @Test fun zeroOrNegativeRemainingDoesNotPreload() {
        assertFalse(NearEndPreloadPolicy.shouldPreload(eligible(remainingMs = 0L)))
        assertFalse(NearEndPreloadPolicy.shouldPreload(eligible(remainingMs = -500L)))
    }

    @Test fun alreadyAttemptedIsNotRetried() {
        assertFalse(NearEndPreloadPolicy.shouldPreload(eligible(attemptedForThisRequest = true)))
    }

    @Test fun crossfadeWidensTheThreshold() {
        // With a 6 s crossfade the effective threshold is 30 s (window still dominates),
        // but a longer crossfade pushes it out past the base window.
        assertEquals(30_000L, NearEndPreloadPolicy.effectiveThresholdMs(6_000L))
        assertEquals(40_000L, NearEndPreloadPolicy.effectiveThresholdMs(35_000L))
        // 38 s remaining would not preload at the base window, but a 35 s crossfade
        // widens the threshold to 40 s so it now qualifies.
        assertFalse(NearEndPreloadPolicy.shouldPreload(eligible(remainingMs = 38_000L, crossfadeMs = 0L)))
        assertTrue(NearEndPreloadPolicy.shouldPreload(eligible(remainingMs = 38_000L, crossfadeMs = 35_000L)))
    }

    @Test fun cheapWindowCheckRejectsOrdinaryTicksBeforeResolution() {
        assertFalse(NearEndPreloadPolicy.isWithinWindow(180_000L, 0L))
        assertTrue(NearEndPreloadPolicy.isWithinWindow(12_000L, 0L))
        assertFalse(NearEndPreloadPolicy.isWithinWindow(0L, 0L))
    }
}
