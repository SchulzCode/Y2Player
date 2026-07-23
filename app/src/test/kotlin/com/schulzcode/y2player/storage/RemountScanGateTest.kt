package com.schulzcode.y2player.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemountScanGateTest {
    @Test fun duplicateMountBroadcastsProduceOneScan() {
        val gate = RemountScanGate(10_000)
        assertTrue(gate.onVolumesMounted(listOf("sdcard"), 1_000))
        assertFalse(gate.onVolumesMounted(listOf("sdcard"), 1_600))
    }

    @Test fun mountThenContentHintProducesOneScan() {
        val gate = RemountScanGate(10_000)
        assertTrue(gate.onVolumesMounted(listOf("sdcard"), 1_000))
        assertFalse(gate.onContentHint(6_000))
    }

    @Test fun laterContentChangeStillScans() {
        val gate = RemountScanGate(10_000)
        gate.onVolumesMounted(listOf("sdcard"), 1_000)
        assertTrue(gate.onContentHint(11_000))
    }

    @Test fun storageReturningAfterStartupSchedulesScan() {
        val gate = RemountScanGate(10_000)
        assertTrue(gate.onVolumesMounted(listOf("sdcard"), 30_000))
    }
}
