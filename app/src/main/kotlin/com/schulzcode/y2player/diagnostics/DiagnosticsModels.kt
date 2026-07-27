package com.schulzcode.y2player.diagnostics

import com.schulzcode.y2player.storage.UsbState

data class DiagnosticsState(
    val recentLines: List<String> = emptyList(),
    val exportedPath: String? = null,
    val lastError: String? = null,
    /**
     * Read-only USB gadget state. Present so a user can tell "the cable is in but
     * the PC sees nothing" apart from "MTP is up and the transfer is running".
     * There is deliberately no action attached to it.
     */
    val usb: UsbState = UsbState()
)
