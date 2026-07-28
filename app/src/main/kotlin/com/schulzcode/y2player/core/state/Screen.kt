package com.schulzcode.y2player.core.state

sealed interface Screen {
    data object MainMenu : Screen
    data object Music : Screen
    data object Songs : Screen
    data object Favorites : Screen
    data object RecentlyPlayed : Screen
    data object Albums : Screen
    /**
     * @param artist scopes the album to one artist when it was reached through
     *   [ArtistAlbums]. Null means the global Albums list, which deliberately
     *   merges every artist that shares the album name so a compilation stays one
     *   album. Without this, two artists each with a "Greatest Hits" opened the
     *   same merged track list.
     */
    data class AlbumSongs(val album: String, val artist: String? = null) : Screen
    data object Artists : Screen
    /** Albums by one artist. The middle step of Artists → albums → songs. */
    data class ArtistAlbums(val artist: String) : Screen
    data class ArtistSongs(val artist: String) : Screen
    data class Folders(val volumeId: String? = null, val relativePath: String = "") : Screen
    data object Playlists : Screen
    data class PlaylistTracks(val playlistId: Long, val name: String) : Screen
    data class TrackOptions(
        val trackId: Long,
        val sourcePlaylistId: Long? = null,
        val fromNowPlaying: Boolean = false
    ) : Screen
    data class TrackBrowse(val trackId: Long) : Screen
    data class TrackDetails(val trackId: Long) : Screen
    data class AddToPlaylist(val trackId: Long) : Screen
    data class QueueOptions(val queueIndex: Int) : Screen
    data object QueueManagement : Screen
    data object NowPlaying : Screen
    data object NowPlayingOptions : Screen
    data object Queue : Screen
    data object Audio : Screen
    data object Settings : Screen
    data object PlaybackSettings : Screen
    data object PlaybackTransitions : Screen
    data object PlaybackSeeking : Screen
    data object PlaybackVolume : Screen
    data object PlaybackInterruptions : Screen
    data object SoundSettings : Screen
    data object EqualizerSettings : Screen
    data object SoundDynamics : Screen
    data object OutputInformation : Screen
    data object EqualizerBands : Screen
    data object SortOrder : Screen
    data object Bluetooth : Screen
    data object InterfaceSettings : Screen
    data object LibrarySettings : Screen
    data object Display : Screen
    /**
     * Wheel and button feedback.
     *
     * Its own screen rather than a corner of Display: haptics and UI sounds are
     * responses to *input*, and the only thing they share with brightness is that
     * both are device rather than music settings.
     */
    data object Controls : Screen
    data object Balance : Screen
    data object Brightness : Screen
    data object ScreenTimeout : Screen
    data object Storage : Screen

    data object System : Screen
    data object Diagnostics : Screen
    data object About : Screen
}

data class ScreenEntry(val screen: Screen, val selectedIndex: Int = 0)
