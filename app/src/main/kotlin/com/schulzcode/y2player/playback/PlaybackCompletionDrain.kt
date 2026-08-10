package com.schulzcode.y2player.playback

internal data class PlaybackCompletionDrainPlan(
    val contentBoundaryFrame: Long,
    val paddingBlockCount: Int
) {
    val paddingFrameCount: Long = paddingBlockCount * PcmFormat.BLOCK_FRAMES.toLong()
}

/**
 * Keeps terminal AudioTrack underruns from wedging non-gapless completion.
 *
 * Some API 19 AudioTrack implementations stop their playback head just short
 * of the final submitted frame after the decoder reaches EOF. Zero PCM after
 * the content keeps the output moving; completion still waits only for the
 * captured content boundary, so no audible PCM is discarded.
 */
internal object PlaybackCompletionDrain {
    fun plan(
        submittedFrames: Long,
        outputBufferFrames: Int
    ): PlaybackCompletionDrainPlan =
        PlaybackCompletionDrainPlan(
            contentBoundaryFrame = submittedFrames.coerceAtLeast(0L),
            paddingBlockCount = outputBufferFrames.coerceAtLeast(PcmFormat.BLOCK_FRAMES)
                .let { (it + PcmFormat.BLOCK_FRAMES - 1) / PcmFormat.BLOCK_FRAMES }
        )

    fun contentWasPlayed(playedFrames: Long, contentBoundaryFrame: Long): Boolean =
        playedFrames >= contentBoundaryFrame.coerceAtLeast(0L)
}

internal enum class DecoderEofAction { PROMOTE_GAPLESS, DRAIN_CURRENT_OUTPUT }
internal enum class DecoderCompletionCause(val diagnosticId: String) {
    NATURAL("natural"),
    SEEK("seek")
}

internal object DecoderEofPolicy {
    fun action(
        hasPreparedNext: Boolean,
        gaplessEnabled: Boolean,
        crossfadeMs: Long
    ): DecoderEofAction = if (
        hasPreparedNext && gaplessEnabled && crossfadeMs <= 0L
    ) {
        DecoderEofAction.PROMOTE_GAPLESS
    } else {
        DecoderEofAction.DRAIN_CURRENT_OUTPUT
    }
}
