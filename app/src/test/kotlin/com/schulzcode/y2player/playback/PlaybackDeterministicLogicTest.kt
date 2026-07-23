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

    @Test fun replacementIsCreatedBeforeCurrentSessionOwnerIsReleased() {
        val events = mutableListOf<String>()
        var currentIsAlive = true

        val replacement = replaceSessionOwner(
            current = "current",
            create = {
                assertTrue(currentIsAlive)
                events += "create"
                "replacement"
            },
            release = {
                assertEquals("current", it)
                currentIsAlive = false
                events += "release"
            }
        )

        assertEquals("replacement", replacement)
        assertEquals(listOf("create", "release"), events)
    }

    @Test fun failedReplacementDoesNotReleaseCurrentSessionOwner() {
        var released = false

        val failure = runCatching {
            replaceSessionOwner(
                current = "current",
                create = { throw IllegalStateException("creation failed") },
                release = { released = true }
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(released)
    }

}
