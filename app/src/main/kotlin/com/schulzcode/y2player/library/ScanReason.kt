package com.schulzcode.y2player.library

enum class ScanReason(val code: String) {
    MANUAL("manual"),
    STARTUP("startup"),
    VOLUME_MOUNTED("volume_mounted"),
    MTP_TRANSFER("mtp_transfer"),
    USB_DISCONNECTED("usb_disconnected"),
    SAFE_MODE_EXIT("safe_mode_exit");

    companion object {
        fun fromContentHint(hint: String?): ScanReason = when {
            hint == null -> MANUAL
            hint.contains("USB", ignoreCase = true) -> USB_DISCONNECTED
            hint.contains("scanner", ignoreCase = true) -> MTP_TRANSFER
            else -> MANUAL
        }
    }
}
