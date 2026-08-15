package com.schulzcode.y2player.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun floatAndPcm16GeometryUseExplicitDifferentByteCounts() {
        assertEquals(8, PcmFormat.FLOAT_BYTES_PER_FRAME)
        assertEquals(4, PcmFormat.PCM16_BYTES_PER_FRAME)
        assertEquals(PcmFormat.BLOCK_SAMPLES * 4, PcmFormat.FLOAT_BLOCK_BYTES)
        assertEquals(PcmFormat.BLOCK_SAMPLES * 2, PcmFormat.PCM16_BLOCK_BYTES)
    }

    @Test fun normalizedValuesAndFullScaleQuantizeDeterministically() {
        val staging = Pcm16StagingBuffer(9)
        val result = staging.stage(
            floatArrayOf(-1f, -0.5f, -1f / 32_768f, 0f, 1f / 32_768f, 0.5f, 1f, 2f, -2f),
            0,
            9
        )

        assertArrayEquals(
            shortArrayOf(-32_768, -16_384, -1, 0, 1, 16_384, 32_767, 32_767, -32_768),
            result
        )
    }

    @Test fun nonFiniteValuesBecomeSilenceRatherThanFullScaleNoise() {
        val staging = Pcm16StagingBuffer(5)
        val result = staging.stage(
            floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.5f, -1.5f),
            0,
            5
        )

        assertArrayEquals(shortArrayOf(0, 0, 0, 32_767, -32_768), result)
    }

    @Test fun stagingConvertsOnlyTheRequestedSubrangeAndKeepsChannelOrder() {
        val staging = Pcm16StagingBuffer(6)
        staging.samples.fill(1_234)
        val source = floatArrayOf(9f, 9f, 0.25f, -0.5f, 9f, 9f)
        val originalSource = source.copyOf()

        val result = staging.stage(source, offsetSamples = 2, sampleCount = 2)

        assertArrayEquals(shortArrayOf(1_234, 1_234, 8_192, -16_384, 1_234, 1_234), result)
        assertArrayEquals(originalSource, source, 0f)
    }

    @Test fun stagingArrayIsReusedAcrossWrites() {
        val staging = Pcm16StagingBuffer(4)
        val first = staging.stage(floatArrayOf(0f, 0f, 0f, 0f), 0, 4)
        val second = staging.stage(floatArrayOf(0.5f, -0.5f, 0f, 0f), 0, 4)

        assertSame(first, second)
    }

    @Test fun everyPcm16OriginValueRoundTripsExactlyThroughFloat() {
        val staging = Pcm16StagingBuffer(1)
        val source = FloatArray(1)
        for (value in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()) {
            source[0] = value / 32_768f
            assertEquals(value.toShort(), staging.stage(source, 0, 1)[0])
        }
    }

    @Test fun highPrecisionSurvivesDspUntilTheFinalBoundary() {
        val source = floatArrayOf(0.123_456_7f, -0.234_567_8f)
        PcmGain.apply(source, 0, source.size, 0.5f, AudioBalance.CENTRE)

        assertEquals(0.061_728_35f, source[0], 0.000_000_01f)
        assertEquals(-0.117_283_9f, source[1], 0.000_000_01f)
        val output = Pcm16StagingBuffer(2).stage(source, 0, 2)
        assertEquals(Math.round(source[0] * 32_768f).toShort(), output[0])
        assertEquals(Math.round(source[1] * 32_768f).toShort(), output[1])
    }

    @Test fun partialWritesRetainTheRequestedOffsetWithoutRepeatingSamples() {
        val pcm = ShortArray(12) { it.toShort() }
        val sink = FakeSink { remaining -> minOf(3, remaining) }

        assertEquals(8, PcmWriteLoop.writeFully(pcm, 2, 8, sink))
        assertEquals(listOf(2, 5, 8), sink.offsets)
        assertEquals(listOf(8, 5, 2), sink.requests)
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
        assertEquals(3, sink.requests.size)
    }

    @Test fun invalidAudioTrackResultsAreOutputFailures() {
        assertThrows(AudioOutputException::class.java) {
            PcmWriteLoop.writeFully(ShortArray(4), 0, 4, FakeSink { -3 })
        }
        assertThrows(AudioOutputException::class.java) {
            PcmWriteLoop.writeFully(ShortArray(4), 0, 4, FakeSink { it + 1 })
        }
    }

    @Test fun zeroLengthWriteDoesNothing() {
        val sink = FakeSink { it }
        assertEquals(0, PcmWriteLoop.writeFully(ShortArray(4), 0, 0, sink))
        assertEquals(0, sink.requests.size)
    }

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

    @Test fun explicitFlushResetRebasesAHeadThatTheDriverDidNotReset() {
        val accumulator = PlaybackHeadAccumulator()
        assertEquals(50_000L, accumulator.update(50_000))

        accumulator.reset()

        assertEquals(0L, accumulator.update(50_000))
        assertEquals(512L, accumulator.update(50_512))
    }

    @Test fun nativeErrorValuesHaveStableFallback() {
        assertEquals(NativeErrorCategory.ABORTED, NativeErrorCategory.fromWireValue(4))
        assertEquals(NativeErrorCategory.INTERNAL, NativeErrorCategory.fromWireValue(999))
    }

    @Test fun unityGainAtCentreBalanceLeavesEverySampleUntouched() {
        val pcm = floatArrayOf(0.1f, -0.1f, 1.25f, -1.25f)
        val expected = pcm.copyOf()

        PcmGain.apply(pcm, 0, pcm.size, PcmGain.UNITY, AudioBalance.CENTRE)

        assertArrayEquals(expected, pcm, 0f)
    }

    @Test fun reducedAndIncreasedGainRetainFloatPrecisionAndHeadroom() {
        val reduced = floatArrayOf(0.75f, -0.75f)
        PcmGain.apply(reduced, 0, 2, 0.5f, AudioBalance.CENTRE)
        assertArrayEquals(floatArrayOf(0.375f, -0.375f), reduced, 0f)

        val increased = floatArrayOf(0.75f, -0.75f)
        PcmGain.apply(increased, 0, 2, 2f, AudioBalance.CENTRE)
        assertArrayEquals(floatArrayOf(1.5f, -1.5f), increased, 0f)
    }

    @Test fun composedApplicationReplayGainDuckAndFadeUseOneGainPass() {
        val pcm = floatArrayOf(1f, -1f)
        val applicationGain = 0.8f
        val replayGain = 1.25f
        val duckGain = 0.5f
        val fadeCoefficient = 0.25f

        PcmGain.apply(
            pcm,
            0,
            pcm.size,
            applicationGain * replayGain * duckGain * fadeCoefficient,
            AudioBalance.CENTRE
        )

        assertArrayEquals(floatArrayOf(0.125f, -0.125f), pcm, 0.000_001f)
    }

    @Test fun gainProcessesOnlyTheRequestedSampleRange() {
        val pcm = FloatArray(8) { 0.5f }
        pcm[0] = 0.75f
        pcm[1] = 0.75f
        pcm[6] = 0.75f
        pcm[7] = 0.75f

        PcmGain.apply(pcm, 2, 4, level = 0.5f, balance = AudioBalance.CENTRE)

        assertEquals(0.75f, pcm[0], 0f)
        assertEquals(0.25f, pcm[2], 0f)
        assertEquals(0.25f, pcm[5], 0f)
        assertEquals(0.75f, pcm[6], 0f)
    }

    @Test fun balanceAttenuatesTheFarChannelWithoutReversingChannels() {
        val leaningLeft = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        PcmGain.apply(leaningLeft, 0, 4, PcmGain.UNITY, balance = -100)
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0.5f, 0f), leaningLeft, 0f)

        val leaningRight = floatArrayOf(0.5f, 0.5f)
        PcmGain.apply(leaningRight, 0, 2, PcmGain.UNITY, balance = 100)
        assertArrayEquals(floatArrayOf(0f, 0.5f), leaningRight, 0f)
    }

    @Test fun anIncompleteFrameIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmGain.apply(FloatArray(4), 0, 3, 0.5f, AudioBalance.CENTRE)
        }
    }

    @Test fun equalSizeCrossfadeProgressesLinearlyFromCurrentToNext() {
        assertFloatListEquals(listOf(0.8f, -0.8f), mixOneFrame(transitionFrame = 0))
        assertFloatListEquals(listOf(0.2f, -0.2f), mixOneFrame(transitionFrame = 50))
        assertFloatListEquals(listOf(-0.4f, 0.4f), mixOneFrame(transitionFrame = 100))
    }

    @Test fun unequalCrossfadeMixesOnlyTheCommonFramesAndPreservesRemainder() {
        val current = floatArrayOf(0.25f, 0.25f, 0.123_456f, -0.123_456f)
        val next = floatArrayOf(0.5f, 0.5f, 0.75f, 0.75f)

        PcmGain.crossfadeInto(
            current, 0, next, 0,
            frameCount = 1,
            transitionFrame = 0,
            transitionFrames = 2,
            level = PcmGain.UNITY,
            balance = AudioBalance.CENTRE
        )

        assertEquals(0.25f, current[0], 0f)
        assertEquals(0.123_456f, current[2], 0f)
        assertEquals(-0.123_456f, current[3], 0f)
    }

    @Test fun crossfadeReadsEachSideFromItsOwnOffset() {
        val current = floatArrayOf(0f, 0f, 0.25f, -0.25f)
        val next = floatArrayOf(0f, 0f, 0f, 0f, 0.75f, -0.75f)

        PcmGain.crossfadeInto(
            current, 2, next, 4,
            frameCount = 1,
            transitionFrame = 2,
            transitionFrames = 2,
            level = PcmGain.UNITY,
            balance = AudioBalance.CENTRE
        )

        assertArrayEquals(floatArrayOf(0f, 0f, 0.75f, -0.75f), current, 0f)
    }

    @Test fun crossfadeDoesNotClipTemporaryHeadroom() {
        val current = floatArrayOf(0.9f, -0.9f)
        val next = floatArrayOf(0.9f, -0.9f)

        PcmGain.crossfadeInto(
            current, 0, next, 0,
            frameCount = 1,
            transitionFrame = 1,
            transitionFrames = 2,
            level = 2f,
            balance = AudioBalance.CENTRE
        )

        assertArrayEquals(floatArrayOf(1.8f, -1.8f), current, 0.000_001f)
    }

    @Test fun crossfadeAppliesEachTracksReplayGainIndependently() {
        val current = floatArrayOf(0.5f, 0.5f)
        val next = floatArrayOf(0.5f, 0.5f)

        PcmGain.crossfadeInto(
            current, 0, next, 0,
            frameCount = 1,
            transitionFrame = 1,
            transitionFrames = 2,
            level = 0.5f,
            nextLevel = 1f,
            balance = AudioBalance.CENTRE
        )

        assertArrayEquals(floatArrayOf(0.375f, 0.375f), current, 0.000_001f)
    }

    @Test fun crossfadeOfZeroFramesTouchesNothing() {
        val current = floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f)
        PcmGain.crossfadeInto(
            current, 0, FloatArray(4), 0,
            frameCount = 0,
            transitionFrame = 0,
            transitionFrames = 10,
            level = PcmGain.UNITY,
            balance = AudioBalance.CENTRE
        )
        assertArrayEquals(floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f), current, 0f)
    }

    private fun mixOneFrame(transitionFrame: Long): List<Float> {
        val current = floatArrayOf(0.8f, -0.8f)
        val next = floatArrayOf(-0.4f, 0.4f)
        PcmGain.crossfadeInto(
            current, 0, next, 0,
            frameCount = 1,
            transitionFrame = transitionFrame,
            transitionFrames = 100,
            level = PcmGain.UNITY,
            balance = AudioBalance.CENTRE
        )
        return current.toList()
    }

    private fun assertFloatListEquals(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals(expected[index], actual[index], 0.000_001f)
        }
    }
}
