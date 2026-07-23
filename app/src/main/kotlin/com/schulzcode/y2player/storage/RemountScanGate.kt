package com.schulzcode.y2player.storage

/**
 * Coalesces equivalent framework hints emitted around a stock-UMS remount.
 * StorageMonitor already debounces bursts of each broadcast type; this gate
 * also prevents the later USB-disconnected/media-scanner hint from scheduling
 * a second full-library pass after the mount transition scheduled one.
 */
class RemountScanGate(private val coalesceWindowMs: Long = DEFAULT_WINDOW_MS) {
    private val lastMountByVolume = LinkedHashMap<String, Long>()

    @Synchronized
    fun onVolumesMounted(volumeIds: Collection<String>, nowMs: Long): Boolean {
        var accepted = false
        volumeIds.forEach { volumeId ->
            val previous = lastMountByVolume[volumeId]
            if (previous == null || !withinWindow(previous, nowMs)) accepted = true
            lastMountByVolume[volumeId] = nowMs
        }
        return accepted
    }

    @Synchronized
    fun onContentHint(nowMs: Long): Boolean =
        lastMountByVolume.values.none { mountedAt -> withinWindow(mountedAt, nowMs) }

    private fun withinWindow(previousMs: Long, nowMs: Long): Boolean =
        nowMs >= previousMs && nowMs - previousMs < coalesceWindowMs

    companion object {
        const val DEFAULT_WINDOW_MS = 15_000L
    }
}
