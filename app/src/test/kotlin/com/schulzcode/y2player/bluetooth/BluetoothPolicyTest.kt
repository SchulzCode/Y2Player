package com.schulzcode.y2player.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothPolicyTest {
    @Test fun disconnectedA2dpAllowsScan() {
        assertFalse(BluetoothScanPolicy.hasActiveA2dp(anyConnected = false, anyPlaying = false))
    }

    @Test fun connectedOrPlayingA2dpBlocksScan() {
        assertTrue(BluetoothScanPolicy.hasActiveA2dp(anyConnected = true, anyPlaying = false))
        assertTrue(BluetoothScanPolicy.hasActiveA2dp(anyConnected = false, anyPlaying = true))
        assertTrue(BluetoothScanPolicy.hasActiveA2dp(anyConnected = true, anyPlaying = true))
    }

    @Test fun scanPolicyHasStableMessageAndCancellationRule() {
        assertEquals("Disconnect Bluetooth audio before scanning", BluetoothScanPolicy.BLOCKED_MESSAGE)
        assertTrue(BluetoothScanPolicy.shouldCancelDiscovery(isDiscovering = true))
        assertFalse(BluetoothScanPolicy.shouldCancelDiscovery(isDiscovering = false))
    }

    @Test fun lifecycleStopsImmediatelyOnlyWhenIdle() {
        assertTrue(BluetoothLifecyclePolicy.stopImmediately(hasPendingOperation = false))
        assertFalse(BluetoothLifecyclePolicy.stopImmediately(hasPendingOperation = true))
    }

    @Test fun deferredLifecycleStopRequiresGoneUiAndIdleOperation() {
        assertTrue(BluetoothLifecyclePolicy.stopAfterIdle(false, true, false))
        assertFalse(BluetoothLifecyclePolicy.stopAfterIdle(false, true, true))
        assertFalse(BluetoothLifecyclePolicy.stopAfterIdle(true, true, false))
        assertFalse(BluetoothLifecyclePolicy.stopAfterIdle(false, false, false))
    }
}
