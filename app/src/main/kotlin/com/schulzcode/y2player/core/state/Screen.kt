package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AlbumKey
import com.schulzcode.y2player.core.model.LibraryScope

sealed interface Screen {
    data object MainMenu : Screen
    data class Search(
        val query: String = "",
        val keyboardRow: Int = 0,
        val keyboardColumn: Int = 0,
        val resultsFocused: Boolean = false
    ) : Screen
    data object Music : Screen
    data object Audiobooks : Screen
    data class AudiobookOptions(val folderKey: String) : Screen
    data class AudiobookChapters(val folderKey: String) : Screen
    data object Songs : Screen
    data object Favorites : Screen
    data object RecentlyPlayed : Screen
    data object Albums : Screen
    data class AlbumSongs(val album: String, val albumArtist: String? = null) : Screen
    data object Artists : Screen
    data class ArtistAlbums(val artist: String) : Screen
    data class ArtistSongs(val artist: String) : Screen
    data object Genres : Screen
    data object Years : Screen
    data class FacetMenu(val scope: LibraryScope) : Screen
    data class FacetArtists(val scope: LibraryScope) : Screen
    data class FacetAlbums(val scope: LibraryScope) : Screen
    data class FacetArtistAlbums(val scope: LibraryScope, val artist: String) : Screen
    data class FacetTracks(
        val scope: LibraryScope,
        val title: String,
        val artist: String? = null,
        val album: AlbumKey? = null
    ) : Screen
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
    data class CollectionOptions(val title: String, val trackIds: List<Long>) : Screen
    data class MultiSelect(
        val trackIds: List<Long>,
        val selectedIndices: Set<Int> = emptySet()
    ) : Screen
    data class QueueOptions(val entryId: Long) : Screen
    data class QueueMove(val entryId: Long, val targetIndex: Int) : Screen
    data object QueueManagement : Screen
    data object NowPlaying : Screen
    data object NowPlayingOptions : Screen
    data object SleepTimer : Screen
    data object Queue : Screen
    data object Audio : Screen
    data object Settings : Screen
    data object PlaybackTransitions : Screen
    data object PlaybackSeeking : Screen
    data object PlaybackVolume : Screen
    data object PlaybackInterruptions : Screen
    data object SoundEffects : Screen
    data object EqualizerSettings : Screen
    data object EqualizerPresets : Screen
    data object OutputInformation : Screen
    data object EqualizerBands : Screen
    data class EqualizerBandLevel(val bandIndex: Int) : Screen
    data object SortOrder : Screen
    data object TrackSorting : Screen
    data object AlbumSorting : Screen
    data object YearSorting : Screen
    data object Bluetooth : Screen
    data class BluetoothDevice(val address: String) : Screen
    data class ConfirmAction(val key: String) : Screen
    data object InterfaceSettings : Screen
    data object LibrarySettings : Screen
    data object Display : Screen
    data object Controls : Screen
    data object Balance : Screen
    data object Brightness : Screen
    data object ScreenTimeout : Screen
    data object Storage : Screen
    data object PlaybackHistory : Screen

    data object System : Screen
    data object BackupRestore : Screen
    data object Diagnostics : Screen
    data object Reset : Screen
    data object About : Screen
}

data class ScreenEntry(val screen: Screen, val selectedIndex: Int = 0)

/** Command menus shown with the wheel-driven circular overlay. */
fun Screen.isRadialMenu(): Boolean =
    this == Screen.NowPlayingOptions || this == Screen.QueueManagement || this is Screen.QueueOptions

// R8 renames these classes, so simpleName logs as `b0` in release builds.
val Screen.code: String get() = when (this) {
    Screen.MainMenu -> "main_menu"
    is Screen.Search -> "search"
    Screen.Music -> "music"
    Screen.Audiobooks -> "audiobooks"
    is Screen.AudiobookOptions -> "audiobook_options"
    is Screen.AudiobookChapters -> "audiobook_chapters"
    Screen.Songs -> "songs"
    Screen.Favorites -> "favorites"
    Screen.RecentlyPlayed -> "recently_played"
    Screen.Albums -> "albums"
    is Screen.AlbumSongs -> "album_songs"
    Screen.Artists -> "artists"
    is Screen.ArtistAlbums -> "artist_albums"
    is Screen.ArtistSongs -> "artist_songs"
    Screen.Genres -> "genres"
    Screen.Years -> "years"
    is Screen.FacetMenu -> "facet_menu"
    is Screen.FacetArtists -> "facet_artists"
    is Screen.FacetAlbums -> "facet_albums"
    is Screen.FacetArtistAlbums -> "facet_artist_albums"
    is Screen.FacetTracks -> "facet_tracks"
    is Screen.Folders -> "folders"
    Screen.Playlists -> "playlists"
    is Screen.PlaylistTracks -> "playlist_tracks"
    is Screen.TrackOptions -> "track_options"
    is Screen.TrackBrowse -> "track_browse"
    is Screen.TrackDetails -> "track_details"
    is Screen.AddToPlaylist -> "add_to_playlist"
    is Screen.CollectionOptions -> "collection_options"
    is Screen.MultiSelect -> "multi_select"
    is Screen.QueueOptions -> "queue_options"
    is Screen.QueueMove -> "queue_move"
    Screen.QueueManagement -> "queue_management"
    Screen.NowPlaying -> "now_playing"
    Screen.NowPlayingOptions -> "now_playing_options"
    Screen.SleepTimer -> "sleep_timer"
    Screen.Queue -> "queue"
    Screen.Audio -> "audio"
    Screen.Settings -> "settings"
    Screen.PlaybackTransitions -> "playback_transitions"
    Screen.PlaybackSeeking -> "playback_seeking"
    Screen.PlaybackVolume -> "playback_volume"
    Screen.PlaybackInterruptions -> "playback_interruptions"
    Screen.SoundEffects -> "sound_effects"
    Screen.EqualizerSettings -> "equalizer_settings"
    Screen.EqualizerPresets -> "equalizer_presets"
    Screen.OutputInformation -> "output_information"
    Screen.EqualizerBands -> "equalizer_bands"
    is Screen.EqualizerBandLevel -> "equalizer_band_level"
    Screen.SortOrder -> "sort_order"
    Screen.TrackSorting -> "track_sorting"
    Screen.AlbumSorting -> "album_sorting"
    Screen.YearSorting -> "year_sorting"
    Screen.Bluetooth -> "bluetooth"
    is Screen.BluetoothDevice -> "bluetooth_device"
    is Screen.ConfirmAction -> "confirm_action"
    Screen.InterfaceSettings -> "interface_settings"
    Screen.LibrarySettings -> "library_settings"
    Screen.Display -> "display"
    Screen.Controls -> "controls"
    Screen.Balance -> "balance"
    Screen.Brightness -> "brightness"
    Screen.ScreenTimeout -> "screen_timeout"
    Screen.Storage -> "storage"
    Screen.PlaybackHistory -> "playback_history"
    Screen.System -> "system"
    Screen.BackupRestore -> "backup_restore"
    Screen.Diagnostics -> "diagnostics"
    Screen.Reset -> "reset"
    Screen.About -> "about"
}
