package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeCurveTest {

    private fun stepDecibels(level: Int): Double =
        20.0 * Math.log10(VolumeCurve.gainForLevel(level).toDouble() / VolumeCurve.gainForLevel(level - 1))

    @Test fun levelZeroIsExactDigitalSilence() {
        // Not "very small" — exactly zero, so mute means silence.
        assertEquals(0f, VolumeCurve.gainForLevel(0), 0f)
    }

    @Test fun maximumLevelIsExactlyUnity() {
        // Exactly 1.0 means no app-level attenuation at full volume.
        assertEquals(1f, VolumeCurve.gainForLevel(VolumeCurve.STEPS), 0f)
    }

    @Test fun stepCountIsInTheRequestedRange() {
        assertTrue(VolumeCurve.STEPS in 30..40)
    }

    @Test fun mappingIsStrictlyMonotonic() {
        var previous = -1f
        for (level in 0..VolumeCurve.STEPS) {
            val gain = VolumeCurve.gainForLevel(level)
            assertTrue("level $level ($gain) must exceed $previous", gain > previous)
            previous = gain
        }
    }

    /**
     * The point of the curve: every audible step is the same perceptual size and
     * close to the ~1 dB just-noticeable difference. Level 0 is excluded because
     * it is silence by definition, so the ratio there is undefined.
     */
    @Test fun everyAudibleStepIsUniformAndNearTheJustNoticeableDifference() {
        val steps = (2..VolumeCurve.STEPS).map(::stepDecibels)
        val smallest = steps.minOrNull()!!
        val largest = steps.maxOrNull()!!
        assertTrue("largest step was $largest dB", largest <= 1.3)
        assertTrue("smallest step was $smallest dB", smallest >= 1.0)
        assertTrue("steps are not uniform: $smallest..$largest", largest - smallest < 0.05)
    }

    /** Cube law has an excessive first step and does not meet the uniformity requirement. */
    @Test fun cubeLawWouldViolateTheStepUniformityRequirement() {
        val first = 20.0 * Math.log10(
            VolumeCurve.cubeGain(2f / VolumeCurve.STEPS).toDouble() /
                VolumeCurve.cubeGain(1f / VolumeCurve.STEPS)
        )
        assertTrue("cube law first step is $first dB", first > 5.0)
    }

    @Test fun outOfRangeLevelsClampInsteadOfThrowing() {
        assertEquals(0f, VolumeCurve.gainForLevel(-5), 0f)
        assertEquals(1f, VolumeCurve.gainForLevel(VolumeCurve.STEPS + 99), 0f)
        assertEquals(0, VolumeCurve.clampLevel(-1))
        assertEquals(VolumeCurve.STEPS, VolumeCurve.clampLevel(9_999))
    }

    @Test fun halfTravelIsWellBelowHalfAmplitude() {
        // Half the fader is half the dB range, i.e. -24 dB, not 0.5 amplitude.
        val half = VolumeCurve.gainForLevel(VolumeCurve.STEPS / 2)
        assertEquals(Math.pow(10.0, VolumeCurve.RANGE_DB / 40.0).toFloat(), half, 1e-4f)
    }

    @Test fun adjustLevelSaturatesAtBothEnds() {
        assertEquals(0, VolumeCurve.adjustLevel(0, -1))
        assertEquals(1, VolumeCurve.adjustLevel(0, +1))
        assertEquals(VolumeCurve.STEPS, VolumeCurve.adjustLevel(VolumeCurve.STEPS, +1))
        assertEquals(VolumeCurve.STEPS - 1, VolumeCurve.adjustLevel(VolumeCurve.STEPS, -1))
    }

    @Test fun percentageSpansTheFullRange() {
        assertEquals(0, VolumeCurve.percentForLevel(0))
        assertEquals(100, VolumeCurve.percentForLevel(VolumeCurve.STEPS))
        assertEquals(50, VolumeCurve.percentForLevel(VolumeCurve.STEPS / 2))
    }

    /**
     * The gain is multiplied by the duck factor and by the fade/crossfade
     * fraction before reaching MediaPlayer. Composition must stay in range and
     * must collapse to silence whenever any factor is zero.
     */
    @Test fun composesWithDuckAndFadeFactors() {
        val duck = 0.2f
        for (level in 0..VolumeCurve.STEPS) {
            for (fade in listOf(0f, 0.25f, 0.5f, 1f)) {
                val composed = VolumeCurve.gainForLevel(level) * duck * fade
                assertTrue("composed $composed out of range", composed in 0f..1f)
            }
        }
        assertEquals(0f, VolumeCurve.gainForLevel(0) * duck * 1f, 0f)
        assertEquals(0f, VolumeCurve.gainForLevel(VolumeCurve.STEPS) * duck * 0f, 0f)
        assertEquals(duck, VolumeCurve.gainForLevel(VolumeCurve.STEPS) * duck * 1f, 1e-6f)
    }

    @Test fun volumeModeRoundTripsThroughStorageAndDefaultsSafely() {
        assertEquals(VolumeMode.SYSTEM, VolumeMode.fromStorage(null))
        assertEquals(VolumeMode.SYSTEM, VolumeMode.fromStorage("nonsense"))
        assertEquals(VolumeMode.PERCEPTUAL, VolumeMode.fromStorage("perceptual"))
        assertEquals(VolumeMode.PERCEPTUAL, VolumeMode.fromStorage(VolumeMode.PERCEPTUAL.name))
        assertEquals(VolumeMode.PERCEPTUAL, VolumeMode.SYSTEM.next())
        assertEquals(VolumeMode.SYSTEM, VolumeMode.PERCEPTUAL.next())
    }

    @Test fun volumeModeTransferPreservesEndpointsAndHalfTravel() {
        assertEquals(0, VolumeModeTransfer.appLevelFromSystemIndex(0, 15))
        assertEquals(VolumeCurve.STEPS, VolumeModeTransfer.appLevelFromSystemIndex(15, 15))
        assertEquals(VolumeCurve.STEPS / 2, VolumeModeTransfer.appLevelFromSystemIndex(5, 10))

        assertEquals(0, VolumeModeTransfer.systemIndexFromAppLevel(0, 15))
        assertEquals(15, VolumeModeTransfer.systemIndexFromAppLevel(VolumeCurve.STEPS, 15))
        assertEquals(5, VolumeModeTransfer.systemIndexFromAppLevel(VolumeCurve.STEPS / 2, 10))
    }

    @Test fun volumeModeTransferClampsInvalidInputsAndHandlesMissingStreamRange() {
        assertEquals(0, VolumeModeTransfer.appLevelFromSystemIndex(-4, 15))
        assertEquals(VolumeCurve.STEPS, VolumeModeTransfer.appLevelFromSystemIndex(99, 15))
        assertEquals(VolumeCurve.STEPS, VolumeModeTransfer.appLevelFromSystemIndex(0, 0))

        assertEquals(0, VolumeModeTransfer.systemIndexFromAppLevel(-4, 15))
        assertEquals(15, VolumeModeTransfer.systemIndexFromAppLevel(99, 15))
        assertEquals(0, VolumeModeTransfer.systemIndexFromAppLevel(20, 0))
    }

    @Test fun volumeModeTransferRoundTripStaysWithinStreamQuantizationError() {
        for (systemMax in 1..25) {
            for (level in 0..VolumeCurve.STEPS) {
                val systemIndex = VolumeModeTransfer.systemIndexFromAppLevel(level, systemMax)
                val restored = VolumeModeTransfer.appLevelFromSystemIndex(systemIndex, systemMax)
                val maximumQuantizationError = (VolumeCurve.STEPS + systemMax - 1) / systemMax
                assertTrue(
                    "level $level via $systemIndex/$systemMax restored as $restored",
                    Math.abs(restored - level) <= maximumQuantizationError
                )
            }
        }
    }
}
