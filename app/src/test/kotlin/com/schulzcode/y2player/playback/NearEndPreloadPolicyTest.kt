package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearEndPreloadPolicyTest {
    @Test fun theWindowIsThirtySecondsWithoutCrossfade() {
        assertEquals(30_000L, NearEndPreloadPolicy.effectiveThresholdMs(0L))
    }

    @Test fun aShortCrossfadeDoesNotShrinkTheWindow() {
        assertEquals(30_000L, NearEndPreloadPolicy.effectiveThresholdMs(6_000L))
    }

    @Test fun aLongCrossfadeWidensTheWindow() {
        assertEquals(40_000L, NearEndPreloadPolicy.effectiveThresholdMs(35_000L))
        assertTrue(NearEndPreloadPolicy.isWithinWindow(38_000L, crossfadeMs = 35_000L))
        assertFalse(NearEndPreloadPolicy.isWithinWindow(38_000L, crossfadeMs = 0L))
    }

    @Test fun aTrackIsOutsideTheWindowUntilItApproachesTheEnd() {
        assertFalse(NearEndPreloadPolicy.isWithinWindow(180_000L, 0L))
        assertTrue(NearEndPreloadPolicy.isWithinWindow(12_000L, 0L))
    }

    @Test fun aFinishedTrackIsNotInsideTheWindow() {
        assertFalse(NearEndPreloadPolicy.isWithinWindow(0L, 0L))
        assertFalse(NearEndPreloadPolicy.isWithinWindow(-500L, 0L))
    }

    @Test fun aVeryShortTrackIsInsideTheWindowImmediately() {
        assertTrue(NearEndPreloadPolicy.isWithinWindow(1_500L, 0L))
    }

    @Test fun aNegativeCrossfadeCannotShrinkTheWindow() {
        assertEquals(30_000L, NearEndPreloadPolicy.effectiveThresholdMs(-1_000L))
    }
}
