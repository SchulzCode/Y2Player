package com.schulzcode.y2player.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Covers the PCM boundary: the partial-write loop, the playback-head
 * accumulator, and the gain/crossfade maths that now runs on ShortArray.
 */
class AudioOutputTest {

    private class FakeSink(private val chunk: (Int) -> Int) : PcmSink {
        val offsets = mutableListOf<Int>()
        val requests = mutableListOf<Int>()
        override fun writeSome(pcm: ShortArray, offsetShorts: Int, shortCount: Int): Int {
            offsets += offsetShorts
            requests += shortCount
            return chunk(shortCount)
        }
    }

    // ---- partial writes -----------------------------------------------------

    @Test fun partialWritesAreRetriedWithoutRepeatingSamples() {
        val pcm = ShortArray(10) { it.toShort() }
        val sink = FakeSink { remaining -> minOf(3, remaining) }

        assertEquals(10, PcmWriteLoop.writeFully(pcm, 0, pcm.size, sink))
        assertEquals(listOf(0, 3, 6, 9), sink.offsets)
        assertEquals(listOf(10, 7, 4, 1), sink.requests)
    }

    @Test fun aSingleZeroProgressWriteIsToleratedAndThenRecovers() {
        var call = 0
        val sink = FakeSink { remaining -> if (call++ == 0) 0 else remaining }

        assertEquals(4, PcmWriteLoop.writeFully(ShortArray(4), 0, 4, sink))
    }

    @Test fun repeatedZeroProgressFailsInsteadOfBusySpinning() {
        val sink = FakeSink { 0 }

        assertThrows(AudioOutputException::class.java) {
            PcmWriteLoop.writeFully(ShortArray(4), 0, 4, sink)
        }
        // Bounded: it must not have spun.
        assertEquals(3, sink.requests.size)
    }

    @Test fun negativeAudioTrackResultIsAnOutputFailure() {
        assertThrows(AudioOutputException::class.java) {
            PcmWriteLoop.writeFully(ShortArray(4), 0, 4, FakeSink { -3 })
        }
    }

    @Test fun aWriteClaimingMoreThanItWasAskedForIsRejected() {
        assertThrows(AudioOutputException::class.java) {
            PcmWriteLoop.writeFully(ShortArray(4), 0, 4, FakeSink { remaining -> remaining + 1 })
        }
    }

    @Test fun zeroLengthWriteDoesNothing() {
        val sink = FakeSink { it }
        assertEquals(0, PcmWriteLoop.writeFully(ShortArray(4), 0, 0, sink))
        assertEquals(0, sink.requests.size)
    }

    // ---- playback head ------------------------------------------------------

    @Test fun playbackHeadAccumulatesAcrossUnsignedWrap() {
        val accumulator = PlaybackHeadAccumulator()

        assertEquals(0xffff_fff0L, accumulator.update(0xffff_fff0L.toInt()))
        assertEquals(0x1_0000_0010L, accumulator.update(0x10))
    }

    @Test fun backwardsResetDoesNotInventBillionsOfFrames() {
        val accumulator = PlaybackHeadAccumulator()

        assertEquals(50_000L, accumulator.update(50_000))
        assertEquals(50_000L, accumulator.update(0))
    }

    @Test fun nativeErrorValuesHaveStableFallback() {
        assertEquals(NativeErrorCategory.ABORTED, NativeErrorCategory.fromWireValue(4))
        assertEquals(NativeErrorCategory.INTERNAL, NativeErrorCategory.fromWireValue(999))
    }

    // ---- gain ---------------------------------------------------------------

    @Test fun unityGainAtCentreBalanceLeavesEverySampleUntouched() {
        val pcm = shortArrayOf(1, -1, Short.MAX_VALUE, Short.MIN_VALUE)
        val expected = pcm.copyOf()

        PcmGain.apply(pcm, 0, pcm.size, PcmGain.UNITY, AudioBalance.CENTRE)

        assertArrayEquals(expected, pcm)
    }

    @Test fun gainNeverExceedsSigned16BitRange() {
        val pcm = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)

        PcmGain.apply(pcm, 0, 2, PcmGain.UNITY, AudioBalance.CENTRE)

