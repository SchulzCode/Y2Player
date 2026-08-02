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
        assertTrue(EngineCommand.OutputGain(0.2f).isSupersededBy(EngineCommand.OutputGain(0.9f)))
        assertFalse(EngineCommand.OutputGain(0.2f).isSupersededBy(EngineCommand.Volume(0.9f)))
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

    @Test fun anExplicitSkipIsOnlyReplacedByAnotherSkip() {
        assertTrue(EngineCommand.SkipToPrepared.isSupersededBy(EngineCommand.SkipToPrepared))
        assertFalse(EngineCommand.SkipToPrepared.isSupersededBy(EngineCommand.Start))
        assertFalse(EngineCommand.SkipToPrepared.isSupersededBy(EngineCommand.Volume(1f)))
        assertFalse(EngineCommand.SkipToPrepared.isSupersededBy(EngineCommand.ClearNext))
    }

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

    @Test fun replayGainConfigurationCoalescesToTheNewestShuffleState() {
        assertTrue(
            EngineCommand.ConfigureReplayGain(ReplayGainMode.TRACK_WHEN_SHUFFLING, false)
                .isSupersededBy(
                    EngineCommand.ConfigureReplayGain(ReplayGainMode.TRACK_WHEN_SHUFFLING, true)
                )
        )
        assertFalse(
            EngineCommand.ConfigureReplayGain(ReplayGainMode.ALBUM, false)
                .isSupersededBy(EngineCommand.Start)
        )
    }
}
