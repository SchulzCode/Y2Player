package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.QueueEntry
import com.schulzcode.y2player.core.model.QueueOrigin

internal fun testQueue(vararg trackIds: Long): List<QueueEntry> = trackIds.mapIndexed { index, trackId ->
    QueueEntry(index + 1L, trackId, QueueOrigin.CONTINUATION, index)
}
