package com.schulzcode.y2player.core.state

sealed interface Screen {
    data object MainMenu : Screen
    data object Songs : Screen
    data object Favorites : Screen
    data object RecentlyPlayed : Screen
    data object Albums : Screen
    data class AlbumSongs(val album: String) : Screen
    data object Artists : Screen
    data class ArtistSongs(val artist: String) : Screen
    data class Folders(val volumeId: String? = null, val relativePath: String = "") : Screen
    data object Playlists : Screen
    data class PlaylistTracks(val playlistId: Long, val name: String) : Screen
    data class TrackOptions(val trackId: Long, val sourcePlaylistId: Long? = null) : Screen
    data class AddToPlaylist(val trackId: Long) : Screen
    data class QueueOptions(val queueIndex: Int) : Screen
    data object NowPlaying : Screen
    data object NowPlayingOptions : Screen
    data object Queue : Screen
    data object Settings : Screen
    data object PlaybackSettings : Screen
    data object SoundSettings : Screen
    data object EqualizerBands : Screen
    data object SortOrder : Screen
    data object Bluetooth : Screen
    data object Display : Screen
    data object Brightness : Screen
    data object ScreenTimeout : Screen
    data object Storage : Screen

    data object System : Screen
    data object Diagnostics : Screen
    data object About : Screen
}

data class ScreenEntry(val screen: Screen, val selectedIndex: Int = 0)
