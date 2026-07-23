package com.schulzcode.y2player.storage

/**
 * Read-only view of the USB gadget, as far as an unprivileged app can see it.
 *
 * Stock firmware owns all USB-mode changes. This type only reports read-only
 * kernel state so the app can diagnose cable and storage changes; it never acts.
 */
data class UsbState(
    val connected: Boolean = false,
    val configured: Boolean = false,
    val charging: Boolean = false,
    /** Raw `functions` node contents, or null when unreadable. */
    val functions: String? = null,
    /** Raw `state` node contents (CONNECTED / DISCONNECTED / CONFIGURED), or null. */
    val gadgetState: String? = null,
    val mtp: Boolean = false,
    val adb: Boolean = false,
    /** True only if some other system component enabled it. This app never does. */
    val massStorage: Boolean = false,
    /** True when neither sysfs node could be read; the broadcast data still applies. */
    val sysfsUnavailable: Boolean = true
) {
    /** Compact form for a diagnostics row and for one structured log field. */
    fun summary(): String = buildString {
        append(if (connected) "Connected" else "Disconnected")
        if (configured) append(" · configured")
        if (charging) append(" · charging")
        val modes = ArrayList<String>(3)
        if (mtp) modes.add("MTP")
        if (adb) modes.add("ADB")
        if (massStorage) modes.add("UMS")
        if (modes.isNotEmpty()) append(" · ").append(modes.joinToString("+"))
        if (sysfsUnavailable) append(" · gadget nodes unreadable")
    }
}

/** Pure parsing, so the sysfs format can be tested without a device. */
object UsbSysfs {

    const val FUNCTIONS_PATH = "/sys/class/android_usb/android0/functions"
    const val STATE_PATH = "/sys/class/android_usb/android0/state"

    /** Longest legitimate contents are a few dozen bytes; anything larger is not ours. */
    const val MAX_NODE_BYTES = 128

    /**
     * Splits a `functions` node. The kernel writes a comma-separated list such as
     * `mtp,adb`, but vendors have shipped `mtp adb` and trailing newlines, so both
     * separators and surrounding whitespace are tolerated.
     */
    fun parseFunctions(raw: String?): Set<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptySet()
        val result = LinkedHashSet<String>(4)
        for (part in text.split(',', ' ', '\n', '\t')) {
            val name = part.trim().lowercase()
            if (name.isNotEmpty()) result.add(name)
        }
        return result
    }

    fun hasMtp(functions: Set<String>): Boolean = functions.any { it == "mtp" || it == "ptp" }

    fun hasAdb(functions: Set<String>): Boolean = functions.contains("adb")

    /**
     * `mass_storage` is the modern name; `usb_mass_storage` is the older one and
     * still appears in MTK 4.4 kernels.
     */
    fun hasMassStorage(functions: Set<String>): Boolean =
        functions.any { it == "mass_storage" || it == "usb_mass_storage" }

    /** `CONFIGURED` means the host finished enumeration; the others are self-describing. */
    fun isConfigured(rawState: String?): Boolean = rawState?.trim().equals("CONFIGURED", ignoreCase = true)

    fun isConnected(rawState: String?): Boolean {
        val value = rawState?.trim()?.uppercase() ?: return false
        return value == "CONNECTED" || value == "CONFIGURED"
    }

    /**
     * Builds the state from whatever was actually readable.
     *
     * The broadcast is authoritative for connected/configured because it always
     * arrives; sysfs only refines the picture with the function list, and both
     * nodes are frequently unreadable to an unprivileged app on this firmware.
     * That case is reported, not hidden — an empty function list and "not
     * readable" are different facts.
     */
    fun build(
        broadcastConnected: Boolean,
        broadcastConfigured: Boolean,
        charging: Boolean,
        rawFunctions: String?,
        rawState: String?
    ): UsbState {
        val functions = parseFunctions(rawFunctions)
        val unavailable = rawFunctions == null && rawState == null
        return UsbState(
            connected = broadcastConnected || isConnected(rawState),
            configured = broadcastConfigured || isConfigured(rawState),
            charging = charging,
            functions = rawFunctions?.trim()?.takeIf { it.isNotEmpty() },
            gadgetState = rawState?.trim()?.takeIf { it.isNotEmpty() },
            mtp = hasMtp(functions),
            adb = hasAdb(functions),
            massStorage = hasMassStorage(functions),
            sysfsUnavailable = unavailable
        )
    }
}
