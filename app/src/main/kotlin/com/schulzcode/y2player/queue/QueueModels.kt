package com.schulzcode.y2player.queue

import com.schulzcode.y2player.core.model.RepeatMode

data class QueueSnapshot(
    val items: List<Long>,
    val currentIndex: Int?,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val shuffleSeed: Long
)

data class PersistedPlaybackSession(
    val currentIndex: Int?,
    val positionMs: Long,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val shuffleSeed: Long,
    val playOrder: List<Int>? = null
)
