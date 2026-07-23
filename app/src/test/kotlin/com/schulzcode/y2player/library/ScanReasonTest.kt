package com.schulzcode.y2player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hint strings come from StorageMonitor; the mapping is deliberately loose
 * so a wording change degrades to MANUAL instead of crashing or mislabeling.
 */
class ScanReasonTest {
    @Test
    fun usbHintsMapToUsbDisconnected() {
        assertEquals(ScanReason.USB_DISCONNECTED, ScanReason.fromContentHint("USB disconnected"))
        assertEquals(ScanReason.USB_DISCONNECTED, ScanReason.fromContentHint("usb cable removed"))
    }

    @Test
    fun scannerHintsMapToMtpTransfer() {
        assertEquals(ScanReason.MTP_TRANSFER, ScanReason.fromContentHint("media scanner finished"))
        assertEquals(ScanReason.MTP_TRANSFER, ScanReason.fromContentHint("Scanner done"))
    }

    @Test
    fun unknownAndNullHintsDegradeToManual() {
        assertEquals(ScanReason.MANUAL, ScanReason.fromContentHint(null))
        assertEquals(ScanReason.MANUAL, ScanReason.fromContentHint(""))
        assertEquals(ScanReason.MANUAL, ScanReason.fromContentHint("something else entirely"))
    }

    @Test
    fun everyReasonHasAStableDistinctCode() {
        val codes = ScanReason.values().map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.none { it.isBlank() })
    }
}
