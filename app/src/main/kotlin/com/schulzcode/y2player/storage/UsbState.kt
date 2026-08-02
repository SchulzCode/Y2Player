package com.schulzcode.y2player.storage

data class UsbState(
    val connected: Boolean = false,
    val configured: Boolean = false,
    val charging: Boolean = false,
    val functions: String? = null,
    val gadgetState: String? = null,
    val mtp: Boolean = false,
    val adb: Boolean = false,
    val massStorage: Boolean = false,
    val sysfsUnavailable: Boolean = true
) {
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

object UsbSysfs {
    const val FUNCTIONS_PATH = "/sys/class/android_usb/android0/functions"
    const val STATE_PATH = "/sys/class/android_usb/android0/state"

    const val MAX_NODE_BYTES = 128

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

    fun hasMassStorage(functions: Set<String>): Boolean =
        // MTK 4.4 kernels still report the older usb_mass_storage name.
        functions.any { it == "mass_storage" || it == "usb_mass_storage" }

    fun isConfigured(rawState: String?): Boolean = rawState?.trim().equals("CONFIGURED", ignoreCase = true)

    fun isConnected(rawState: String?): Boolean {
        val value = rawState?.trim()?.uppercase() ?: return false
        return value == "CONNECTED" || value == "CONFIGURED"
    }

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
