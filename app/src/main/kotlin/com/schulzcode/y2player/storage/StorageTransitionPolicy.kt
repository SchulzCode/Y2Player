package com.schulzcode.y2player.storage

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
