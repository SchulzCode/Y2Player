package com.schulzcode.y2player.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbRefreshPolicyTest {
    @Test fun oneHapticCandidateIsProducedPerDisconnectedToConnectedTransition() {
        var previousConnected = false
        var initialSnapshot = true
        val candidates = listOf(false, true, true, false, true).map { currentConnected ->
            UsbConnectionTransitionPolicy.becameConnected(
                previousConnected,
                currentConnected,
                initialSnapshot
            ).also {
                previousConnected = currentConnected
                initialSnapshot = false
            }
        }

        assertEquals(listOf(false, true, false, false, true), candidates)
    }

    @Test fun anInitiallyConnectedDeviceDoesNotLookLikeANewConnection() {
        assertFalse(
            UsbConnectionTransitionPolicy.becameConnected(
                previousConnected = false,
                currentConnected = true,
                initialSnapshot = true
            )
        )
    }

    @Test fun unchangedChargingDoesNotRefresh() {
        assertFalse(UsbRefreshPolicy.onChargingSignal(currentCharging = true, reportedCharging = true).refresh)
        assertFalse(UsbRefreshPolicy.onChargingSignal(currentCharging = false, reportedCharging = false).refresh)
    }

    @Test fun chargingFalseToTrueRefreshes() {
        val decision = UsbRefreshPolicy.onChargingSignal(currentCharging = false, reportedCharging = true)
        assertTrue(decision.refresh)
        assertTrue(decision.charging)
    }

    @Test fun chargingTrueToFalseRefreshes() {
        val decision = UsbRefreshPolicy.onChargingSignal(currentCharging = true, reportedCharging = false)
        assertTrue(decision.refresh)
        assertFalse(decision.charging)
    }

    @Test fun usbStateBroadcastAlwaysRefreshes() {
        assertTrue(UsbRefreshPolicy.onUsbState(currentCharging = true).refresh)
        assertTrue(UsbRefreshPolicy.onUsbState(currentCharging = false).refresh)
    }

    @Test fun usbStateLeavesChargingUntouched() {
        assertTrue(UsbRefreshPolicy.onUsbState(currentCharging = true).charging)
        assertFalse(UsbRefreshPolicy.onUsbState(currentCharging = false).charging)
    }
}
