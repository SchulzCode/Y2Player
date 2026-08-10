package com.schulzcode.y2player.power

import com.schulzcode.y2player.playback.VolumeCurve
import com.schulzcode.y2player.playback.VolumeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryWarningControllerTest {

    @Test fun mutedVolumeSuppressesWarningsInBothVolumeModes() {
        assertEquals(0f, BatteryWarningVolume.localGain(VolumeMode.PERCEPTUAL, 0, 15), 0f)
        assertEquals(0f, BatteryWarningVolume.localGain(VolumeMode.SYSTEM, VolumeCurve.STEPS, 0), 0f)
    }

    @Test fun lowInAppVolumeUsesThePlaybackGainWhileSystemModeUsesItsStream() {
        val gain = BatteryWarningVolume.localGain(VolumeMode.PERCEPTUAL, 1, 15)
        assertEquals(VolumeCurve.gainForLevel(1), gain, 0f)
        assertTrue(gain in 0f..1f)
        assertEquals(1f, BatteryWarningVolume.localGain(VolumeMode.SYSTEM, 1, 1), 0f)
    }

    @Test fun maximumVolumeAndBothWarningThresholdsPlayOnce() {
        assertEquals(
            1f,
            BatteryWarningVolume.localGain(VolumeMode.PERCEPTUAL, VolumeCurve.STEPS, 15),
            0f
        )
        val gate = BatteryWarningGate()
        assertEquals(BatteryWarning.LOW, gate.update(15, charging = false))
        assertNull(gate.update(14, charging = false))
        assertEquals(BatteryWarning.CRITICAL, gate.update(5, charging = false))
        assertNull(gate.update(4, charging = false))
    }
}
