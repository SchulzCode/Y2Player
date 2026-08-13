package com.schulzcode.y2player.queue

import com.schulzcode.y2player.core.model.QueueEntry
import com.schulzcode.y2player.core.model.QueueOrigin
import com.schulzcode.y2player.core.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueControllerTest {
    private fun QueueController.ids() = snapshot().entries.map(QueueEntry::trackId)
    private fun QueueController.visibleIds() = snapshot().visibleEntries.map(QueueEntry::trackId)

    @Test fun orderedQueueAdvancesAndStops() {
        val queue = QueueController(listOf(1, 2), initialIndex = 0)
        assertEquals(2L, queue.next())
        assertNull(queue.next())
    }

    @Test fun playNextTakesPriorityOverContinuation() {
        val queue = QueueController(listOf(10, 20, 30), initialIndex = 0)
        queue.playNext(listOf(90, 91))
        assertEquals(listOf(10L, 90L, 91L, 20L, 30L), queue.visibleIds())
        assertEquals(90L, queue.next())
        assertEquals(91L, queue.next())
        assertEquals(20L, queue.next())
    }

    @Test fun latestPlayNextRequestWinsWithoutReversingItsBlock() {
        val queue = QueueController(listOf(10, 20), initialIndex = 0)
        queue.playNext(listOf(70, 71))
        queue.playNext(listOf(80, 81))
        assertEquals(listOf(10L, 80L, 81L, 70L, 71L, 20L), queue.visibleIds())
    }

    @Test fun addToUpNextIsFifoAndResumesContinuation() {
        val queue = QueueController(listOf(10, 20, 30), initialIndex = 0)
        queue.addToUpNext(listOf(70, 71))
        queue.addToUpNext(listOf(80, 81))
        assertEquals(listOf(10L, 70L, 71L, 80L, 81L, 20L, 30L), queue.visibleIds())
        repeat(4) { queue.next() }
        assertEquals(20L, queue.next())
    }

    @Test fun shuffleExposesTheExactPlaybackOrder() {
        val queue = QueueController()
        queue.replaceShuffled((1L..20L).toList(), repeatAll = false)
        val displayed = queue.snapshot().visibleEntries.map(QueueEntry::trackId)
        val played = generateSequence(queue.currentTrackId()) { queue.next() }.toList()
        assertEquals(displayed, played)
        assertEquals((1L..20L).toSet(), played.toSet())
    }

    @Test fun togglingShuffleLeavesManualEntriesOrderedAndVisibleFirst() {
        val queue = QueueController(listOf(1, 2, 3, 4, 5), initialIndex = 0)
        queue.addToUpNext(listOf(80, 81))
        queue.toggleShuffle()
        val visible = queue.snapshot().visibleEntries
        assertEquals(listOf(1L, 80L, 81L), visible.take(3).map(QueueEntry::trackId))
        assertEquals(List(2) { QueueOrigin.UP_NEXT }, visible.drop(1).take(2).map(QueueEntry::origin))
    }

    @Test fun disablingShuffleRestoresRemainingCollectionOrder() {
        val queue = QueueController(listOf(1, 2, 3, 4, 5), initialIndex = 0)
        queue.toggleShuffle()
        queue.toggleShuffle()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), queue.visibleIds())
    }

    @Test fun repeatAllDoesNotRepeatManualEntries() {
        val queue = QueueController(
            initialItems = listOf(1, 2), initialIndex = 0, initialRepeatMode = RepeatMode.ALL
        )
        queue.addToUpNext(9)
        assertEquals(9L, queue.next())
        assertEquals(2L, queue.next())
        assertEquals(1L, queue.next())
        assertEquals(listOf(1L, 2L), queue.ids())
    }

    @Test fun previousWalksHiddenHistory() {
        val queue = QueueController(listOf(1, 2, 3), initialIndex = 0)
        queue.next()
        queue.next()
        assertEquals(listOf(3L), queue.visibleIds())
        assertEquals(2L, queue.previous())
        assertEquals(listOf(2L, 3L), queue.visibleIds())
    }

    @Test fun clearUpNextKeepsContinuation() {
        val queue = QueueController(listOf(1, 2, 3), initialIndex = 0)
        queue.addToUpNext(listOf(8, 9))
        queue.clearUpNext()
        assertEquals(listOf(1L, 2L, 3L), queue.visibleIds())
    }

    @Test fun clearRemainingKeepsCurrentPlaying() {
        val queue = QueueController(listOf(1, 2, 3), initialIndex = 1)
        queue.addToUpNext(9)
        queue.clearRemaining()
        assertEquals(listOf(2L), queue.visibleIds())
        assertNull(queue.next())
    }

    @Test fun moveStaysInsideItsSection() {
        val queue = QueueController(listOf(1, 2, 3), initialIndex = 0)
        queue.addToUpNext(listOf(8, 9))
        val manual = queue.snapshot().visibleEntries[2]
        assertTrue(queue.moveEntry(manual.id, -1))
        assertEquals(listOf(1L, 9L, 8L, 2L, 3L), queue.visibleIds())
        assertTrue(queue.moveEntry(manual.id, 1))
        assertEquals(listOf(1L, 8L, 9L, 2L, 3L), queue.visibleIds())
        assertFalse(queue.moveEntry(manual.id, 1))
    }

    @Test fun duplicateTracksHaveStableDistinctEntryIds() {
        val queue = QueueController(listOf(10, 10), initialIndex = 0)
        val entries = queue.snapshot().entries
        assertNotEquals(entries[0].id, entries[1].id)
        queue.removeEntry(entries[1].id)
        assertEquals(listOf(10L), queue.ids())
        assertEquals(entries[0].id, queue.currentEntryId())
    }

    @Test fun removingTheLastCurrentEntryDoesNotRestartHistory() {
        val queue = QueueController(listOf(1, 2), initialIndex = 1)
        queue.removeEntry(queue.currentEntryId()!!)
        assertNull(queue.currentTrackId())
    }

    @Test fun sessionRoundTripPreservesOriginsAndCurrentOccurrence() {
        val queue = QueueController(listOf(10, 20, 10), initialIndex = 2)
        queue.addToUpNext(99)
        val restored = QueueController()
        restored.restore(queue.snapshot().entries, queue.session(321))
        assertEquals(queue.currentEntryId(), restored.currentEntryId())
        assertEquals(queue.snapshot().entries, restored.snapshot().entries)
        assertEquals(99L, restored.next())
    }

    @Test fun legacyShuffleOrderIsMaterializedDuringRestore() {
        val entries = listOf(10L, 20L, 30L).mapIndexed { index, id ->
            QueueEntry(index + 1L, id, QueueOrigin.CONTINUATION, index)
        }
        val queue = QueueController()
        queue.restore(entries, PersistedPlaybackSession(
            currentEntryId = null,
            legacyCurrentIndex = 2,
            positionMs = 0,
            repeatMode = RepeatMode.OFF,
            shuffleEnabled = true,
            shuffleSeed = 7,
            legacyPlayOrder = listOf(2, 0, 1)
        ))
        assertEquals(listOf(30L, 10L, 20L), queue.ids())
        assertEquals(30L, queue.currentTrackId())
    }

    @Test fun immutableEntryListIsReusedUntilContentChanges() {
        val queue = QueueController(listOf(10, 20), initialIndex = 0)
        val entries = queue.snapshot().entries
        queue.next()
        assertSame(entries, queue.snapshot().entries)
        queue.addToUpNext(30)
        assertTrue(entries !== queue.snapshot().entries)
    }

    @Test fun retainKnownPreservesTheExactCurrentDuplicate() {
        val queue = QueueController(listOf(99, 10, 20, 10), initialIndex = 3)
        val currentEntry = queue.currentEntryId()
        queue.retainKnown(setOf(10, 20))
        assertEquals(currentEntry, queue.currentEntryId())
        assertEquals(10L, queue.currentTrackId())
    }

    @Test fun currentPassIgnoresRepeatAllWrap() {
        val queue = QueueController(
            initialItems = listOf(10, 20), initialIndex = 1, initialRepeatMode = RepeatMode.ALL
        )
        assertNull(queue.peekNextInCurrentPass())
        assertNull(queue.nextInCurrentPass())
        assertEquals(20L, queue.currentTrackId())
    }
}
