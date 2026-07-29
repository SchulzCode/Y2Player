package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the reusable decode block.
 *
 * The bug this exists for: a reusable typed buffer view had its limit
 * narrowed per block, and on API 19 that leaves the *backing* ByteBuffer's limit
 * reduced even after `clear()`, which surfaced on device as
 * `IndexOutOfBoundsException: index=14988, limit=14988`. The block now performs
 * exactly one bulk copy and never narrows the view, so the sizes from those
 * device logs are pinned here.
 */
class PcmBlockTest {

    private val mp3SizedFrames = 47      // 376 float PCM bytes
    private val flacSizedFrames = 3_747  // 29,976 float PCM bytes

    @Test fun repeatedSmallBlocksReuseTheSameArrayAndLeaveTheViewAtFullCapacity() {
        val block = FfmpegPlaybackEngine.PcmBlock()
        val array = block.pcm

        repeat(2) { round ->
            fill(block, mp3SizedFrames, valueOffset = round * 100)
            block.stage(mp3SizedFrames)
            block.consumeAll(mp3SizedFrames)
            assertSame(array, block.pcm)
            assertBufferUnnarrowed(block)
        }
    }

    @Test fun alternatingFlacAndMp3SizedBlocksNeverNarrowTheBuffer() {
        val block = FfmpegPlaybackEngine.PcmBlock()

        listOf(flacSizedFrames, mp3SizedFrames, flacSizedFrames, mp3SizedFrames)
            .forEachIndexed { round, frames ->
                fill(block, frames, valueOffset = round * 100)
                block.stage(frames)
                block.consumeAll(frames)
                assertBufferUnnarrowed(block)
                assertEquals(
                    sampleValue(round * 100),
                    block.pcm[0],
                    0f
                )
                assertEquals(
                    sampleValue(round * 100 + frames * PcmFormat.CHANNELS - 1),
                    block.pcm[frames * PcmFormat.CHANNELS - 1],
                    0f
                )
            }
    }

    @Test fun exactlyFullAndEmptyBlocksAreBothValid() {
        val block = FfmpegPlaybackEngine.PcmBlock()

        block.stage(PcmFormat.BLOCK_FRAMES)
        block.consumeAll(PcmFormat.BLOCK_FRAMES)
        assertBufferUnnarrowed(block)

        block.stage(0)
        assertEquals(0, block.remainingFrameCount)
        assertBufferUnnarrowed(block)
    }

    /** Zero is a real value: it means EOF, and must stay staged and sticky. */
    @Test fun aStagedZeroFrameBlockReportsEndOfStreamAndStaysStaged() {
        val block = FfmpegPlaybackEngine.PcmBlock()
        assertFalse(block.hasStagedBlock)

        block.stage(0)
        assertTrue(block.hasStagedBlock)
        assertTrue(block.atEndOfStream)
        assertEquals(0, block.remainingFrameCount)
        // Consuming nothing must not release an EOF marker.
        block.consume(0)
        assertTrue(block.hasStagedBlock)
    }

    @Test fun fullConsumptionReleasesTheBlockAndDiscardDropsIt() {
        val block = FfmpegPlaybackEngine.PcmBlock()

        block.stage(mp3SizedFrames)
        assertTrue(block.hasStagedBlock)
        block.consume(mp3SizedFrames)
        assertFalse(block.hasStagedBlock)

        block.stage(mp3SizedFrames)
        block.discard()
        assertFalse(block.hasStagedBlock)
    }

    /**
     * The F1 case: a crossfade consumes only what both sides supply, so the
     * longer block must keep its remainder and its read offset.
     */
    @Test fun aPartiallyConsumedBlockKeepsItsRemainderAndOffset() {
        val block = FfmpegPlaybackEngine.PcmBlock()
        fill(block, flacSizedFrames, valueOffset = 0)
        block.stage(flacSizedFrames)

        block.consume(mp3SizedFrames)

        assertTrue(block.hasStagedBlock)
        assertEquals(flacSizedFrames - mp3SizedFrames, block.remainingFrameCount)
        assertEquals(mp3SizedFrames * PcmFormat.CHANNELS, block.consumedSampleOffset)
        // The remainder is the real decoded audio, not moved or cleared.
        assertEquals(
            sampleValue(mp3SizedFrames * PcmFormat.CHANNELS),
            block.pcm[block.consumedSampleOffset],
            0f
        )

        block.consume(flacSizedFrames - mp3SizedFrames)
        assertFalse(block.hasStagedBlock)
    }

    @Test fun consumingMoreThanRemainsIsRejected() {
        val block = FfmpegPlaybackEngine.PcmBlock()
        block.stage(mp3SizedFrames)

        assertThrows(IllegalArgumentException::class.java) {
            block.consume(mp3SizedFrames + 1)
        }
    }

    /**
     * Frames, not bytes and not samples. The old API took a byte count and had to
     * prove at runtime that it divided cleanly; an over-long block is now simply
     * not representable.
     */
    @Test fun aFrameCountBeyondTheBlockIsRejected() {
        val block = FfmpegPlaybackEngine.PcmBlock()

        assertThrows(IllegalArgumentException::class.java) {
            block.stage(PcmFormat.BLOCK_FRAMES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            block.stage(-1)
        }
    }

    private fun fill(block: FfmpegPlaybackEngine.PcmBlock, frames: Int, valueOffset: Int) {
        for (index in 0 until frames * PcmFormat.CHANNELS) {
            block.bytes.putFloat(
                index * PcmFormat.FLOAT_BYTES_PER_SAMPLE,
                sampleValue(valueOffset + index)
            )
        }
    }

    private fun sampleValue(index: Int): Float = index / 10_000f + 0.000_001f

    private fun FfmpegPlaybackEngine.PcmBlock.consumeAll(frames: Int) {
        assertEquals(frames, remainingFrameCount)
        consume(frames)
    }

    private fun assertBufferUnnarrowed(block: FfmpegPlaybackEngine.PcmBlock) {
        assertEquals(PcmFormat.FLOAT_BLOCK_BYTES, block.bytes.capacity())
        assertEquals(PcmFormat.FLOAT_BLOCK_BYTES, block.bytes.limit())
        assertEquals(0, block.bytes.position())
    }
}
