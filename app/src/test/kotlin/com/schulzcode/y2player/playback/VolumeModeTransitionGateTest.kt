package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeModeTransitionGateTest {
    @Test fun appliedGainCanCompleteTheCurrentEngineTransition() {
        val engine = Any()
        val gate = VolumeModeTransitionGate()
        val ticket = gate.begin(engine)

        assertEquals(
            VolumeModeTransitionDecision.RAISE_SYSTEM_VOLUME,
            gate.complete(ticket, engine, OutputGainApplyResult.APPLIED, stillInAppMode = true)
        )
        assertEquals(
            VolumeModeTransitionDecision.IGNORE_STALE,
            gate.complete(ticket, engine, OutputGainApplyResult.APPLIED, stillInAppMode = true)
        )
    }

    @Test fun olderAcknowledgementCannotCompleteANewerTransition() {
        val engine = Any()
        val gate = VolumeModeTransitionGate()
        val old = gate.begin(engine)
        val current = gate.begin(engine)

        assertEquals(
            VolumeModeTransitionDecision.IGNORE_STALE,
            gate.complete(old, engine, OutputGainApplyResult.APPLIED, stillInAppMode = true)
        )
        assertEquals(
            VolumeModeTransitionDecision.RAISE_SYSTEM_VOLUME,
            gate.complete(current, engine, OutputGainApplyResult.APPLIED, stillInAppMode = true)
        )
    }

    @Test fun leavingInAppModeInvalidatesPendingAttenuation() {
        val engine = Any()
        val gate = VolumeModeTransitionGate()
        val ticket = gate.begin(engine)

        gate.cancel()

        assertEquals(
            VolumeModeTransitionDecision.IGNORE_STALE,
            gate.complete(ticket, engine, OutputGainApplyResult.APPLIED, stillInAppMode = false)
        )
    }

    @Test fun engineReplacementCannotAcknowledgeTheOldPlaybackPath() {
        val oldEngine = Any()
        val replacement = Any()
        val gate = VolumeModeTransitionGate()
        val ticket = gate.begin(oldEngine)

        assertEquals(
            VolumeModeTransitionDecision.IGNORE_STALE,
            gate.complete(ticket, replacement, OutputGainApplyResult.APPLIED, stillInAppMode = true)
        )
    }

    @Test fun cancelledGainRetriesWithoutRaisingTheSystemLayer() {
        val engine = Any()
        val gate = VolumeModeTransitionGate()
        val ticket = gate.begin(engine)

        assertEquals(
            VolumeModeTransitionDecision.RETRY_OUTPUT_GAIN,
            gate.complete(ticket, engine, OutputGainApplyResult.CANCELLED, stillInAppMode = true)
        )
    }

    @Test fun engineFailureAndReleaseHoldThePreviousSafeSystemVolume() {
        listOf(OutputGainApplyResult.FAILED, OutputGainApplyResult.RELEASED).forEach { result ->
            val engine = Any()
            val gate = VolumeModeTransitionGate()
            val ticket = gate.begin(engine)

            assertEquals(
                VolumeModeTransitionDecision.HOLD_SAFE,
                gate.complete(ticket, engine, result, stillInAppMode = true)
            )
        }
    }

    @Test fun switchingBackToSystemBeforeAcknowledgementCannotRaiseItAgain() {
        val engine = Any()
        val gate = VolumeModeTransitionGate()
        val ticket = gate.begin(engine)

        assertEquals(
            VolumeModeTransitionDecision.HOLD_SAFE,
            gate.complete(ticket, engine, OutputGainApplyResult.APPLIED, stillInAppMode = false)
        )
    }

    @Test fun playingPausedAndEmptyPathsAllWaitForAppliedAcknowledgement() {
        listOf(EngineState.PLAYING, EngineState.PAUSED, EngineState.EMPTY).forEach { state ->
            val events = mutableListOf("requested:$state", "gain_queued")
            val engine = Any()
            val gate = VolumeModeTransitionGate()
            val ticket = gate.begin(engine)

            assertFalse("$state raised before acknowledgement", events.contains("system_raised"))
            val decision = gate.complete(
                ticket,
                engine,
                OutputGainApplyResult.APPLIED,
                stillInAppMode = true
            )
            if (decision == VolumeModeTransitionDecision.RAISE_SYSTEM_VOLUME) {
                events += "gain_applied"
                events += "system_raised"
            }

            assertEquals(
                listOf("requested:$state", "gain_queued", "gain_applied", "system_raised"),
                events
            )
        }
    }

    @Test fun unavailableEngineReportsFailureInsteadOfFalseAcknowledgement() {
        var result: OutputGainApplyResult? = null

        UnavailablePlaybackEngine("test").setOutputGain(0.25f) { result = it }

        assertEquals(OutputGainApplyResult.FAILED, result)
    }

    @Test fun rapidRepeatedRequestsAuthorizeOnlyTheLastAppliedGain() {
        val engine = Any()
        val gate = VolumeModeTransitionGate()
        val tickets = (1..100).map { gate.begin(engine) }

        tickets.dropLast(1).forEach { ticket ->
            assertEquals(
                VolumeModeTransitionDecision.IGNORE_STALE,
                gate.complete(ticket, engine, OutputGainApplyResult.APPLIED, stillInAppMode = true)
            )
        }
        assertEquals(
            VolumeModeTransitionDecision.RAISE_SYSTEM_VOLUME,
            gate.complete(tickets.last(), engine, OutputGainApplyResult.APPLIED, stillInAppMode = true)
        )
    }

    @Test fun playingOutputRequiresAPlaybackHeadFenceAfterApplyingGain() {
        val boundary = OutputGainActivationPolicy.confirmationFrame(
            engineState = EngineState.PLAYING,
            outputIsPlaying = true,
            playedFrames = 12_345L
        )

        assertEquals(12_346L, boundary)
        assertFalse(
            OutputGainActivationPolicy.isActive(
                boundary,
                EngineState.PLAYING,
                outputIsPlaying = true,
                playedFrames = 12_345L
            )
        )
        assertTrue(
            OutputGainActivationPolicy.isActive(
                boundary,
                EngineState.PLAYING,
                outputIsPlaying = true,
                playedFrames = 12_346L
            )
        )
    }

    @Test fun pausedPreparedAndEmptyOutputsRetainGainWithoutAnAudibleFence() {
        listOf(EngineState.PAUSED, EngineState.READY, EngineState.EMPTY).forEach { state ->
            val boundary = OutputGainActivationPolicy.confirmationFrame(
                engineState = state,
                outputIsPlaying = false,
                playedFrames = 99L
            )

            assertEquals(OutputGainActivationPolicy.IMMEDIATE, boundary)
            assertTrue(
                OutputGainActivationPolicy.isActive(
                    boundary,
                    state,
                    outputIsPlaying = false,
                    playedFrames = 99L
                )
            )
        }
    }

    @Test fun aPlayingStateWithQuiescentOutputCannotEmitBeforeAcknowledgement() {
        val boundary = OutputGainActivationPolicy.confirmationFrame(
            engineState = EngineState.PLAYING,
            outputIsPlaying = false,
            playedFrames = 7L
        )

        assertEquals(OutputGainActivationPolicy.IMMEDIATE, boundary)
    }
}
