package com.schulzcode.y2player.playback

import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.queue.QueueController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueContinuationTest {
    @Test
    fun `natural completion advances an ordered queue`() {
        val queue = QueueController(listOf(11L, 12L, 13L), initialIndex = 0)

        assertEquals(12L, queue.next())
        assertEquals(12L, queue.currentTrackId())
    }

    @Test
    fun `repeat one repeats the current track`() {
        val queue = QueueController(
            listOf(11L, 12L),
            initialIndex = 0,
            initialRepeatMode = RepeatMode.ONE
        )

        assertEquals(11L, queue.next())
        assertEquals(11L, queue.currentTrackId())
    }

    @Test
    fun `repeat all continues at the queue boundary`() {
        val queue = QueueController(
            listOf(11L, 12L),
            initialIndex = 1,
            initialRepeatMode = RepeatMode.ALL
        )

        assertEquals(11L, queue.next())
    }

    @Test
    fun `no successor completes the queue normally`() {
        val queue = QueueController(listOf(11L, 12L), initialIndex = 1)

        assertNull(queue.next())
        assertEquals(12L, queue.currentTrackId())
    }

    @Test
    fun `shuffle progression visits every queued track`() {
        val ids = (1L..16L).toList()
        val queue = QueueController(ids, initialIndex = 0)
        queue.toggleShuffle()

        val pass = generateSequence(queue.currentTrackId()) { queue.next() }.toList()

        assertEquals(ids.size, pass.size)
        assertEquals(ids.toSet(), pass.toSet())
        assertTrue(queue.snapshot().shuffleEnabled)
    }
}
