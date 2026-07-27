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

    @Test fun latestSeekAndTransportCommandSupersedeOlderWork() {
        assertTrue(EngineCommand.Seek(1).isSupersededBy(EngineCommand.Seek(2)))
        assertFalse(EngineCommand.Seek(1).isSupersededBy(EngineCommand.Start))
        assertTrue(EngineCommand.Start.isSupersededBy(EngineCommand.Pause))
        assertTrue(EngineCommand.Pause.isSupersededBy(EngineCommand.Start))
    }

    @Test fun repeatedVolumeAndBalanceStepsCoalesce() {
        assertTrue(EngineCommand.Volume(0.2f).isSupersededBy(EngineCommand.Volume(0.9f)))
        assertTrue(EngineCommand.Balance(-10).isSupersededBy(EngineCommand.Balance(10)))
        assertFalse(EngineCommand.Volume(0.2f).isSupersededBy(EngineCommand.Balance(10)))
        assertFalse(EngineCommand.Balance(10).isSupersededBy(EngineCommand.Volume(0.2f)))
    }

    @Test fun newLoadSkipInvalidatesEveryPendingPlaybackCommand() {
        assertTrue(EngineCommand.Cancel.clearsPending)
        assertTrue(EngineCommand.Release.clearsPending)
        assertFalse(EngineCommand.ClearNext.clearsPending)
        assertFalse(EngineCommand.Seek(1).clearsPending)
        assertFalse(EngineCommand.Start.clearsPending)
    }

    @Test fun queueMutationInvalidatesPreparedNextButNotCurrentTransport() {
        assertTrue(EngineCommand.ClearNext.isSupersededBy(EngineCommand.ClearNext))
        assertFalse(EngineCommand.Start.isSupersededBy(EngineCommand.ClearNext))
        assertFalse(EngineCommand.Seek(1).isSupersededBy(EngineCommand.ClearNext))
    }

    /**
     * An explicit skip is only ever superseded by another skip: it must not be
     * dropped by an unrelated transport command, or a user's press is lost.
     *
     * Timed transitions no longer travel as commands at all — the engine
     * schedules them against its own frame counter — so this covers only the
     * user-initiated path.
     */
    @Test fun anExplicitSkipIsOnlyReplacedByAnotherSkip() {
        assertTrue(EngineCommand.SkipToPrepared.isSupersededBy(EngineCommand.SkipToPrepared))
        assertFalse(EngineCommand.SkipToPrepared.isSupersededBy(EngineCommand.Start))
        assertFalse(EngineCommand.SkipToPrepared.isSupersededBy(EngineCommand.Volume(1f)))
        assertFalse(EngineCommand.SkipToPrepared.isSupersededBy(EngineCommand.ClearNext))
    }

    /**
     * Transition configuration follows the preferences, so a burst of setting
     * changes must collapse to the newest one rather than replaying old policy.
     */
    @Test fun transitionConfigurationCoalescesToTheNewest() {
        assertTrue(
            EngineCommand.ConfigureTransition(gaplessEnabled = true, crossfadeMs = 0)
                .isSupersededBy(
                    EngineCommand.ConfigureTransition(gaplessEnabled = false, crossfadeMs = 5_000)
                )
        )
        assertFalse(
            EngineCommand.ConfigureTransition(gaplessEnabled = true, crossfadeMs = 0)
                .isSupersededBy(EngineCommand.Start)
        )
        assertFalse(EngineCommand.ConfigureTransition(true, 0).clearsPending)
    }

}
