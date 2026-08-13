package com.schulzcode.y2player.queue

import com.schulzcode.y2player.core.model.QueueEntry
import com.schulzcode.y2player.core.model.QueueOrigin
import com.schulzcode.y2player.core.model.RepeatMode
import java.util.Collections
import java.util.Random

/**
 * Owns the single, materialized playback order.
 *
 * Entries through [currentIndex] are playback history. Future UP_NEXT entries always sit before
 * future CONTINUATION entries. Shuffle only rearranges unplayed continuation entries, which means
 * the order exposed to the UI is exactly the order that playback will follow.
 */
class QueueController(
    initialItems: List<Long> = emptyList(),
    initialIndex: Int? = null,
    initialRepeatMode: RepeatMode = RepeatMode.OFF,
    initialShuffleEnabled: Boolean = false,
    initialShuffleSeed: Long = System.nanoTime()
) {
    private val entries = initialItems.take(MAX_QUEUE_ITEMS).mapIndexedTo(ArrayList()) { index, trackId ->
        QueueEntry(index + 1L, trackId, QueueOrigin.CONTINUATION, index)
    }
    private var currentIndex: Int? = initialIndex?.takeIf { it in entries.indices }
    private var repeatMode = initialRepeatMode
    private var shuffleEnabled = initialShuffleEnabled
    private var shuffleSeed = initialShuffleSeed
    private var nextEntryId = (entries.maxOfOrNull(QueueEntry::id) ?: 0L) + 1L

    private var entriesRevision = 0L
    private var cachedEntriesRevision = -1L
    private var cachedEntries: List<QueueEntry> = emptyList()
    private var visibleRevision = 0L
    private var cachedVisibleRevision = -1L
    private var cachedVisibleEntries: List<QueueEntry> = emptyList()

    init {
        if (shuffleEnabled) reorderFutureContinuation(shuffled = true)
    }

    @Synchronized
    fun replace(trackIds: List<Long>, startIndex: Int = 0) {
        entries.clear()
        entries += continuationEntries(trackIds)
        currentIndex = startIndex.takeIf { it in entries.indices }
        shuffleEnabled = false
        shuffleSeed = System.nanoTime()
        touchEntries()
    }

    @Synchronized
    fun replaceShuffled(trackIds: List<Long>, repeatAll: Boolean = true) {
        entries.clear()
        entries += continuationEntries(trackIds)
        if (repeatAll) repeatMode = RepeatMode.ALL
        shuffleEnabled = true
        shuffleSeed = System.nanoTime()
        shuffleEntries(entries, shuffleSeed)
        currentIndex = entries.indices.firstOrNull()
        touchEntries()
    }

    @Synchronized
    fun restore(restoredEntries: List<QueueEntry>, session: PersistedPlaybackSession?) {
        val normalized = normalizeEntries(restoredEntries.take(MAX_QUEUE_ITEMS))
        val legacyOrder = session?.legacyPlayOrder?.takeIf { order ->
            order.size == normalized.size && order.toSet() == normalized.indices.toSet()
        }
        val legacyCurrentEntryId = session?.legacyCurrentIndex
            ?.takeIf { it in normalized.indices }
            ?.let { normalized[it].id }

        entries.clear()
        if (legacyOrder == null) entries += normalized else legacyOrder.forEach { entries += normalized[it] }
        currentIndex = when {
            session?.currentEntryId != null -> entries.indexOfFirst { it.id == session.currentEntryId }.takeIf { it >= 0 }
            legacyCurrentEntryId != null -> entries.indexOfFirst { it.id == legacyCurrentEntryId }.takeIf { it >= 0 }
            else -> null
        }
        repeatMode = session?.repeatMode ?: RepeatMode.OFF
        shuffleEnabled = session?.shuffleEnabled ?: false
        shuffleSeed = session?.shuffleSeed ?: System.nanoTime()
        nextEntryId = (entries.maxOfOrNull(QueueEntry::id) ?: 0L) + 1L
        touchEntries()
    }

    @Synchronized
    fun currentTrackId(): Long? = currentIndex?.let(entries::getOrNull)?.trackId

    @Synchronized
    fun currentEntryId(): Long? = currentIndex?.let(entries::getOrNull)?.id

    @Synchronized
    fun moveToEntry(entryId: Long): Long? {
        val index = entries.indexOfFirst { it.id == entryId }
        if (index < 0) return null
        currentIndex = index
        touchVisible()
        return entries[index].trackId
    }

    @Synchronized
    fun peekNext(): Long? {
        if (repeatMode == RepeatMode.ONE) return currentTrackId()
        val next = nextInList()
        if (next != null) return next.trackId
        if (repeatMode != RepeatMode.ALL) return null
        return nextContinuationPass(mutate = false).firstOrNull()?.trackId
    }

    @Synchronized
    fun peekNextInCurrentPass(): Long? = nextInList()?.trackId

    @Synchronized
    fun nextInCurrentPass(): Long? {
        val index = currentIndex
        if (index == null) return first()
        if (index + 1 !in entries.indices) return null
        currentIndex = index + 1
        touchVisible()
        return currentTrackId()
    }

    @Synchronized
    fun next(): Long? {
        if (repeatMode == RepeatMode.ONE) return currentTrackId()
        val index = currentIndex
        if (index == null) return first()
        if (index + 1 in entries.indices) {
            currentIndex = index + 1
            touchVisible()
            return currentTrackId()
        }
        if (repeatMode != RepeatMode.ALL) return null
        val nextPass = nextContinuationPass(mutate = true)
        if (nextPass.isEmpty()) return null
        entries.clear()
        entries += nextPass
        currentIndex = 0
        touchEntries()
        return currentTrackId()
    }

    @Synchronized
    fun previous(): Long? {
        val index = currentIndex ?: return first()
        if (repeatMode == RepeatMode.ONE) return currentTrackId()
        if (index > 0) {
            currentIndex = index - 1
            touchVisible()
            return currentTrackId()
        }
        if (repeatMode == RepeatMode.ALL) {
            val lastContinuation = entries.indexOfLast { it.origin == QueueOrigin.CONTINUATION }
            if (lastContinuation >= 0) {
                currentIndex = lastContinuation
                touchVisible()
            }
        }
        return currentTrackId()
    }

    /** Latest Play Next request wins; a multi-track block retains its internal order. */
    @Synchronized
    fun playNext(trackIds: List<Long>) {
        val additions = upNextEntries(trackIds)
        if (additions.isEmpty()) return
        val insertionIndex = ((currentIndex ?: -1) + 1).coerceIn(0, entries.size)
        entries.addAll(insertionIndex, additions)
        touchEntries()
    }

    @Synchronized
    fun playNext(trackId: Long) = playNext(listOf(trackId))

    /** Appends to the explicit FIFO Up Next section, before the underlying continuation. */
    @Synchronized
    fun addToUpNext(trackIds: List<Long>, shuffled: Boolean = false) {
        val additions = upNextEntries(trackIds).toMutableList()
        if (additions.isEmpty()) return
        if (shuffled) Collections.shuffle(additions, Random(System.nanoTime()))
        val firstContinuation = entries.indexOfFirstFrom((currentIndex ?: -1) + 1) {
            it.origin == QueueOrigin.CONTINUATION
        }
        entries.addAll(if (firstContinuation < 0) entries.size else firstContinuation, additions)
        touchEntries()
    }

    @Synchronized
    fun addToUpNext(trackId: Long) = addToUpNext(listOf(trackId))

    @Synchronized
    fun removeEntry(entryId: Long): Long? {
        val index = entries.indexOfFirst { it.id == entryId }
        if (index < 0) return null
        val wasCurrent = index == currentIndex
        val removed = entries.removeAt(index)
        currentIndex = when {
            entries.isEmpty() -> null
            wasCurrent -> index.takeIf { it in entries.indices }
            currentIndex != null && index < currentIndex!! -> currentIndex!! - 1
            else -> currentIndex
        }
        touchEntries()
        return removed.trackId
    }

    /** Reorders an upcoming item inside its own section without breaking the two-layer model. */
    @Synchronized
    fun moveEntry(entryId: Long, delta: Int): Boolean {
        val index = entries.indexOfFirst { it.id == entryId }
        val target = index + delta
        val current = currentIndex ?: -1
        if (index <= current || target <= current || index !in entries.indices || target !in entries.indices) return false
        if (entries[index].origin != entries[target].origin) return false
        val entry = entries.removeAt(index)
        entries.add(target, entry)
        touchEntries()
        return true
    }

    @Synchronized
    fun nextIgnoringRepeatOne(): Long? = ignoringRepeatOne(::next)

    @Synchronized
    fun previousIgnoringRepeatOne(): Long? = ignoringRepeatOne(::previous)

    @Synchronized
    fun clearUpNext() {
        val start = ((currentIndex ?: -1) + 1).coerceAtLeast(0)
        if (entries.removeAllFrom(start) { it.origin == QueueOrigin.UP_NEXT }) touchEntries()
    }

    @Synchronized
    fun clearRemaining() {
        val keep = currentIndex?.plus(1) ?: 0
        if (keep < entries.size) {
            entries.subList(keep, entries.size).clear()
            touchEntries()
        }
    }

    @Synchronized
    fun clear() {
        if (entries.isEmpty() && currentIndex == null) return
        entries.clear()
        currentIndex = null
        touchEntries()
    }

    @Synchronized
    fun retainKnown(knownTrackIds: Set<Long>) {
        val currentId = currentEntryId()
        if (!entries.removeAll { it.trackId !in knownTrackIds }) return
        currentIndex = currentId?.let { id -> entries.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            ?: currentIndex?.coerceAtMost(entries.lastIndex)?.takeIf { entries.isNotEmpty() }
        touchEntries()
    }

    @Synchronized
    fun toggleShuffle(): Boolean {
        shuffleEnabled = !shuffleEnabled
        if (shuffleEnabled) shuffleSeed = System.nanoTime()
        reorderFutureContinuation(shuffleEnabled)
        return shuffleEnabled
    }

    @Synchronized
    fun cycleRepeat(): RepeatMode {
        repeatMode = repeatMode.next()
        return repeatMode
    }

    /** Used by audiobook playback: chapters remain ordered and do not loop. */
    @Synchronized
    fun applyOrderedPlayback(): Boolean {
        val changed = shuffleEnabled || repeatMode != RepeatMode.OFF
        if (!changed) return false
        shuffleEnabled = false
        repeatMode = RepeatMode.OFF
        reorderFutureContinuation(shuffled = false)
        return true
    }

    @Synchronized
    fun snapshot(): QueueSnapshot = QueueSnapshot(
        entries = immutableEntries(),
        visibleEntries = immutableVisibleEntries(),
        currentEntryId = currentEntryId(),
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        shuffleSeed = shuffleSeed
    )

    @Synchronized
    fun session(positionMs: Long): PersistedPlaybackSession = PersistedPlaybackSession(
        currentEntryId = currentEntryId(),
        positionMs = positionMs,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        shuffleSeed = shuffleSeed
    )

    private fun first(): Long? {
        if (entries.isEmpty()) return null
        currentIndex = 0
        touchVisible()
        return currentTrackId()
    }

    private fun nextInList(): QueueEntry? {
        val nextIndex = (currentIndex ?: -1) + 1
        return entries.getOrNull(nextIndex)
    }

    private fun nextContinuationPass(mutate: Boolean): List<QueueEntry> {
        val continuation = entries.filter { it.origin == QueueOrigin.CONTINUATION }.toMutableList()
        if (continuation.isEmpty()) return emptyList()
        if (shuffleEnabled) {
            val seed = nextShuffleSeed()
            shuffleEntries(continuation, seed)
            avoidImmediateRepeat(continuation)
            if (mutate) shuffleSeed = seed
        } else {
            continuation.sortBy { it.sourceOrder ?: Int.MAX_VALUE }
        }
        return continuation
    }

    private fun reorderFutureContinuation(shuffled: Boolean) {
        val start = ((currentIndex ?: -1) + 1).coerceAtLeast(0)
        if (start >= entries.size) return
        val future = entries.subList(start, entries.size).toList()
        val manual = future.filter { it.origin == QueueOrigin.UP_NEXT }
        val continuation = future.filter { it.origin == QueueOrigin.CONTINUATION }.toMutableList()
        if (shuffled) shuffleEntries(continuation, shuffleSeed)
        else continuation.sortBy { it.sourceOrder ?: Int.MAX_VALUE }
        entries.subList(start, entries.size).clear()
        entries += manual
        entries += continuation
        touchEntries()
    }

    private fun continuationEntries(trackIds: List<Long>): List<QueueEntry> {
        val available = (MAX_QUEUE_ITEMS - entries.size).coerceAtLeast(0)
        return trackIds.take(available).mapIndexed { sourceOrder, id ->
            QueueEntry(nextEntryId++, id, QueueOrigin.CONTINUATION, sourceOrder)
        }
    }

    private fun upNextEntries(trackIds: List<Long>): List<QueueEntry> {
        val available = (MAX_QUEUE_ITEMS - entries.size).coerceAtLeast(0)
        return trackIds.take(available).map { QueueEntry(nextEntryId++, it, QueueOrigin.UP_NEXT, null) }
    }

    private fun normalizeEntries(values: List<QueueEntry>): List<QueueEntry> {
        val used = HashSet<Long>(values.size)
        var generated = (values.maxOfOrNull(QueueEntry::id) ?: 0L) + 1L
        var continuationOrder = 0
        return values.map { entry ->
            val id = entry.id.takeIf { it > 0 && used.add(it) } ?: generated++.also(used::add)
            val sourceOrder = if (entry.origin == QueueOrigin.CONTINUATION) {
                (entry.sourceOrder ?: continuationOrder).also { continuationOrder = maxOf(continuationOrder, it + 1) }
            } else null
            QueueEntry(id, entry.trackId, entry.origin, sourceOrder)
        }
    }

    private fun ignoringRepeatOne(operation: () -> Long?): Long? {
        if (repeatMode != RepeatMode.ONE) return operation()
        repeatMode = RepeatMode.OFF
        return try { operation() } finally { repeatMode = RepeatMode.ONE }
    }

    private fun shuffleEntries(values: MutableList<QueueEntry>, seed: Long) {
        Collections.shuffle(values, Random(seed))
    }

    private fun avoidImmediateRepeat(values: MutableList<QueueEntry>) {
        val currentId = currentEntryId() ?: return
        if (values.size > 1 && values.first().id == currentId) Collections.swap(values, 0, 1)
    }

    private fun nextShuffleSeed(): Long = shuffleSeed xor -7046029254386353131L

    private fun touchEntries() {
        entriesRevision += 1
        touchVisible()
    }

    private fun touchVisible() {
        visibleRevision += 1
    }

    private fun immutableEntries(): List<QueueEntry> {
        if (cachedEntriesRevision != entriesRevision) {
            cachedEntries = Collections.unmodifiableList(ArrayList(entries))
            cachedEntriesRevision = entriesRevision
        }
        return cachedEntries
    }

    private fun immutableVisibleEntries(): List<QueueEntry> {
        if (cachedVisibleRevision != visibleRevision) {
            val start = currentIndex?.coerceIn(0, entries.size) ?: 0
            cachedVisibleEntries = Collections.unmodifiableList(ArrayList(entries.subList(start, entries.size)))
            cachedVisibleRevision = visibleRevision
        }
        return cachedVisibleEntries
    }

    private inline fun <T> List<T>.indexOfFirstFrom(start: Int, predicate: (T) -> Boolean): Int {
        for (index in start.coerceAtLeast(0)..lastIndex) if (predicate(this[index])) return index
        return -1
    }

    private inline fun <T> MutableList<T>.removeAllFrom(start: Int, predicate: (T) -> Boolean): Boolean {
        var changed = false
        for (index in lastIndex downTo start.coerceAtLeast(0)) {
            if (predicate(this[index])) {
                removeAt(index)
                changed = true
            }
        }
        return changed
    }

    companion object {
        const val MAX_QUEUE_ITEMS = 50_000
    }
}
