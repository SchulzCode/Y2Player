package com.schulzcode.y2player.core.model

data class LibraryScanProgress(
    val volumeId: String? = null,
    val currentPath: String? = null,
    val processedFiles: Int = 0
)

class LibraryIndex private constructor(val tracks: List<Track>) {
    val byId: Map<Long, Track>
    val availableTracks: List<Track>
    val favoriteTracks: List<Track>
    val musicTracks: List<Track>
    val favoriteMusicTracks: List<Track>
    val availableTrackIds: Set<Long>
    val organization: LibraryOrganization by lazy { LibraryOrganization(musicTracks) }

    init {
        val ids = HashMap<Long, Track>(tracks.size * 4 / 3 + 1)
        val available = ArrayList<Track>(tracks.size)
        val favorites = ArrayList<Track>()
        val music = ArrayList<Track>(tracks.size)
        val favoriteMusic = ArrayList<Track>()
        val availableIds = HashSet<Long>(tracks.size * 4 / 3 + 1)
        tracks.forEach { track ->
            ids[track.id] = track
            if (track.available) {
                available.add(track)
                availableIds.add(track.id)
                if (track.favorite) favorites.add(track)
                if (!track.isAudiobookChapter) {
                    music.add(track)
                    if (track.favorite) favoriteMusic.add(track)
                }
            }
        }
        byId = ids
        availableTracks = available
        favoriteTracks = favorites
        musicTracks = music
        favoriteMusicTracks = favoriteMusic
        availableTrackIds = availableIds
    }

    companion object {
        val EMPTY = LibraryIndex(emptyList())
        fun of(tracks: List<Track>): LibraryIndex = if (tracks.isEmpty()) EMPTY else LibraryIndex(tracks)
    }
}

data class LibraryState(
    val revision: Long = 0,
    val tracksRevision: Long = 0,
    val availabilityRevision: Long = 0,
    val index: LibraryIndex = LibraryIndex.EMPTY,
    val totalIndexedTracks: Int = 0,
    val playlists: List<PlaylistSummary> = emptyList(),
    val playlistTrackIds: Map<Long, List<Long>> = emptyMap(),
    val recentlyPlayedIds: List<Long> = emptyList(),
    val audiobookProgress: Map<String, AudiobookProgress> = emptyMap(),
    val isScanning: Boolean = false,
    val scanProgress: LibraryScanProgress = LibraryScanProgress(),
    val lastScanAt: Long? = null,
    val errorMessage: String? = null
) {
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
    val musicTracks: List<Track> get() = index.musicTracks
    val favoriteMusicTracks: List<Track> get() = index.favoriteMusicTracks
    val availableTrackIds: Set<Long> get() = index.availableTrackIds
    val recentlyPlayed: List<Track> by lazy { recentlyPlayedIds.mapNotNull(byId::get) }
    val recentlyPlayedMusic: List<Track> by lazy { recentlyPlayed.filterNot(Track::isAudiobookChapter) }
}
