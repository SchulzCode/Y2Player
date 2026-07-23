package com.schulzcode.y2player.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSysfsTest {

    @Test fun parsesTheCommaSeparatedKernelForm() {
        assertEquals(setOf("mtp", "adb"), UsbSysfs.parseFunctions("mtp,adb"))
    }

    @Test fun toleratesVendorVariationsAndTrailingNewlines() {
        // Seen in the wild on MTK 4.4: space separation and a trailing newline.
        assertEquals(setOf("mtp", "adb"), UsbSysfs.parseFunctions("mtp adb\n"))
        assertEquals(setOf("mtp"), UsbSysfs.parseFunctions("  MTP  "))
        assertEquals(emptySet<String>(), UsbSysfs.parseFunctions(""))
        assertEquals(emptySet<String>(), UsbSysfs.parseFunctions(null))
        assertEquals(emptySet<String>(), UsbSysfs.parseFunctions("   \n"))
    }

    @Test fun recognisesTheModesWeReportOn() {
        assertTrue(UsbSysfs.hasMtp(setOf("mtp")))
        assertTrue("PTP is the same transfer path for our purposes", UsbSysfs.hasMtp(setOf("ptp")))
        assertFalse(UsbSysfs.hasMtp(setOf("adb")))
        assertTrue(UsbSysfs.hasAdb(setOf("mtp", "adb")))
        // Both spellings exist; the older one is still used by 4.4 MTK kernels.
        assertTrue(UsbSysfs.hasMassStorage(setOf("mass_storage")))
        assertTrue(UsbSysfs.hasMassStorage(setOf("usb_mass_storage")))
        assertFalse(UsbSysfs.hasMassStorage(setOf("mtp", "adb")))
    }

    @Test fun readsTheGadgetStateNode() {
        assertTrue(UsbSysfs.isConfigured("CONFIGURED\n"))
        assertTrue(UsbSysfs.isConfigured("configured"))
        assertFalse(UsbSysfs.isConfigured("CONNECTED"))
        assertFalse(UsbSysfs.isConfigured(null))
        assertTrue(UsbSysfs.isConnected("CONNECTED"))
        assertTrue("configured implies connected", UsbSysfs.isConnected("CONFIGURED"))
        assertFalse(UsbSysfs.isConnected("DISCONNECTED"))
        assertFalse(UsbSysfs.isConnected(null))
    }

    /**
     * The common case on this firmware: the nodes are not readable by an
     * unprivileged app. "No functions listed" and "could not look" are different
     * facts and must not be conflated in a support log.
     */
    @Test fun unreadableNodesAreReportedRatherThanGuessed() {
        val state = UsbSysfs.build(
            broadcastConnected = true,
            broadcastConfigured = true,
            charging = true,
            rawFunctions = null,
            rawState = null
        )
        assertTrue(state.sysfsUnavailable)
        assertTrue(state.connected)
        assertTrue(state.configured)
        assertTrue(state.charging)
        assertFalse(state.mtp)
        assertTrue(state.summary().contains("unreadable"))
    }

    @Test fun sysfsRefinesTheBroadcastWithoutContradictingIt() {
        val state = UsbSysfs.build(
            broadcastConnected = false,
            broadcastConfigured = false,
            charging = false,
            rawFunctions = "mtp,adb\n",
            rawState = "CONFIGURED\n"
        )
        // The broadcast said nothing yet; sysfs supplies the truth.
        assertTrue(state.connected)
        assertTrue(state.configured)
        assertTrue(state.mtp)
        assertTrue(state.adb)
        assertFalse(state.massStorage)
        assertFalse(state.sysfsUnavailable)
        assertEquals("mtp,adb", state.functions)
    }

    @Test fun massStorageIsReportedIfSomethingElseEnabledIt() {
        // The app never enables this. Seeing it means another system component did.
        val state = UsbSysfs.build(true, true, true, "mass_storage", "CONFIGURED")
        assertTrue(state.massStorage)
        assertTrue(state.summary().contains("UMS"))
    }

    @Test fun disconnectedSummaryIsPlain() {
        val state = UsbSysfs.build(false, false, false, "", "DISCONNECTED")
        assertFalse(state.connected)
        assertEquals("Disconnected", state.summary())
    }
}
