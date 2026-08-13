package com.schulzcode.y2player.queue

import com.schulzcode.y2player.core.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueOrderedPlaybackTest {
    private fun controller(
        shuffle: Boolean = false,
        repeat: RepeatMode = RepeatMode.OFF
    ) = QueueController(
        initialItems = listOf(10L, 11L, 12L, 13L, 14L),
        initialIndex = 2,
        initialRepeatMode = repeat,
        initialShuffleEnabled = shuffle,
        initialShuffleSeed = 42L
    )

    @Test
    fun `clears shuffle and repeat together`() {
        val queue = controller(shuffle = true, repeat = RepeatMode.ALL)
        assertTrue(queue.applyOrderedPlayback())
        val snapshot = queue.snapshot()
        assertFalse(snapshot.shuffleEnabled)
        assertEquals(RepeatMode.OFF, snapshot.repeatMode)
    }

    @Test
    fun `reports no change when already ordered`() {
        val queue = controller(shuffle = false, repeat = RepeatMode.OFF)
        assertFalse(queue.applyOrderedPlayback())
    }

    @Test
    fun `reports change when only repeat was set`() {
        val queue = controller(shuffle = false, repeat = RepeatMode.ONE)
        assertTrue(queue.applyOrderedPlayback())
        assertEquals(RepeatMode.OFF, queue.snapshot().repeatMode)
    }

    @Test
    fun `keeps the current track when it straightens the order`() {
        val queue = controller(shuffle = true, repeat = RepeatMode.ALL)
        val before = queue.currentTrackId()
        queue.applyOrderedPlayback()
        assertEquals(before, queue.currentTrackId())
    }

    @Test
    fun `advances in queue order afterwards`() {
        val queue = controller(shuffle = true, repeat = RepeatMode.ALL)
        queue.applyOrderedPlayback()
        assertEquals(13L, queue.next())
        assertEquals(14L, queue.next())
    }

    @Test
    fun `stops at the end instead of looping the book`() {
        val queue = controller(shuffle = true, repeat = RepeatMode.ALL)
        queue.applyOrderedPlayback()
        queue.next()
        queue.next()
        assertEquals(null, queue.next())
    }

    @Test
    fun `shuffle can still be turned back on afterwards`() {
        val queue = controller(shuffle = true, repeat = RepeatMode.ALL)
        queue.applyOrderedPlayback()
        assertTrue(queue.toggleShuffle())
        assertTrue(queue.snapshot().shuffleEnabled)
    }

    @Test
    fun `every item survives straightening exactly once`() {
        val queue = controller(shuffle = true, repeat = RepeatMode.ALL)
        queue.applyOrderedPlayback()
        val items = queue.snapshot().entries.map { it.trackId }
        assertEquals(listOf(10L, 11L, 12L, 13L, 14L), items)
        assertEquals(items.size, items.toSet().size)
    }

    @Test
    fun `starting a collection in order clears a leftover shuffle`() {
        val queue = controller(shuffle = true)
        queue.replace(listOf(1L, 2L, 3L), startIndex = 0)
        assertFalse(queue.snapshot().shuffleEnabled)
    }

    @Test
    fun `starting a collection in order plays it in order`() {
        val queue = controller(shuffle = true)
        queue.replace(listOf(1L, 2L, 3L), startIndex = 0)
        assertEquals(2L, queue.next())
        assertEquals(3L, queue.next())
    }

    @Test
    fun `starting a collection in order honours the chosen track`() {
        val queue = controller(shuffle = true)
        queue.replace(listOf(1L, 2L, 3L), startIndex = 1)
        assertEquals(2L, queue.currentTrackId())
    }

    @Test
    fun `shuffled collection does not force repeat all`() {
        val queue = controller()
        queue.replaceShuffled(listOf(1L, 2L, 3L), repeatAll = false)
        assertTrue(queue.snapshot().shuffleEnabled)
        assertEquals(RepeatMode.OFF, queue.snapshot().repeatMode)
    }

    @Test
    fun `shuffle all still forces repeat all`() {
        val queue = controller()
        queue.replaceShuffled(listOf(1L, 2L, 3L))
        assertEquals(RepeatMode.ALL, queue.snapshot().repeatMode)
    }

    @Test
    fun `shuffled collection contains every item exactly once`() {
        val queue = controller()
        val ids = (1L..25L).toList()
        queue.replaceShuffled(ids, repeatAll = false)

        val played = generateSequence(queue.currentTrackId()) { queue.next() }.toList()
        assertEquals(ids.size, played.size)
        assertEquals(ids.toSet(), played.toSet())
    }

    @Test
    fun `shuffled collection stops at the end of its single pass`() {
        val queue = controller()
        queue.replaceShuffled(listOf(1L, 2L), repeatAll = false)
        queue.next()
        assertEquals(null, queue.next())
    }
}
