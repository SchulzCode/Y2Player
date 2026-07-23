package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDeterministicLogicTest {
    @Test fun stalePlaybackRequestIsRejected() {
        assertTrue(PlaybackRequestGate.accepts(42, 42))
        assertFalse(PlaybackRequestGate.accepts(41, 42))
        assertFalse(PlaybackRequestGate.accepts(0, 0))
    }

    @Test fun restoredPositionIsClampedAndEndGuardResets() {
        assertEquals(25_000L, PlaybackPositionPolicy.clampRestored(25_000, 60_000))
        assertEquals(0L, PlaybackPositionPolicy.clampRestored(-1, 60_000))
        assertEquals(0L, PlaybackPositionPolicy.clampRestored(59_000, 60_000))
        assertEquals(0L, PlaybackPositionPolicy.clampRestored(90_000, 60_000))
    }

    @Test fun staleTimerGenerationCannotFire() {
        val guard = GenerationGuard()
        val old = guard.advance()
        val current = guard.advance()

        assertFalse(guard.isCurrent(old))
        assertTrue(guard.isCurrent(current))
    }

}
