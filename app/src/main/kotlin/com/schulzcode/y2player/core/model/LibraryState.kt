package com.schulzcode.y2player.core.model

data class LibraryScanProgress(
    val volumeId: String? = null,
    val currentPath: String? = null,
    val processedFiles: Int = 0
)

/**
 * Immutable derived lookups for one track list.
 *
 * Built once, on a background thread, whenever the track list actually changes and
 * then shared by reference across [LibraryState] copies. This keeps an ordinary
 * `copy()` — including a recently-played bump on each track transition — from
 * discarding and rebuilding a 30k-entry map on whichever thread touches the state
 * next (usually the UI thread).
 *
 * Identity equality is intentional: two indexes are interchangeable only if they are
 * the same object, which also keeps `LibraryState.equals` cheap.
 */
class LibraryIndex private constructor(val tracks: List<Track>) {
    val byId: Map<Long, Track>
    val availableTracks: List<Track>
    val favoriteTracks: List<Track>
    val availableTrackIds: Set<Long>

    init {
        val ids = HashMap<Long, Track>(tracks.size * 4 / 3 + 1)
        val available = ArrayList<Track>(tracks.size)
        val favorites = ArrayList<Track>()
        val availableIds = HashSet<Long>(tracks.size * 4 / 3 + 1)
        tracks.forEach { track ->
            ids[track.id] = track
            if (track.available) {
                available.add(track)
                availableIds.add(track.id)
                if (track.favorite) favorites.add(track)
            }
        }
        byId = ids
        availableTracks = available
        favoriteTracks = favorites
        availableTrackIds = availableIds
    }

    companion object {
        val EMPTY = LibraryIndex(emptyList())
        fun of(tracks: List<Track>): LibraryIndex = if (tracks.isEmpty()) EMPTY else LibraryIndex(tracks)
    }
}

/**
 * Revisions:
 * - [revision] bumps on any library change (playlists, recently played, tracks).
 * - [tracksRevision] bumps only when the track list itself changes (scan reload,
 *   favorite flag, volume removal). Screens derived purely from tracks key their row
 *   caches on this, so recently-played bookkeeping at every track transition does not
 *   invalidate and re-sort a 30k-row screen on the main thread.
 * - [availabilityRevision] bumps when the set of playable track ids changes; the
 *   playback service reconciles its queue against it.
 */
data class LibraryState(
    val revision: Long = 0,
    val tracksRevision: Long = 0,
    val availabilityRevision: Long = 0,
    val index: LibraryIndex = LibraryIndex.EMPTY,
    val totalIndexedTracks: Int = 0,
    val playlists: List<PlaylistSummary> = emptyList(),
    val playlistTrackIds: Map<Long, List<Long>> = emptyMap(),
    val recentlyPlayedIds: List<Long> = emptyList(),
    val isScanning: Boolean = false,
    val scanProgress: LibraryScanProgress = LibraryScanProgress(),
    val lastScanAt: Long? = null,
    val errorMessage: String? = null
) {
    /** Convenience constructor for tests and callers that start from a plain track list. */
    constructor(
        tracks: List<Track>,
        playlists: List<PlaylistSummary> = emptyList(),
        playlistTrackIds: Map<Long, List<Long>> = emptyMap()
    ) : this(
        index = LibraryIndex.of(tracks),
        totalIndexedTracks = tracks.size,
        playlists = playlists,
        playlistTrackIds = playlistTrackIds
    )

    val tracks: List<Track> get() = index.tracks
    val byId: Map<Long, Track> get() = index.byId
    val availableTracks: List<Track> get() = index.availableTracks
    val favoriteTracks: List<Track> get() = index.favoriteTracks
    val availableTrackIds: Set<Long> get() = index.availableTrackIds
    val recentlyPlayed: List<Track> by lazy { recentlyPlayedIds.mapNotNull(byId::get) }
}
