package com.schulzcode.y2player.playback

/**
 * Invalidates stale asynchronous output-gain acknowledgements without owning
 * either the persisted volume mode or the live gain value.
 */
internal class VolumeModeTransitionGate {
    class Ticket internal constructor(
        val generation: Long,
        internal val engineIdentity: Any
    )

    private var generation = 0L
    private var pendingGeneration: Long? = null

    @Synchronized
    fun begin(engineIdentity: Any): Ticket {
        val ticket = Ticket(++generation, engineIdentity)
        pendingGeneration = ticket.generation
        return ticket
    }

    @Synchronized
    fun cancel() {
        generation += 1
        pendingGeneration = null
    }

    @Synchronized
    fun complete(
        ticket: Ticket,
        currentEngineIdentity: Any,
        result: OutputGainApplyResult,
        stillInAppMode: Boolean
    ): VolumeModeTransitionDecision {
        if (pendingGeneration != ticket.generation ||
            ticket.engineIdentity !== currentEngineIdentity
        ) return VolumeModeTransitionDecision.IGNORE_STALE
        pendingGeneration = null
        if (!stillInAppMode) return VolumeModeTransitionDecision.HOLD_SAFE
        return when (result) {
            OutputGainApplyResult.APPLIED -> VolumeModeTransitionDecision.RAISE_SYSTEM_VOLUME
            OutputGainApplyResult.CANCELLED -> VolumeModeTransitionDecision.RETRY_OUTPUT_GAIN
            OutputGainApplyResult.FAILED,
            OutputGainApplyResult.RELEASED -> VolumeModeTransitionDecision.HOLD_SAFE
        }
    }
}

internal enum class VolumeModeTransitionDecision {
    RAISE_SYSTEM_VOLUME,
    RETRY_OUTPUT_GAIN,
    HOLD_SAFE,
    IGNORE_STALE
}

/**
 * Turns an AudioTrack gain update into an audible-output acknowledgement.
 *
 * AudioTrack.setStereoVolume() returning is not a sufficient fence on the Y2's
 * API 19 audio stack: AudioFlinger can consume the update asynchronously. When
 * audio is live, require the playback head to advance after the call. A paused
 * or otherwise quiescent AudioTrack retains the new gain and cannot emit audio,
 * so it is safe to acknowledge immediately.
 */
internal object OutputGainActivationPolicy {
    const val IMMEDIATE = -1L

    fun confirmationFrame(
        engineState: EngineState,
        outputIsPlaying: Boolean,
        playedFrames: Long
    ): Long = if (engineState == EngineState.PLAYING && outputIsPlaying) {
        playedFrames.coerceAtLeast(0L).let { frame ->
            if (frame == Long.MAX_VALUE) Long.MAX_VALUE else frame + 1L
        }
    } else {
        IMMEDIATE
    }

    fun isActive(
        confirmationFrame: Long,
        engineState: EngineState,
        outputIsPlaying: Boolean,
        playedFrames: Long
    ): Boolean = confirmationFrame == IMMEDIATE ||
        engineState != EngineState.PLAYING ||
        !outputIsPlaying ||
        playedFrames >= confirmationFrame
}
