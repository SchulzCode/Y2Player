package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCompletionDrainTest {
    @Test
    fun `completion causes have stable diagnostic identifiers`() {
        assertEquals("natural", DecoderCompletionCause.NATURAL.diagnosticId)
        assertEquals("seek", DecoderCompletionCause.SEEK.diagnosticId)
    }

    @Test
    fun `non-gapless EOF captures the audible content boundary and pads the output`() {
        val plan = PlaybackCompletionDrain.plan(
            submittedFrames = 126_441L,
            outputBufferFrames = 8_192
        )

        assertEquals(126_441L, plan.contentBoundaryFrame)
        assertEquals(2, plan.paddingBlockCount)
        assertEquals(8_192L, plan.paddingFrameCount)
    }

    @Test
    fun `terminal underrun cannot wedge after padding carries the head over content`() {
        val plan = PlaybackCompletionDrain.plan(
            submittedFrames = 126_441L,
            outputBufferFrames = 8_192
        )
        val paddedSubmittedFrames = plan.contentBoundaryFrame + plan.paddingFrameCount

        assertFalse(
            PlaybackCompletionDrain.contentWasPlayed(
                playedFrames = plan.contentBoundaryFrame - 1,
                contentBoundaryFrame = plan.contentBoundaryFrame
            )
        )
        assertTrue(
            PlaybackCompletionDrain.contentWasPlayed(
                playedFrames = plan.contentBoundaryFrame,
                contentBoundaryFrame = plan.contentBoundaryFrame
            )
        )
        assertTrue(plan.contentBoundaryFrame < paddedSubmittedFrames)
    }

    @Test
    fun `drain padding covers larger output buffers without partial blocks`() {
        val plan = PlaybackCompletionDrain.plan(
            submittedFrames = 10_000L,
            outputBufferFrames = 10_000
        )

        assertEquals(3, plan.paddingBlockCount)
        assertEquals(12_288L, plan.paddingFrameCount)
    }

    @Test
    fun `gapless still promotes directly without a drain gap`() {
        assertEquals(
            DecoderEofAction.PROMOTE_GAPLESS,
            DecoderEofPolicy.action(
                hasPreparedNext = true,
                gaplessEnabled = true,
                crossfadeMs = 0L
            )
        )
    }

    @Test
    fun `gapless off drains even when a successor is prepared`() {
        assertEquals(
            DecoderEofAction.DRAIN_CURRENT_OUTPUT,
            DecoderEofPolicy.action(
                hasPreparedNext = true,
                gaplessEnabled = false,
                crossfadeMs = 0L
            )
        )
    }

    @Test
    fun `no successor drains and completes normally`() {
        assertEquals(
            DecoderEofAction.DRAIN_CURRENT_OUTPUT,
            DecoderEofPolicy.action(
                hasPreparedNext = false,
                gaplessEnabled = true,
                crossfadeMs = 0L
            )
        )
    }

    @Test
    fun `a missed crossfade boundary never turns into a gapless promotion`() {
        assertEquals(
            DecoderEofAction.DRAIN_CURRENT_OUTPUT,
            DecoderEofPolicy.action(
                hasPreparedNext = true,
                gaplessEnabled = true,
                crossfadeMs = 5_000L
            )
        )
    }
}
