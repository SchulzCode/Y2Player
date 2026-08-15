package com.schulzcode.y2player.diagnostics

import com.schulzcode.y2player.storage.UsbState

data class DiagnosticsState(
    val exportedPath: String? = null,
    val lastError: String? = null,
    val usb: UsbState = UsbState(),
    val historySessions: Int? = null,
    val historyBytes: Long = 0
)
