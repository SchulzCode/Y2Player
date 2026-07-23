package com.schulzcode.y2player.queue

import com.schulzcode.y2player.core.model.RepeatMode
import java.util.Collections
import java.util.Random

class QueueController(
    initialItems: List<Long> = emptyList(),
    initialIndex: Int? = null,
    initialRepeatMode: RepeatMode = RepeatMode.OFF,
    initialShuffleEnabled: Boolean = false,
    initialShuffleSeed: Long = System.nanoTime()
) {
    private val items = initialItems.take(MAX_QUEUE_ITEMS).toMutableList()
    private var itemsRevision = 0L
    private var cachedItemsRevision = -1L
    private var cachedItems: List<Long> = emptyList()
    private var currentIndex: Int? = initialIndex?.takeIf { it in items.indices }
    private var repeatMode: RepeatMode = initialRepeatMode
    private var shuffleEnabled: Boolean = initialShuffleEnabled
    private var shuffleSeed: Long = initialShuffleSeed
    private var playOrder: MutableList<Int> = buildPlayOrder()
    private var cursor: Int? = currentIndex?.let(playOrder::indexOf)?.takeIf { it >= 0 }
    private var playOrderRevision = 0L
    private var cachedPlayOrderRevision = -1L
    private var cachedPlayOrder: List<Int> = emptyList()

    @Synchronized
    fun replace(newItems: List<Long>, startIndex: Int = 0) {
        items.clear()
        items.addAll(newItems.take(MAX_QUEUE_ITEMS))
        touchItems()
        currentIndex = startIndex.takeIf { it in items.indices }
        shuffleSeed = System.nanoTime()
        rebuildOrderKeepingCurrent()
    }

    @Synchronized
    fun restore(newItems: List<Long>, session: PersistedPlaybackSession?) {
        items.clear()
        items.addAll(newItems.take(MAX_QUEUE_ITEMS))
        touchItems()
        currentIndex = session?.currentIndex?.takeIf { it in items.indices }
        repeatMode = session?.repeatMode ?: RepeatMode.OFF
        shuffleEnabled = session?.shuffleEnabled ?: false
        shuffleSeed = session?.shuffleSeed ?: System.nanoTime()
        val restoredOrder = session?.playOrder?.takeIf(::isValidPlayOrder)
        playOrder = restoredOrder?.toMutableList() ?: buildPlayOrder()
        touchPlayOrder()
        cursor = currentIndex?.let(playOrder::indexOf)?.takeIf { it >= 0 }
    }

    @Synchronized
    fun currentTrackId(): Long? = currentIndex?.let(items::getOrNull)

    @Synchronized
    fun currentIndex(): Int? = currentIndex

    @Synchronized
    fun moveToQueueIndex(index: Int): Long? {
        if (index !in items.indices) return null
        currentIndex = index
        cursor = playOrder.indexOf(index).takeIf { it >= 0 }
        return items[index]
    }

    @Synchronized
    fun peekNext(): Long? {
        val currentCursor = cursor ?: return playOrder.firstOrNull()?.let(items::getOrNull)
        if (repeatMode == RepeatMode.ONE) return currentTrackId()
        val nextCursor = currentCursor + 1
        if (nextCursor < playOrder.size) return items.getOrNull(playOrder[nextCursor])
        if (repeatMode == RepeatMode.ALL && playOrder.isNotEmpty()) return items.getOrNull(playOrder[0])
        return null
    }

    /**
     * Returns the next logical queue entry without applying Repeat ONE or
     * wrapping Repeat ALL. Sleep-timer boundaries use this to finish the
     * current pass through the queue predictably.
     */
    @Synchronized
    fun peekNextInCurrentPass(): Long? {
        val currentCursor = cursor ?: return playOrder.firstOrNull()?.let(items::getOrNull)
        val nextCursor = currentCursor + 1
        return if (nextCursor < playOrder.size) items.getOrNull(playOrder[nextCursor]) else null
    }

    /** Advances once in the current logical play order, without repeat rules. */
    @Synchronized
    fun nextInCurrentPass(): Long? {
        val currentCursor = cursor ?: return first()
        val nextCursor = currentCursor + 1
        if (nextCursor >= playOrder.size) return null
        cursor = nextCursor
        currentIndex = playOrder[nextCursor]
        return currentTrackId()
    }

    @Synchronized
    fun next(): Long? {
        val currentCursor = cursor ?: return first()
        if (repeatMode == RepeatMode.ONE) return currentTrackId()
        val nextCursor = currentCursor + 1
        if (nextCursor < playOrder.size) {
            cursor = nextCursor
            currentIndex = playOrder[nextCursor]
            return currentTrackId()
        }
        if (repeatMode == RepeatMode.ALL && playOrder.isNotEmpty()) {
            cursor = 0
            currentIndex = playOrder[0]
            return currentTrackId()
        }
        return null
    }

    @Synchronized
    fun previous(): Long? {
        val currentCursor = cursor ?: return first()
        if (repeatMode == RepeatMode.ONE) return currentTrackId()
        val previousCursor = currentCursor - 1
        if (previousCursor >= 0) {
            cursor = previousCursor
            currentIndex = playOrder[previousCursor]
            return currentTrackId()
        }
        if (repeatMode == RepeatMode.ALL && playOrder.isNotEmpty()) {
            cursor = playOrder.lastIndex
            currentIndex = playOrder.last()
            return currentTrackId()
        }
        return currentTrackId()
    }

    @Synchronized
    fun addNext(trackId: Long) {
        if (items.size >= MAX_QUEUE_ITEMS) return
        val insertAt = ((currentIndex ?: -1) + 1).coerceAtMost(items.size)
        items.add(insertAt, trackId)
        touchItems()

        // Logical indices at and after the insertion point shift by one.
        for (index in playOrder.indices) {
            if (playOrder[index] >= insertAt) playOrder[index] = playOrder[index] + 1
        }
        currentIndex = currentIndex?.let { if (it >= insertAt) it + 1 else it }

        val insertionCursor = ((cursor ?: -1) + 1).coerceIn(0, playOrder.size)
        playOrder.add(insertionCursor, insertAt)
        touchPlayOrder()
        cursor = currentIndex?.let(playOrder::indexOf)?.takeIf { it >= 0 }
    }

    @Synchronized
    fun append(trackId: Long) {
        if (items.size >= MAX_QUEUE_ITEMS) return
        val index = items.size
        items += trackId
        touchItems()
        playOrder += index
        touchPlayOrder()
        cursor = currentIndex?.let(playOrder::indexOf)?.takeIf { it >= 0 }
    }

    @Synchronized
    fun removeAt(index: Int): Long? {
        if (index !in items.indices) return null
        val wasCurrent = index == currentIndex
        val removedOrderPosition = playOrder.indexOf(index)
        val removed = items.removeAt(index)
        touchItems()

        if (removedOrderPosition >= 0) playOrder.removeAt(removedOrderPosition)
        for (position in playOrder.indices) {
            if (playOrder[position] > index) playOrder[position] = playOrder[position] - 1
        }
        touchPlayOrder()

        if (items.isEmpty()) {
            currentIndex = null
            cursor = null
            return removed
        }

        currentIndex = if (wasCurrent) {
            val nextOrderPosition = removedOrderPosition.coerceIn(0, playOrder.lastIndex)
            playOrder[nextOrderPosition]
        } else {
            currentIndex?.let { if (index < it) it - 1 else it }
        }
        cursor = currentIndex?.let(playOrder::indexOf)?.takeIf { it >= 0 }
        return removed
    }


    @Synchronized
    fun moveItem(index: Int, delta: Int): Boolean {
        val target = index + delta
        if (index !in items.indices || target !in items.indices || index == target) return false
        val previousCurrent = currentIndex
        val value = items.removeAt(index)
        items.add(target, value)
        touchItems()
        currentIndex = when {
            previousCurrent == null -> null
            previousCurrent == index -> target
            index < previousCurrent && target >= previousCurrent -> previousCurrent - 1
            index > previousCurrent && target <= previousCurrent -> previousCurrent + 1
            else -> previousCurrent
        }
        rebuildOrderKeepingCurrent()
        return true
    }

    /**
     * Explicit skips and error advances must move on even when automatic
     * completion repeats one track.
     */
    @Synchronized
    fun nextIgnoringRepeatOne(): Long? {
        if (repeatMode != RepeatMode.ONE) return next()
        val original = repeatMode
        repeatMode = RepeatMode.OFF
        return try { next() } finally { repeatMode = original }
    }

    /** Explicit previous commands must navigate even when Repeat ONE is active. */
    @Synchronized
    fun previousIgnoringRepeatOne(): Long? {
        if (repeatMode != RepeatMode.ONE) return previous()
        val original = repeatMode
        repeatMode = RepeatMode.OFF
        return try { previous() } finally { repeatMode = original }
    }

    @Synchronized
    fun clearUpcoming() {
        val oldCurrent = currentIndex
        val currentCursor = cursor
        if (oldCurrent == null || currentCursor == null) {
            clear()
            return
        }

        // Keep exactly the logical history and current entry. This matters in shuffle mode:
        // physical queue order and playback order are intentionally different.
        val keptOrder = playOrder.take(currentCursor + 1)
        val keptIndices = keptOrder.toHashSet()
        val oldToNew = HashMap<Int, Int>(keptIndices.size)
        val keptItems = ArrayList<Long>(keptIndices.size)
        items.forEachIndexed { oldIndex, trackId ->
            if (oldIndex in keptIndices) {
                oldToNew[oldIndex] = keptItems.size
                keptItems += trackId
            }
        }

        items.clear()
        items.addAll(keptItems)
        touchItems()
        currentIndex = oldToNew[oldCurrent]
        playOrder = keptOrder.mapNotNull(oldToNew::get).toMutableList()
        touchPlayOrder()
        cursor = currentIndex?.let(playOrder::indexOf)?.takeIf { it >= 0 }
    }

    @Synchronized
    fun clear() {
        items.clear()
        touchItems()
        currentIndex = null
        playOrder.clear()
        touchPlayOrder()
        cursor = null
    }

    @Synchronized
    fun retainKnown(trackIds: Set<Long>) {
        if (items.all { it in trackIds }) return

        val oldCurrentIndex = currentIndex
        val oldCursor = cursor
        val oldPlayOrder = playOrder.toList()
        val oldToNew = HashMap<Int, Int>(items.size)
        val keptItems = ArrayList<Long>(items.size)
        items.forEachIndexed { oldIndex, trackId ->
            if (trackId in trackIds) {
                oldToNew[oldIndex] = keptItems.size
                keptItems += trackId
            }
        }

        items.clear()
        items.addAll(keptItems)
        touchItems()
        currentIndex = oldCurrentIndex?.let(oldToNew::get) ?: run {
            val logicalCandidates = if (oldCursor == null) oldPlayOrder else
                oldPlayOrder.drop(oldCursor + 1) + oldPlayOrder.take(oldCursor)
            logicalCandidates.firstNotNullOfOrNull(oldToNew::get)
        }
        playOrder = playOrder.mapNotNull(oldToNew::get).toMutableList()
        touchPlayOrder()
        cursor = currentIndex?.let(playOrder::indexOf)?.takeIf { it >= 0 }
    }

    @Synchronized
    fun toggleShuffle(): Boolean {
        shuffleEnabled = !shuffleEnabled
        if (shuffleEnabled) shuffleSeed = System.nanoTime()
        rebuildOrderKeepingCurrent()
        return shuffleEnabled
    }

    @Synchronized
    fun cycleRepeat(): RepeatMode {
        repeatMode = repeatMode.next()
        return repeatMode
    }

    @Synchronized
    fun snapshot(): QueueSnapshot = QueueSnapshot(
        items = immutableItems(),
        currentIndex = currentIndex,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        shuffleSeed = shuffleSeed
    )

    @Synchronized
    fun session(positionMs: Long): PersistedPlaybackSession = PersistedPlaybackSession(
        currentIndex = currentIndex,
        positionMs = positionMs,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        shuffleSeed = shuffleSeed,
        playOrder = if (shuffleEnabled) immutablePlayOrder() else null
    )

    private fun touchItems() {
        itemsRevision += 1
    }

    private fun touchPlayOrder() {
        playOrderRevision += 1
    }

    private fun immutableItems(): List<Long> {
        if (cachedItemsRevision != itemsRevision) {
            cachedItems = items.toList()
            cachedItemsRevision = itemsRevision
        }
        return cachedItems
    }

    private fun immutablePlayOrder(): List<Int> {
        if (cachedPlayOrderRevision != playOrderRevision) {
            cachedPlayOrder = playOrder.toList()
            cachedPlayOrderRevision = playOrderRevision
        }
        return cachedPlayOrder
    }

    private fun first(): Long? {
        if (playOrder.isEmpty()) return null
        cursor = 0
        currentIndex = playOrder[0]
        return currentTrackId()
    }

    private fun isValidPlayOrder(order: List<Int>): Boolean =
        order.size == items.size && order.toSet().size == items.size && order.all { it in items.indices }

    private fun rebuildOrderKeepingCurrent() {
        playOrder = buildPlayOrder()
        touchPlayOrder()
        cursor = currentIndex?.let(playOrder::indexOf)?.takeIf { it >= 0 }
    }

    private fun buildPlayOrder(): MutableList<Int> {
        val order = items.indices.toMutableList()
        if (shuffleEnabled && order.size > 1) {
            Collections.shuffle(order, Random(shuffleSeed))
        }
        return order
    }

    companion object {
        const val MAX_QUEUE_ITEMS = 50_000
    }
}
