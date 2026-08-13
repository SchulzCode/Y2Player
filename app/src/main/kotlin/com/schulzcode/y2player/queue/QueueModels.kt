package com.schulzcode.y2player.queue

import com.schulzcode.y2player.core.model.QueueEntry
import com.schulzcode.y2player.core.model.RepeatMode

data class QueueSnapshot(
    val entries: List<QueueEntry>,
    val visibleEntries: List<QueueEntry>,
    val currentEntryId: Long?,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val shuffleSeed: Long
)

data class PersistedPlaybackSession(
    val currentEntryId: Long?,
    val positionMs: Long,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val shuffleSeed: Long,
    /** Only populated while importing state written by the pre-v15 queue. */
    val legacyCurrentIndex: Int? = null,
    /** Only populated while importing state written by the pre-v15 hidden shuffle order. */
    val legacyPlayOrder: List<Int>? = null
)
