package com.schulzcode.y2player.storage

/*
 * Storage-driven scan policy.
 *
 * These two units answer one question between them — "given what Android just
 * reported about the volumes, should the library be rescanned, and for which
 * volume?" — and are consumed together by the storage coordinator in
 * Y2Application. They were previously two files that had to be read in sequence
 * to follow a single decision.
 */

/** Pure classification of Android storage snapshots for process-level policy. */
object StorageTransitionPolicy {
    data class Changes(
        val becameUnavailable: Set<String>,
        val becameAvailable: Set<String>,
        val missingAtStartup: Set<String>
    )

    fun classify(
        previous: Map<String, Boolean>,
        current: Map<String, Boolean>,
        firstSnapshot: Boolean
    ): Changes {
        val unavailable = linkedSetOf<String>()
        val available = linkedSetOf<String>()
        val startupMissing = linkedSetOf<String>()
        current.forEach { (id, isAvailable) ->
            when {
                isAvailable && previous[id] == false -> available += id
                !isAvailable && previous[id] == true -> unavailable += id
                !isAvailable && firstSnapshot -> startupMissing += id
            }
        }
        return Changes(unavailable, available, startupMissing)
    }
}

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
