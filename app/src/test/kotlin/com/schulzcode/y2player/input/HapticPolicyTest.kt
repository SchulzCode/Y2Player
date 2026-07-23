package com.schulzcode.y2player.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticPolicyTest {

    // --- level mapping -------------------------------------------------------

    @Test fun defaultAndUnknownStoredValuesAreOff() {
        // A corrupt preference must never leave the device buzzing on every detent.
        assertEquals(HapticLevel.OFF, HapticLevel.fromStorage(null))
        assertEquals(HapticLevel.OFF, HapticLevel.fromStorage(""))
        assertEquals(HapticLevel.OFF, HapticLevel.fromStorage("extreme"))
    }

    @Test fun storedValuesRoundTrip() {
        for (level in HapticLevel.values()) {
            assertEquals(level, HapticLevel.fromStorage(level.storageId))
            assertEquals(level, HapticLevel.fromStorage(level.name))
        }
    }

    @Test fun levelsCycleThroughAllFourAndWrap() {
        var level = HapticLevel.OFF
        val seen = ArrayList<HapticLevel>()
        repeat(HapticLevel.values().size) { level = level.next(); seen.add(level) }
        assertEquals(HapticLevel.values().toList().drop(1) + HapticLevel.OFF, seen)
    }

    @Test fun durationsAreShortMonotonicAndOffIsZero() {
        assertEquals(0L, HapticLevel.OFF.durationMs)
        assertFalse(HapticLevel.OFF.enabled)
        var previous = 0L
        for (level in HapticLevel.values().drop(1)) {
            assertTrue(level.enabled)
            assertTrue("durations must increase", level.durationMs > previous)
            // A detent is a tick, not a buzz — and every millisecond is motor current.
            assertTrue("${level.name} pulse ${level.durationMs} ms is too long", level.durationMs <= 40L)
            previous = level.durationMs
        }
    }

    // --- rate limiting -------------------------------------------------------

    @Test fun firstDetentAlwaysPulses() {
        assertTrue(HapticRateLimiter(50L).allow(0L))
        assertTrue(HapticRateLimiter(50L).allow(1_000_000L))
    }

    @Test fun detentsInsideTheWindowAreSuppressed() {
        val limiter = HapticRateLimiter(50L)
        assertTrue(limiter.allow(1_000L))
        assertFalse(limiter.allow(1_010L))
        assertFalse(limiter.allow(1_049L))
        assertTrue("the boundary itself must pass", limiter.allow(1_050L))
    }

    /**
     * The failure this prevents: a fast spin emitting a detent every 20 ms would
     * queue pulses behind each other and leave the motor running after the wheel
     * stopped. Feedback must stay bounded and aligned with the wheel.
     */
    @Test fun fastSpinIsThinnedToABoundedTickRate() {
        val limiter = HapticRateLimiter(55L)
        var now = 0L
        repeat(100) { limiter.allow(now); now += 20L }
        // 100 detents over 2 seconds → at most ~2000/55 pulses.
        assertTrue("fired ${limiter.pulseCount()}", limiter.pulseCount() <= 100 * 20 / 55 + 1)
        assertEquals(100, limiter.pulseCount() + limiter.suppressedCount())
    }

    @Test fun slowScrollIsNeverThinned() {
        val limiter = HapticRateLimiter(55L)
        var now = 0L
        repeat(20) { assertTrue(limiter.allow(now)); now += 120L }
        assertEquals(0, limiter.suppressedCount())
    }

    @Test fun resetMakesTheNextDetentImmediate() {
        val limiter = HapticRateLimiter(50L)
        assertTrue(limiter.allow(0L))
        assertFalse(limiter.allow(10L))
        limiter.reset()
        assertTrue(limiter.allow(11L))
    }

    @Test fun drainingCountersZeroesThem() {
        val limiter = HapticRateLimiter(50L)
        limiter.allow(0L)
        limiter.allow(5L)
        val first = limiter.drainCounters()
        assertEquals(1, first[0])
        assertEquals(1, first[1])
        val second = limiter.drainCounters()
        assertEquals(0, second[0])
        assertEquals(0, second[1])
    }

    // --- acceptance rules ----------------------------------------------------

    @Test fun noPulseWhenTheDeviceHasNoMotor() {
        assertFalse(
            HapticPolicy.shouldPulse(HapticLevel.STRONG, available = false, onNowPlaying = true, stateChanged = true)
        )
    }

    @Test fun noPulseWhenTurnedOff() {
        assertFalse(
            HapticPolicy.shouldPulse(HapticLevel.OFF, available = true, onNowPlaying = true, stateChanged = true)
        )
    }

    /** A detent against the end of an empty list changes nothing, so it must be silent. */
    @Test fun noPulseWhenTheDetentChangedNothing() {
        assertFalse(
            HapticPolicy.shouldPulse(HapticLevel.LIGHT, available = true, onNowPlaying = false, stateChanged = false)
        )
    }

    @Test fun pulseWhenTheSelectionMoved() {
        assertTrue(
            HapticPolicy.shouldPulse(HapticLevel.LIGHT, available = true, onNowPlaying = false, stateChanged = true)
        )
    }

    /** On Now Playing the wheel sets volume via an effect, not a state change. */
    @Test fun pulseOnNowPlayingEvenWithoutAStateChange() {
        assertTrue(
            HapticPolicy.shouldPulse(HapticLevel.MEDIUM, available = true, onNowPlaying = true, stateChanged = false)
        )
    }
}
