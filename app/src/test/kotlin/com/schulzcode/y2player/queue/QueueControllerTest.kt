package com.schulzcode.y2player.queue

import com.schulzcode.y2player.core.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueControllerTest {
    @Test
    fun nextStopsAtEndWhenRepeatIsOff() {
        val queue = QueueController(listOf(1, 2), initialIndex = 0)

        assertEquals(2L, queue.next())
        assertNull(queue.next())
    }

    @Test
    fun repeatAllWrapsToStart() {
        val queue = QueueController(
            initialItems = listOf(1, 2),
            initialIndex = 1,
            initialRepeatMode = RepeatMode.ALL
        )

        assertEquals(1L, queue.next())
    }

    @Test
    fun shuffleKeepsCurrentTrackStable() {
        val queue = QueueController(listOf(10, 20, 30, 40), initialIndex = 2)
        val before = queue.currentTrackId()

        queue.toggleShuffle()

        assertEquals(before, queue.currentTrackId())
        assertTrue(queue.snapshot().shuffleEnabled)
    }

    @Test
    fun removeBeforeCurrentAdjustsIndexWithoutChangingTrack() {
        val queue = QueueController(listOf(10, 20, 30), initialIndex = 2)

        queue.removeAt(0)

        assertEquals(30L, queue.currentTrackId())
        assertEquals(1, queue.currentIndex())
    }

    @Test
    fun addNextPlacesTrackAfterCurrent() {
        val queue = QueueController(listOf(10, 30), initialIndex = 0)

        queue.addNext(20)

        assertEquals(listOf(10L, 20L, 30L), queue.snapshot().items)
        assertEquals(20L, queue.next())
    }

    @Test
    fun shuffleOrderContinuesAfterRestore() {
        val first = QueueController(listOf(10, 20, 30, 40), initialIndex = 0)
        first.toggleShuffle()
        first.next()
        val saved = first.session(positionMs = 123)
        val expectedNext = first.next()

        val restored = QueueController()
        restored.restore(listOf(10, 20, 30, 40), saved)

        assertEquals(expectedNext, restored.next())
    }

    @Test
    fun addNextIsNextEvenWhileShuffled() {
        val queue = QueueController(listOf(10, 20, 30), initialIndex = 1)
        queue.toggleShuffle()
        queue.addNext(99)

        assertEquals(99L, queue.next())
    }
    @Test
    fun moveItemKeepsCurrentTrackStable() {
        val queue = QueueController(listOf(10, 20, 30), initialIndex = 1)
        queue.moveItem(0, 1)
        assertEquals(listOf(20L, 10L, 30L), queue.snapshot().items)
        assertEquals(20L, queue.currentTrackId())
    }

    @Test
    fun clearUpcomingKeepsCurrentAndPastItems() {
        val queue = QueueController(listOf(10, 20, 30, 40), initialIndex = 1)
        queue.clearUpcoming()
        assertEquals(listOf(10L, 20L), queue.snapshot().items)
        assertEquals(20L, queue.currentTrackId())
    }

    @Test
    fun moveItemPreservesTheExactCurrentOccurrenceWhenIdsRepeat() {
        val queue = QueueController(listOf(10, 20, 10), initialIndex = 2)

        queue.moveItem(0, 1)

        assertEquals(listOf(20L, 10L, 10L), queue.snapshot().items)
        assertEquals(2, queue.currentIndex())
        assertEquals(10L, queue.currentTrackId())
    }

    @Test
    fun errorAdvanceEscapesRepeatOne() {
        val queue = QueueController(
            initialItems = listOf(10, 20),
            initialIndex = 0,
            initialRepeatMode = RepeatMode.ONE
        )

        assertEquals(20L, queue.nextIgnoringRepeatOne())
        assertEquals(RepeatMode.ONE, queue.snapshot().repeatMode)
    }

    @Test
    fun explicitNextAndPreviousEscapeRepeatOneWithoutChangingTheMode() {
        val queue = QueueController(
            initialItems = listOf(10, 20, 30),
            initialIndex = 1,
            initialRepeatMode = RepeatMode.ONE
        )

        assertEquals(30L, queue.nextIgnoringRepeatOne())
        assertEquals(20L, queue.previousIgnoringRepeatOne())
        assertEquals(RepeatMode.ONE, queue.snapshot().repeatMode)
    }

    @Test
    fun clearUpcomingUsesLogicalShuffleOrderAndRestoresIt() {
        val queue = QueueController()
        queue.restore(
            newItems = listOf(10, 20, 30, 40, 50),
            session = PersistedPlaybackSession(
                currentIndex = 2,
                positionMs = 0,
                repeatMode = RepeatMode.OFF,
                shuffleEnabled = true,
                shuffleSeed = 7,
                playOrder = listOf(3, 0, 2, 4, 1)
            )
        )

        queue.clearUpcoming()

        assertEquals(30L, queue.currentTrackId())
        assertNull(queue.next())

        val restored = QueueController()
        restored.restore(queue.snapshot().items, queue.session(positionMs = 321))
        assertEquals(30L, restored.currentTrackId())
        assertNull(restored.next())
    }

    @Test
    fun invalidPersistedPlayOrderFallsBackToAValidOrder() {
        val queue = QueueController()
        queue.restore(
            newItems = listOf(10, 20, 30),
            session = PersistedPlaybackSession(
                currentIndex = 1,
                positionMs = 0,
                repeatMode = RepeatMode.OFF,
                shuffleEnabled = false,
                shuffleSeed = 1,
                playOrder = listOf(0, 0, 99)
            )
        )

        assertEquals(20L, queue.currentTrackId())
        assertEquals(30L, queue.next())
    }

    @Test fun queueRepairRemovesUnknownRowsAndSelectsLogicalSuccessor() {
        val queue = QueueController(listOf(10, 20, 30, 40), initialIndex = 1)

        queue.retainKnown(setOf(10, 30, 40))

        assertEquals(listOf(10L, 30L, 40L), queue.snapshot().items)
        assertEquals(30L, queue.currentTrackId())
    }

    @Test fun queueRepairPreservesDuplicateKnownTracks() {
        val queue = QueueController(listOf(10, 20, 10), initialIndex = 2)
        queue.retainKnown(setOf(10))

        assertEquals(listOf(10L, 10L), queue.snapshot().items)
        assertEquals(1, queue.currentIndex())
    }

    @Test
    fun currentPassNavigationIgnoresRepeatOneAndDoesNotWrapRepeatAll() {
        val queue = QueueController(
            initialItems = listOf(10, 20, 30),
            initialIndex = 1,
            initialRepeatMode = RepeatMode.ONE
        )

        assertEquals(30L, queue.peekNextInCurrentPass())
        assertEquals(30L, queue.nextInCurrentPass())

        while (queue.cycleRepeat() != RepeatMode.ALL) Unit
        assertNull(queue.peekNextInCurrentPass())
        assertNull(queue.nextInCurrentPass())
        assertEquals(30L, queue.currentTrackId())
    }

    @Test
    fun currentPassNavigationUsesLogicalShuffleOrder() {
        val queue = QueueController()
        queue.restore(
            newItems = listOf(10, 20, 30, 40),
            session = PersistedPlaybackSession(
                currentIndex = 2,
                positionMs = 0,
                repeatMode = RepeatMode.ALL,
                shuffleEnabled = true,
                shuffleSeed = 7,
                playOrder = listOf(3, 2, 0, 1)
            )
        )

        assertEquals(10L, queue.peekNextInCurrentPass())
        assertEquals(10L, queue.nextInCurrentPass())
        assertEquals(20L, queue.nextInCurrentPass())
        assertNull(queue.nextInCurrentPass())
    }

    /**
     * The dedicated-player contract: Previous in shuffle walks the *actual* playback
     * history backwards (A→F→C→M, Previous yields M→C→F→A), and Next afterwards
     * continues forward through the same order without corrupting the history.
     * The seeded permutation + cursor model provides exactly this.
     */
    @Test
    fun shufflePreviousWalksTheActualHistoryAndNextContinuesForward() {
        val queue = QueueController()
        queue.restore(
            newItems = listOf(1, 2, 3, 4, 5),
            session = PersistedPlaybackSession(
                currentIndex = 3, positionMs = 0, repeatMode = RepeatMode.OFF,
                shuffleEnabled = true, shuffleSeed = 7, playOrder = listOf(3, 0, 2, 4, 1)
            )
        )
        // Playback history along the shuffle order: 4 → 1 → 3 → 5.
        val played = mutableListOf(queue.currentTrackId()!!)
        repeat(3) { played += queue.next()!! }
        assertEquals(listOf(4L, 1L, 3L, 5L), played)

        // Previous replays the real history backwards, never a new random pick.
        assertEquals(3L, queue.previous())
        assertEquals(1L, queue.previous())
        assertEquals(4L, queue.previous())

        // Next afterwards continues the same order without corrupting the history.
        assertEquals(1L, queue.next())
        assertEquals(3L, queue.next())
        assertEquals(5L, queue.next())
    }

    /** Shuffle must play every track exactly once before the cycle is exhausted. */
    @Test
    fun shufflePlaysEveryTrackOnceBeforeExhaustion() {
        val queue = QueueController()
        queue.restore(
            newItems = listOf(1, 2, 3, 4, 5, 6, 7, 8),
            session = PersistedPlaybackSession(
                currentIndex = 5, positionMs = 0, repeatMode = RepeatMode.OFF,
                shuffleEnabled = true, shuffleSeed = 7, playOrder = listOf(5, 2, 7, 0, 3, 6, 1, 4)
            )
        )
        val seen = mutableSetOf(queue.currentTrackId()!!)
        while (true) {
            val next = queue.next() ?: break
            assertTrue("track $next repeated before the shuffle cycle was exhausted", seen.add(next))
        }
        assertEquals(8, seen.size)
    }

    /** Shuffle history (not just the order) must survive a persistence round trip. */
    @Test
    fun shuffleHistorySurvivesPersistenceRoundTrip() {
        val queue = QueueController()
        queue.restore(
            newItems = listOf(1, 2, 3, 4, 5),
            session = PersistedPlaybackSession(
                currentIndex = 3, positionMs = 0, repeatMode = RepeatMode.OFF,
                shuffleEnabled = true, shuffleSeed = 7, playOrder = listOf(3, 0, 2, 4, 1)
            )
        )
        val first = queue.currentTrackId()!!
        val second = queue.next()!!
        val third = queue.next()!!

        val restored = QueueController()
        restored.restore(queue.snapshot().items, queue.session(positionMs = 0))

        assertEquals(third, restored.currentTrackId())
        assertEquals(second, restored.previous())
        assertEquals(first, restored.previous())
    }

    /** Repeat All + shuffle wraps into the same pass order, keeping history navigable. */
    @Test
    fun repeatAllShuffleWrapKeepsHistoryConsistent() {
        val queue = QueueController()
        queue.restore(
            newItems = listOf(1, 2, 3),
            session = PersistedPlaybackSession(
                currentIndex = 2, positionMs = 0, repeatMode = RepeatMode.ALL,
                shuffleEnabled = true, shuffleSeed = 7, playOrder = listOf(2, 0, 1)
            )
        )
        val pass = mutableListOf(queue.currentTrackId()!!)
        repeat(2) { pass += queue.next()!! }
        assertEquals(listOf(3L, 1L, 2L), pass)

        assertEquals(pass[0], queue.next())      // wrap to the start of the same pass
        assertEquals(pass[2], queue.previous())  // previous crosses the wrap backwards
    }

    @Test
    fun snapshotsReuseImmutableQueueItemsUntilQueueContentChanges() {
        val queue = QueueController(listOf(10, 20), initialIndex = 0)
        val first = queue.snapshot().items
        assertSame(first, queue.snapshot().items)
        queue.moveToQueueIndex(1)
        assertSame(first, queue.snapshot().items)
        queue.append(30)
        org.junit.Assert.assertNotSame(first, queue.snapshot().items)
    }

    @Test
    fun sessionsReusePlayOrderUntilItsStructureChanges() {
        val queue = QueueController(listOf(10, 20, 30), initialIndex = 0)
        assertNull(queue.session(positionMs = 0).playOrder)
        queue.toggleShuffle()
        val first = queue.session(positionMs = 0).playOrder

        queue.next()
        assertSame(first, queue.session(positionMs = 1_000).playOrder)

        queue.addNext(40)
        org.junit.Assert.assertNotSame(first, queue.session(positionMs = 1_000).playOrder)
    }

    @Test
    fun retainingKnownTracksPreservesTheExactDuplicateOccurrence() {
        val queue = QueueController(listOf(10, 20, 10), initialIndex = 2)

        queue.retainKnown(setOf(10, 20))

        assertEquals(2, queue.currentIndex())
        assertEquals(10L, queue.currentTrackId())
    }

    @Test
    fun retainingKnownTracksMapsTheExactDuplicateAfterEarlierRemoval() {
        val queue = QueueController(listOf(99, 10, 20, 10), initialIndex = 3)

        queue.retainKnown(setOf(10, 20))

        assertEquals(listOf(10L, 20L, 10L), queue.snapshot().items)
        assertEquals(2, queue.currentIndex())
        assertEquals(10L, queue.currentTrackId())
    }

    @Test
    fun peekNextDoesNotAdvanceAndRespectsRepeatModes() {
        val queue = QueueController(listOf(10, 20), initialIndex = 0)
        assertEquals(20L, queue.peekNext())
        assertEquals(10L, queue.currentTrackId()) // peek must not move the cursor

        // End of queue, repeat off: nothing next.
        queue.moveToQueueIndex(1)
        assertNull(queue.peekNext())
    }

    @Test
    fun peekNextRepeatOneReturnsCurrentAndRepeatAllWraps() {
        val repeatOne = QueueController(listOf(10, 20), initialIndex = 0, initialRepeatMode = RepeatMode.ONE)
        assertEquals(10L, repeatOne.peekNext())

        val repeatAll = QueueController(listOf(10, 20), initialIndex = 1, initialRepeatMode = RepeatMode.ALL)
        assertEquals(10L, repeatAll.peekNext())
    }

}
