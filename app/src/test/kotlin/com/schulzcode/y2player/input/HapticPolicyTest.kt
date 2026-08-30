package com.schulzcode.y2player.input

import com.schulzcode.y2player.storage.UsbConnectionTransitionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticPolicyTest {
    @Test fun defaultAndUnknownStoredValuesAreOff() {
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
            assertTrue("${level.name} pulse ${level.durationMs} ms is too long", level.durationMs <= 40L)
            previous = level.durationMs
        }
    }

    @Test fun firstAcceptedActionAlwaysPulses() {
        assertTrue(HapticRateLimiter(50L).allow(0L))
        assertTrue(HapticRateLimiter(50L).allow(1_000_000L))
    }

    @Test fun actionsInsideTheWindowAreSuppressed() {
        val limiter = HapticRateLimiter(50L)
        assertTrue(limiter.allow(1_000L))
        assertFalse(limiter.allow(1_010L))
        assertFalse(limiter.allow(1_049L))
        assertTrue("the boundary itself must pass", limiter.allow(1_050L))
    }

    @Test fun fastSpinIsThinnedToABoundedTickRate() {
        val limiter = HapticRateLimiter(55L)
        var now = 0L
        repeat(100) { limiter.allow(now); now += 20L }
        val (pulses, suppressed) = limiter.drainCounters()
        assertTrue("fired $pulses", pulses <= 100 * 20 / 55 + 1)
        assertEquals(100, pulses + suppressed)
    }

    @Test fun slowScrollIsNeverThinned() {
        val limiter = HapticRateLimiter(55L)
        var now = 0L
        repeat(20) { assertTrue(limiter.allow(now)); now += 120L }
        assertEquals(0, limiter.drainCounters()[1])
    }

    @Test fun resetMakesTheNextActionImmediate() {
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

    @Test fun noPulseWhenTheDeviceHasNoMotor() {
        assertFalse(
            HapticPolicy.shouldPulse(HapticLevel.STRONG, available = false, accepted = true)
        )
    }

    @Test fun noPulseWhenTurnedOff() {
        assertFalse(
            HapticPolicy.shouldPulse(HapticLevel.OFF, available = true, accepted = true)
        )
    }

    @Test fun usbConnectionDoesNotPulseWhenHapticsAreTurnedOff() {
        val becameConnected = UsbConnectionTransitionPolicy.becameConnected(
            previousConnected = false,
            currentConnected = true,
            initialSnapshot = false
        )

        assertFalse(
            HapticPolicy.shouldPulse(HapticLevel.OFF, available = true, accepted = becameConnected)
        )
        assertEquals(0L, HapticPolicy.usbConnectionDuration(HapticLevel.OFF, available = true))
    }

    @Test fun usbConnectionPulsesWhenHapticsAreEnabled() {
        val becameConnected = UsbConnectionTransitionPolicy.becameConnected(
            previousConnected = false,
            currentConnected = true,
            initialSnapshot = false
        )

        assertTrue(
            HapticPolicy.shouldPulse(HapticLevel.LIGHT, available = true, accepted = becameConnected)
        )
        assertEquals(
            HapticPolicy.USB_CONNECTION_DURATION_MS,
            HapticPolicy.usbConnectionDuration(HapticLevel.LIGHT, available = true)
        )
    }

    @Test fun usbConnectionUsesDedicatedLongPulseAndRequiresAVibrator() {
        assertEquals(500L, HapticPolicy.USB_CONNECTION_DURATION_MS)
        assertTrue(HapticPolicy.USB_CONNECTION_DURATION_MS > HapticLevel.STRONG.durationMs)
        assertEquals(0L, HapticPolicy.usbConnectionDuration(HapticLevel.STRONG, available = false))
    }

    @Test fun noPulseWhenTheActionWasNotAccepted() {
        assertFalse(
            HapticPolicy.shouldPulse(HapticLevel.LIGHT, available = true, accepted = false)
        )
    }

    @Test fun pulseWhenTheSelectionMoved() {
        assertTrue(
            HapticPolicy.shouldPulse(HapticLevel.LIGHT, available = true, accepted = true)
        )
    }

    @Test fun noPulseForAnUnacceptedNowPlayingAction() {
        assertFalse(HapticPolicy.shouldPulse(HapticLevel.MEDIUM, available = true, accepted = false))
    }
}
