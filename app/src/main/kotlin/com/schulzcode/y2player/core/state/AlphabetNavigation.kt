package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AlbumSortOrder
import com.schulzcode.y2player.core.model.NaturalTextOrder
import com.schulzcode.y2player.core.model.TrackSortOrder
import java.lang.ref.WeakReference
import java.util.Locale

/** Alphabet navigation policy and its per-row-list first-letter index. */
object AlphabetNavigation {
    private var cachedRows: WeakReference<List<ScreenRow>>? = null
    private var cachedScreen: Screen? = null
    private var cachedIndex: AlphabetIndex? = null
    private var indexBuildCount = 0

    @Synchronized
    fun allowsScrubbing(state: AppState): Boolean = index(state, ScreenContent.rows(state)) != null

    @Synchronized
    fun move(state: AppState, direction: Int): AppState? {
        val rows = ScreenContent.rows(state)
        val index = index(state, rows) ?: return null
        val step = if (direction < 0) -1 else 1
        val currentBucket = state.alphabetScrub?.bucket
            ?: index.bucketForSelection(state.selectedIndex, step)
        val targetBucket = (currentBucket + step).coerceIn(FIRST_BUCKET, LAST_BUCKET)
        val targetRow = if (targetBucket == currentBucket) null else index.firstRow(targetBucket)
        val stack = if (targetRow == null || targetRow == state.selectedIndex) {
            state.screenStack
        } else {
            state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = targetRow)
        }
        return state.copy(
            screenStack = stack,
            alphabetScrub = AlphabetScrubState(targetBucket)
        )
    }

    fun label(bucket: Int): String = if (bucket <= FIRST_BUCKET) "#" else {
        ('A'.code + bucket - 1).toChar().toString()
    }

    @Synchronized
    fun clearCachedIndex() {
        cachedRows = null
        cachedScreen = null
        cachedIndex = null
    }

    @Synchronized
    internal fun indexBuildCountForTests(): Int = indexBuildCount

    @Synchronized
    internal fun resetForTests() {
        clearCachedIndex()
        indexBuildCount = 0
    }

    private fun index(state: AppState, rows: List<ScreenRow>): AlphabetIndex? {
        if (!supportsEffectiveOrder(state) || rows.size < MIN_ROWS) return null
        if (cachedRows?.get() === rows && cachedScreen == state.currentScreen) return cachedIndex

        val firstRows = IntArray(BUCKET_COUNT) { -1 }
        var eligibleRows = 0
        var previousPlaylistTitle: String? = null
        var playlistOrderValid = true
        rows.forEachIndexed { rowIndex, row ->
            val title = alphabetTitle(state.currentScreen, row) ?: return@forEachIndexed
            if (state.currentScreen == Screen.Playlists) {
                val previous = previousPlaylistTitle
                if (previous != null && NaturalTextOrder.compare(previous, title) > 0) {
                    playlistOrderValid = false
                }
                previousPlaylistTitle = title
            }
            eligibleRows += 1
            val bucket = bucket(title)
            if (firstRows[bucket] < 0) firstRows[bucket] = rowIndex
        }
        indexBuildCount += 1
        val built = if (eligibleRows >= MIN_ROWS && playlistOrderValid) AlphabetIndex(firstRows) else null
        cachedRows = WeakReference(rows)
        cachedScreen = state.currentScreen
        cachedIndex = built
        return built
    }

    private fun supportsEffectiveOrder(state: AppState): Boolean = when (val screen = state.currentScreen) {
        Screen.Songs, Screen.Favorites -> state.preferences.sortOrder == TrackSortOrder.TITLE
        Screen.Albums, is Screen.ArtistAlbums, is Screen.FacetAlbums,
        is Screen.FacetArtistAlbums -> state.preferences.albumSortOrder == AlbumSortOrder.TITLE
        Screen.Artists, is Screen.FacetArtists, Screen.Playlists -> true
        is Screen.FacetTracks -> screen.artist == null && screen.album == null &&
            state.preferences.sortOrder == TrackSortOrder.TITLE
        else -> false
    }

    private fun alphabetTitle(screen: Screen, row: ScreenRow): String? = when (screen) {
        Screen.Songs, Screen.Favorites, is Screen.FacetTracks ->
            (row as? ScreenRow.TrackRow)?.title
        Screen.Albums, is Screen.ArtistAlbums, is Screen.FacetAlbums,
        is Screen.FacetArtistAlbums, Screen.Artists, is Screen.FacetArtists ->
            (row as? ScreenRow.Group)?.title
        Screen.Playlists -> (row as? ScreenRow.Action)?.takeIf {
            it.key.startsWith("playlist:")
        }?.title
        else -> null
    }

    private fun bucket(title: String): Int {
        val first = title.trim().uppercase(Locale.US).firstOrNull() ?: return FIRST_BUCKET
        return if (first in 'A'..'Z') first.code - 'A'.code + 1 else FIRST_BUCKET
    }

    private class AlphabetIndex(private val firstRows: IntArray) {
        fun firstRow(bucket: Int): Int? = firstRows[bucket].takeIf { it >= 0 }

        fun bucketForSelection(selectedIndex: Int, direction: Int): Int {
            var directBucket = -1
            for (bucket in FIRST_BUCKET..LAST_BUCKET) {
                if (firstRows[bucket] == selectedIndex) return bucket
                if (firstRows[bucket] in 0..selectedIndex) directBucket = bucket
            }
            if (directBucket >= 0) return directBucket
            return if (direction > 0) FIRST_BUCKET - 1 else LAST_BUCKET + 1
        }
    }

    private const val MIN_ROWS = 12
    private const val FIRST_BUCKET = 0
    private const val LAST_BUCKET = 26
    private const val BUCKET_COUNT = LAST_BUCKET + 1
}
