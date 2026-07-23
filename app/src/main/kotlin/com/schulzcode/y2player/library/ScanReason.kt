package com.schulzcode.y2player.library

/**
 * Why a library scan was requested.
 *
 * Recorded with every scan so a support log can answer "why did it rescan?"
 * without inference. It also drives coalescing: a queued storage-driven reason
 * outranks a manual one, because the storage event is the one carrying new
 * information.
 */
enum class ScanReason(val code: String) {
    /** User asked, or the app had no better attribution. */
    MANUAL("manual"),
    /** First scan of the process. */
    STARTUP("startup"),
    /** A volume became available again. */
    VOLUME_MOUNTED("volume_mounted"),
    /** Media scanner finished — usually the tail of an MTP transfer. */
    MTP_TRANSFER("mtp_transfer"),
    /** USB cable removed, i.e. a transfer session ended. */
    USB_DISCONNECTED("usb_disconnected"),
    /** Leaving Safe Mode, where scanning was suppressed. */
    SAFE_MODE_EXIT("safe_mode_exit");

    companion object {
        /**
         * Maps the storage monitor's human-readable hint onto a reason. The hint
         * strings come from one place and are matched loosely on purpose: a
         * wording change should degrade to MANUAL, not crash or mislabel.
         */
        fun fromContentHint(hint: String?): ScanReason = when {
            hint == null -> MANUAL
            hint.contains("USB", ignoreCase = true) -> USB_DISCONNECTED
            hint.contains("scanner", ignoreCase = true) -> MTP_TRANSFER
            else -> MANUAL
        }
    }
}
