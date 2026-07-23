package com.schulzcode.y2player.diagnostics

import com.schulzcode.y2player.storage.UsbState

data class FormatProbeResult(
    val extension: String,
    val success: Boolean,
    val message: String,
    val testedAt: Long = System.currentTimeMillis()
)

data class DiagnosticsState(
    val recentLines: List<String> = emptyList(),
    val formatProbeRunning: Boolean = false,
    val formatProbeResults: List<FormatProbeResult> = emptyList(),
    val exportedPath: String? = null,
    val lastError: String? = null,
    /**
     * Read-only USB gadget state. Present so a user can tell "the cable is in but
     * the PC sees nothing" apart from "MTP is up and the transfer is running".
     * There is deliberately no action attached to it.
     */
    val usb: UsbState = UsbState()
)
