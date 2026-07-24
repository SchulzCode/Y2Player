package com.schulzcode.y2player.bluetooth

/** Pure policy for discovery while the single Bluetooth radio is carrying A2DP. */
object BluetoothScanPolicy {
    const val BLOCKED_MESSAGE = "Disconnect Bluetooth audio before scanning"

    fun hasActiveA2dp(anyConnected: Boolean, anyPlaying: Boolean): Boolean =
        anyConnected || anyPlaying

    fun shouldCancelDiscovery(isDiscovering: Boolean): Boolean = isDiscovering
}

/** Pure policy for releasing the UI-owned receiver and A2DP profile proxy. */
object BluetoothLifecyclePolicy {
    fun stopImmediately(hasPendingOperation: Boolean): Boolean = !hasPendingOperation

    fun stopAfterIdle(uiActive: Boolean, stopRequested: Boolean, hasPendingOperation: Boolean): Boolean =
        stopRequested && !uiActive && !hasPendingOperation
}