        assertEquals(Short.MAX_VALUE, pcm[0])
        assertEquals(Short.MIN_VALUE, pcm[1])
    }

    @Test fun gainProcessesOnlyTheDecodedShortCount() {
        val pcm = ShortArray(8) { 1_000 }
        pcm[6] = 12_345
        pcm[7] = 12_345

        PcmGain.apply(pcm, 0, 6, level = 0.5f, balance = AudioBalance.CENTRE)

        assertEquals(500, pcm[0].toInt())
        assertEquals(500, pcm[5].toInt())
        assertEquals(12_345, pcm[6].toInt())
        assertEquals(12_345, pcm[7].toInt())
    }

    @Test fun balanceAttenuatesTheFarChannelByChannelNotByRawIndex() {
        val leaningLeft = shortArrayOf(10_000, 10_000, 10_000, 10_000)
        PcmGain.apply(leaningLeft, 0, 4, PcmGain.UNITY, balance = -100)
        assertEquals(10_000, leaningLeft[0].toInt())
        assertEquals(0, leaningLeft[1].toInt())
        assertEquals(10_000, leaningLeft[2].toInt())
        assertEquals(0, leaningLeft[3].toInt())

        val leaningRight = shortArrayOf(10_000, 10_000)
        PcmGain.apply(leaningRight, 0, 2, PcmGain.UNITY, balance = 100)
        assertEquals(0, leaningRight[0].toInt())
        assertEquals(10_000, leaningRight[1].toInt())
    }

    @Test fun anIncompleteFrameIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmGain.apply(ShortArray(4), 0, 3, 0.5f, AudioBalance.CENTRE)
        }
    }

    // ---- crossfade ----------------------------------------------------------

    @Test fun crossfadeProgressesLinearlyFromCurrentToNext() {
        assertEquals(listOf(20_000, -20_000), mixOneFrame(transitionFrame = 0))
        assertEquals(listOf(5_000, -5_000), mixOneFrame(transitionFrame = 50))
        assertEquals(listOf(-10_000, 10_000), mixOneFrame(transitionFrame = 100))
    }

    /**
     * The regression this replaced: the mixer used to run to
     * `max(current, next)` and pad the shorter side with silence, then consume
     * both blocks. One native decode returns one converted AVFrame, so the two
     * counts differ per codec — 1152 for MP3, 1024 for AAC, commonly 4096 for
     * FLAC — and the shorter track was stretched to `short/long` of real speed.
     * The mixer now takes an explicit frame count and never fabricates silence.
     */
    @Test fun crossfadeMixesOnlyTheFramesBothSidesSupply() {
        val current = shortArrayOf(10_000, 10_000, 1_111, 1_111)
        val next = shortArrayOf(20_000, 20_000, 30_000, 30_000)

        // Both decoders supplied one frame; the caller passes min(1, 2) = 1.
        PcmGain.crossfadeInto(
            current, currentOffsetShorts = 0,
            next, nextOffsetShorts = 0,
            frameCount = 1,
            transitionFrame = 0, transitionFrames = 2,
            level = PcmGain.UNITY, balance = AudioBalance.CENTRE
        )

        // Frame 0 mixed at fraction 0 -> current only.
        assertEquals(10_000, current[0].toInt())
        // Frame 1 untouched: it is the unconsumed remainder, not silence.
        assertEquals(1_111, current[2].toInt())
    }

    /** The longer block's remainder is read from its own offset next turn. */
    @Test fun crossfadeReadsEachSideFromItsOwnOffset() {
        val current = shortArrayOf(0, 0, 10_000, 10_000)
        val next = shortArrayOf(0, 0, 0, 0, 20_000, 20_000)

        PcmGain.crossfadeInto(
            current, currentOffsetShorts = 2,
            next, nextOffsetShorts = 4,
            frameCount = 1,
            transitionFrame = 2, transitionFrames = 2,
            level = PcmGain.UNITY, balance = AudioBalance.CENTRE
        )

        // fraction 1.0 -> next only, written at the current block's offset.
        assertEquals(20_000, current[2].toInt())
        assertEquals(20_000, current[3].toInt())
        // Nothing before the offset was touched.
        assertEquals(0, current[0].toInt())
    }

    @Test fun crossfadeSaturatesRatherThanWrappingAround() {
        val current = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)
        val next = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)

        PcmGain.crossfadeInto(
            current, 0, next, 0,
            frameCount = 1,
            transitionFrame = 1, transitionFrames = 2,
            level = PcmGain.UNITY, balance = AudioBalance.CENTRE
        )

        assertEquals(Short.MAX_VALUE.toInt(), current[0].toInt())
        assertEquals(Short.MIN_VALUE.toInt(), current[1].toInt())
    }

    @Test fun crossfadeAppliesEachTracksReplayGainIndependently() {
        val current = shortArrayOf(10_000, 10_000)
        val next = shortArrayOf(10_000, 10_000)

        PcmGain.crossfadeInto(
            current, 0, next, 0,
            frameCount = 1,
            transitionFrame = 1, transitionFrames = 2,
            level = 0.5f,
            nextLevel = 1f,
            balance = AudioBalance.CENTRE
        )

        // Midpoint: 10,000 * (0.5 ReplayGain * 0.5 fade + 1.0 * 0.5 fade).
        assertEquals(7_500, current[0].toInt())
        assertEquals(7_500, current[1].toInt())
    }

    @Test fun crossfadeOfZeroFramesTouchesNothing() {
        val current = shortArrayOf(7, 7, 7, 7)
        PcmGain.crossfadeInto(
            current, 0, ShortArray(4), 0,
            frameCount = 0,
            transitionFrame = 0, transitionFrames = 10,
            level = PcmGain.UNITY, balance = AudioBalance.CENTRE
        )
        assertEquals(7, current[0].toInt())
    }

    private fun mixOneFrame(transitionFrame: Long): List<Int> {
        val current = shortArrayOf(20_000, -20_000)
        val next = shortArrayOf(-10_000, 10_000)
        PcmGain.crossfadeInto(
            current, 0, next, 0,
            frameCount = 1,
            transitionFrame, transitionFrames = 100,
            level = PcmGain.UNITY, balance = AudioBalance.CENTRE
        )
        return listOf(current[0].toInt(), current[1].toInt())
    }
}
