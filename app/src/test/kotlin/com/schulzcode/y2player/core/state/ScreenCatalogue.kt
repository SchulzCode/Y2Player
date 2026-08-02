package com.schulzcode.y2player.core.state

import kotlin.reflect.KClass

/**
 * One sample of every screen, derived from the sealed hierarchy rather than typed
 * out by hand. `everyScreenIsCovered` fails when a new subtype appears, so a screen
 * cannot be added without the audits seeing it.
 */
object ScreenCatalogue {

    private val samples: Map<KClass<out Screen>, Screen> = buildMap {
        fun put(screen: Screen) = put(screen::class, screen)
        put(Screen.MainMenu)
        put(Screen.Music)
        put(Screen.Audiobooks)
        put(Screen.AudiobookOptions("sdcard|AUDIOBOOKS/Dune"))
        put(Screen.AudiobookChapters("sdcard|AUDIOBOOKS/Dune"))
        put(Screen.Songs)
        put(Screen.Favorites)
        put(Screen.RecentlyPlayed)
        put(Screen.Albums)
        put(Screen.AlbumSongs("Album"))
        put(Screen.Artists)
        put(Screen.ArtistAlbums("Artist"))
        put(Screen.ArtistSongs("Artist"))
        put(Screen.Folders())
        put(Screen.Playlists)
        put(Screen.PlaylistTracks(5, "Road Trip"))
        put(Screen.TrackOptions(1))
        put(Screen.TrackBrowse(1))
        put(Screen.TrackDetails(1))
        put(Screen.AddToPlaylist(1))
        put(Screen.QueueOptions(0))
        put(Screen.QueueManagement)
        put(Screen.NowPlaying)
        put(Screen.NowPlayingOptions)
        put(Screen.Queue)
        put(Screen.Audio)
        put(Screen.Settings)
        put(Screen.PlaybackTransitions)
        put(Screen.PlaybackSeeking)
        put(Screen.PlaybackVolume)
        put(Screen.PlaybackInterruptions)
        put(Screen.SoundEffects)
        put(Screen.EqualizerSettings)
        put(Screen.OutputInformation)
        put(Screen.EqualizerBands)
        put(Screen.SortOrder)
        put(Screen.Bluetooth)
        put(Screen.BluetoothDevice("AA:BB:CC:DD:EE:FF"))
        put(Screen.ConfirmAction(ConfirmPrompts.CLEAR_QUEUE))
        put(Screen.InterfaceSettings)
        put(Screen.LibrarySettings)
        put(Screen.Display)
        put(Screen.Controls)
        put(Screen.Balance)
        put(Screen.Brightness)
        put(Screen.ScreenTimeout)
        put(Screen.Storage)
        put(Screen.PlaybackHistory)
        put(Screen.System)
        put(Screen.Diagnostics)
        put(Screen.Reset)
        put(Screen.About)
    }

    fun declaredSubtypes(): List<KClass<out Screen>> = Screen::class.sealedSubclasses

    fun all(): List<Screen> = samples.values.toList()

    fun missingFromCatalogue(): List<String> =
        declaredSubtypes().filterNot { it in samples }.map { it.simpleName ?: "<anonymous>" }

    /** Screens that hold no rows by design; everything else must build something. */
    val rowless: Set<String> = setOf(Screen.NowPlaying.code)

    /** Screens whose content depends on a library, so emptiness is legitimate. */
    val contentScreens: Set<String> = setOf(
        Screen.Songs.code, Screen.Favorites.code, Screen.RecentlyPlayed.code,
        Screen.Albums.code, Screen.Artists.code, Screen.Audiobooks.code,
        Screen.Queue.code, Screen.AlbumSongs("").code, Screen.ArtistAlbums("").code,
        Screen.ArtistSongs("").code, Screen.Folders().code, Screen.PlaylistTracks(0, "").code,
        Screen.AudiobookChapters("").code, Screen.EqualizerBands.code
    )
}
