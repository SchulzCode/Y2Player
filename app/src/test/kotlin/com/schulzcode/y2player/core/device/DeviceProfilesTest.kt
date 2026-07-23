package com.schulzcode.y2player.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceProfilesTest {

    // ---------- parseVirtualSize ----------

    @Test fun parsesTheStandardNodeFormat() {
        assertEquals(480 to 720, DeviceProfiles.parseVirtualSize("480,720"))
        assertEquals(480 to 360, DeviceProfiles.parseVirtualSize("480,360\n"))
        assertEquals(320 to 240, DeviceProfiles.parseVirtualSize("  320 , 240  "))
    }

    @Test fun rejectsMissingMalformedAndImplausibleValues() {
        assertNull(DeviceProfiles.parseVirtualSize(null))
        assertNull(DeviceProfiles.parseVirtualSize(""))
        assertNull(DeviceProfiles.parseVirtualSize("   "))
        assertNull(DeviceProfiles.parseVirtualSize("480"))
        assertNull(DeviceProfiles.parseVirtualSize("garbage"))
        assertNull(DeviceProfiles.parseVirtualSize("480,abc"))
        assertNull(DeviceProfiles.parseVirtualSize("0,0"))
        assertNull(DeviceProfiles.parseVirtualSize("-480,360"))
        // Implausibly large: treat as unreadable rather than trust it.
        assertNull(DeviceProfiles.parseVirtualSize("99999,99999"))
    }

    // ---------- double buffering ----------

    @Test fun resolvesDoubleAndTripleBufferedHeightsAgainstTheMetricsHint() {
        // 480x360 panel, double buffered -> node reports 720.
        assertEquals(360, DeviceProfiles.resolveVisibleHeight(480, 720, hintHeight = 360))
        // Triple buffered.
        assertEquals(360, DeviceProfiles.resolveVisibleHeight(480, 1080, hintHeight = 360))
        // Already unbuffered.
        assertEquals(360, DeviceProfiles.resolveVisibleHeight(480, 360, hintHeight = 360))
    }

    @Test fun resolvesWithoutAHintByPreferringALandscapePanel() {
        // No DisplayMetrics available: 720 must not be taken literally for a
        // 480-wide panel, because that would imply a portrait device.
        assertEquals(360, DeviceProfiles.resolveVisibleHeight(480, 720, hintHeight = 0))
    }

    @Test fun leavesIndivisibleOrDegenerateValuesAlone() {
        assertEquals(365, DeviceProfiles.resolveVisibleHeight(480, 365, hintHeight = 0))
        assertEquals(0, DeviceProfiles.resolveVisibleHeight(480, 0, hintHeight = 360))
        assertEquals(720, DeviceProfiles.resolveVisibleHeight(0, 720, hintHeight = 360))
    }

    // ---------- classification ----------

    private fun panel(w: Int, h: Int) = PanelGeometry(w, h, PanelSource.SYSFS)

    @Test fun socAndPanelAgreeingGivesHighConfidence() {
        assertEquals(
            DeviceFamily.Y2 to DeviceConfidence.HIGH,
            DeviceProfiles.classify("mt6582", "Y2", panel(480, 360))
        )
        assertEquals(
            DeviceFamily.Y1 to DeviceConfidence.HIGH,
            DeviceProfiles.classify("mt6572", "Y1", panel(480, 360))
        )
    }

    /** The SoC is trusted even when the model string disagrees with it. */
    @Test fun socOverridesAnUntrustworthyModelString() {
        assertEquals(
            DeviceFamily.Y2 to DeviceConfidence.HIGH,
            DeviceProfiles.classify("mt6582", "Y1", panel(480, 360))
        )
    }

    /** A 480x360 panel alone is insufficient to identify the device family. */
    @Test fun panelGeometryAloneNeverIdentifiesTheDevice() {
        assertEquals(
            DeviceFamily.UNKNOWN to DeviceConfidence.NONE,
            DeviceProfiles.classify("", "", panel(480, 360))
        )
    }

    @Test fun modelStringAloneIsLowConfidence() {
        assertEquals(
            DeviceFamily.Y2 to DeviceConfidence.LOW,
            DeviceProfiles.classify("unknown_hw", "Y2", panel(0, 0))
        )
    }

    @Test fun unrecognisedDevicesStayUnknown() {
        assertEquals(
            DeviceFamily.UNKNOWN to DeviceConfidence.NONE,
            DeviceProfiles.classify("qcom", "SM-G900F", panel(1080, 1920))
        )
        assertEquals(DeviceFamily.UNKNOWN, DeviceProfile.UNRESOLVED.family)
        assertEquals(DeviceConfidence.NONE, DeviceProfile.UNRESOLVED.confidence)
    }

    @Test fun summaryIsSingleLineAndCarriesTheKeyFacts() {
        val profile = DeviceProfile.UNRESOLVED.copy(
            panel = panel(480, 360),
            displayWidth = 480,
            displayHeight = 360,
            hardware = "mt6582",
            model = "Y2",
            family = DeviceFamily.Y2,
            confidence = DeviceConfidence.HIGH
        )
        val summary = profile.summary()
        assertEquals(false, summary.contains("\n"))
        listOf("Y2", "high", "480x360", "mt6582").forEach {
            assert(summary.contains(it)) { "summary missing '$it': $summary" }
        }
    }
}
