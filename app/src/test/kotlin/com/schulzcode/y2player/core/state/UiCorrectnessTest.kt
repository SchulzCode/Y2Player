package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UiCorrectnessTest {

    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

    private val track = Track(
        id = 1,
        volumeId = "sdcard",
        absolutePath = "/storage/sdcard1/Music/song.mp3",
        relativePath = "Music/song.mp3",
        title = "Song",
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        trackNumber = 1,
        discNumber = 1,
        durationMs = 60_000,
        fileSize = 123,
        modifiedAt = 1
    )

    private val playing = PlaybackSnapshot(
        currentTrackId = 1L,
        queue = testQueue(1L),
        currentQueueEntryId = 1L,
        status = PlaybackStatus.PLAYING
    )

    private fun keys(state: AppState) = ScreenContent.rows(state).map { (it as ScreenRow.Action).key }

    // ---- main-menu playback shortcuts ---------------------------------------------

    @Test fun `a live session has no duplicate Now Playing row`() {
        val state = AppState(library = LibraryState(), playback = playing)
        assertEquals(
            listOf("music", "audiobooks", "search", "settings"),
            keys(state)
        )
    }

    @Test fun `main destinations stay stable when the session ends`() {
        val stopped = AppState(library = LibraryState(tracks = listOf(track)), playback = PlaybackSnapshot())
        assertEquals(listOf("music", "audiobooks", "search", "settings"), keys(stopped))
    }

    @Test fun `a restored queue has neither Shuffle All nor a duplicate Now Playing row`() {
        val restored = AppState(
            library = LibraryState(tracks = listOf(track)),
            playback = PlaybackSnapshot(queue = testQueue(1L), currentQueueEntryId = 1L)
        )
        assertEquals(
            listOf("music", "audiobooks", "search", "settings"),
            keys(restored)
        )
    }

    @Test fun `Settings selection survives removal of the idle Shuffle All row`() {
        val idle = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu, 4)),
            library = LibraryState(tracks = listOf(track))
        )
        val live = AppReducer.reduce(idle, AppAction.PlaybackChanged(playing)).state
        assertEquals("settings", keys(live)[live.selectedIndex])
    }

    @Test fun `long Play reaches Now Playing for a restored queue`() {
        val restored = AppState(
            library = LibraryState(tracks = listOf(track)),
            playback = PlaybackSnapshot(queue = testQueue(1L), currentQueueEntryId = 1L)
        )
        assertEquals(
            Screen.NowPlaying,
            AppReducer.reduce(restored, AppAction.ShowNowPlaying).state.currentScreen
        )
    }

    @Test fun `long Play still does nothing when there is no session at all`() {
        val idle = AppState(library = LibraryState(tracks = listOf(track)))
        val result = AppReducer.reduce(idle, AppAction.ShowNowPlaying)
        assertEquals(Screen.MainMenu, result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    // ---- selection safety across changing row sets --------------------------------

    @Test fun `selection stays in range when a conditional row set shrinks`() {
        val withPlaylists = AppState(
            screenStack = listOf(ScreenEntry(Screen.Playlists, 1)),
            library = LibraryState(tracks = listOf(track), playlists = listOf(PlaylistSummary(5, "Road Trip", 1)))
        )
        val emptied = AppReducer.reduce(withPlaylists, AppAction.LibraryChanged(LibraryState())).state
        assertTrue(emptied.selectedIndex < ScreenContent.rows(emptied).size)
    }

    @Test fun `losing every track leaves the main menu selection valid`() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu, 3)),
            library = LibraryState(tracks = listOf(track))
        )
        val wiped = AppReducer.reduce(state, AppAction.LibraryChanged(LibraryState())).state
        assertTrue(wiped.selectedIndex in ScreenContent.rows(wiped).indices)
    }

    @Test fun `Confirm on an empty collection never crashes and never plays`() {
        listOf(Screen.Songs, Screen.Favorites, Screen.RecentlyPlayed, Screen.Queue, Screen.Audiobooks)
            .forEach { screen ->
                val state = AppState(
                    screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(screen)),
                    library = LibraryState()
                )
                assertTrue("rows should be empty for $screen", ScreenContent.rows(state).isEmpty())
                val result = AppReducer.reduce(state, AppAction.Confirm)
                assertTrue(
                    "$screen started playback from an empty list",
                    result.effects.none { it is AppEffect.PlayCollection || it == AppEffect.ShuffleAll }
                )
            }
    }

    @Test fun `Back out of an empty collection reaches a real screen`() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.Music), ScreenEntry(Screen.Favorites)),
            library = LibraryState()
        )
        val back = AppReducer.reduce(state, AppAction.Back).state
        assertEquals(Screen.Music, back.currentScreen)
    }

    // ---- every screen has a title and survives an empty world ---------------------

    @Test fun `every screen has a non blank title`() {
        allScreens().forEach { screen ->
            val state = AppState(screenStack = listOf(ScreenEntry(screen)))
            assertTrue("blank title for ${screen.code}", ScreenContent.title(state).isNotBlank())
        }
    }

    @Test fun `no screen throws or mis-selects with an empty library and no device`() {
        allScreens().forEach { screen ->
            val state = AppState(screenStack = listOf(ScreenEntry(screen)), library = LibraryState())
            val rows = ScreenContent.rows(state)
            val result = AppReducer.reduce(state, AppAction.Confirm)
            assertTrue("${screen.code} left the selection out of range",
                result.state.selectedIndex >= 0)
            if (rows.isNotEmpty()) {
                assertTrue("${screen.code} has a blank row title", rows.all { it.title.isNotBlank() })
            }
        }
    }

    @Test fun `every settings screen produces rows on a bare device`() {
        // A settings screen with no rows means its builder was lost. Content screens
        // are excluded: they are legitimately empty without a library.
        val settingsScreens = listOf(
            Screen.Settings, Screen.Audio, Screen.OutputInformation, Screen.PlaybackTransitions,
            Screen.PlaybackSeeking, Screen.PlaybackVolume, Screen.PlaybackInterruptions,
            Screen.SoundEffects, Screen.EqualizerSettings, Screen.EqualizerPresets,
            Screen.EqualizerBandLevel(0), Screen.SortOrder, Screen.Bluetooth,
            Screen.InterfaceSettings, Screen.LibrarySettings, Screen.Display, Screen.Controls,
            Screen.Balance, Screen.Brightness, Screen.ScreenTimeout, Screen.PlaybackHistory,
            Screen.System, Screen.Diagnostics, Screen.Reset, Screen.About
        )
        settingsScreens.forEach { screen ->
            val rows = ScreenContent.rows(AppState(screenStack = listOf(ScreenEntry(screen))))
            assertTrue("${screen.code} has no rows", rows.isNotEmpty())
        }
    }

    @Test fun `every settings screen offers at least one thing to activate`() {
        val readOnly = setOf(Screen.About.code, Screen.Diagnostics.code)
        listOf(
            Screen.Settings, Screen.Audio, Screen.OutputInformation, Screen.PlaybackTransitions,
            Screen.PlaybackSeeking, Screen.PlaybackVolume, Screen.PlaybackInterruptions,
            Screen.InterfaceSettings, Screen.LibrarySettings, Screen.Display, Screen.Controls,
            Screen.System, Screen.Reset
        ).filterNot { it.code in readOnly }.forEach { screen ->
            val rows = ScreenContent.rows(AppState(screenStack = listOf(ScreenEntry(screen))))
            assertTrue(
                "${screen.code} has no actionable row",
                rows.any { it is ScreenRow.Action }
            )
        }
    }

    @Test fun `every destructive confirmation names what is lost`() {
        val prompts = listOf(
            ConfirmPrompts.CLEAR_QUEUE,
            ConfirmPrompts.RESET_LIBRARY,
            ConfirmPrompts.FORGET_DEVICE + "AA:BB",
            ConfirmPrompts.CLEAR_AUDIOBOOK + "sdcard|AUDIOBOOKS/Dune"
        )
        prompts.forEach { key ->
            val state = AppState(screenStack = listOf(ScreenEntry(Screen.ConfirmAction(key))))
            val rows = ScreenContent.rows(state)
            val prompt = rows.first() as ScreenRow.Group
            assertTrue("$key has no question", prompt.title.endsWith("?"))
            assertTrue("$key does not say what happens", prompt.subtitle.orEmpty().length > 20)
            assertEquals(
                "$key must preselect Cancel",
                ScreenContent.CONFIRM_CANCEL_KEY,
                (rows[ScreenContent.CONFIRM_DEFAULT_INDEX] as ScreenRow.Action).key
            )
        }
    }

    @Test fun `resetting the library does not claim to rebuild what it deletes`() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.ConfirmAction(ConfirmPrompts.RESET_LIBRARY)))
        )
        val detail = (ScreenContent.rows(state).first() as ScreenRow.Group).subtitle.orEmpty()
        // resetLibrary() drops playlists, favourites, history and audiobook_progress.
        // Only the track index comes back, from a rescan.
        assertFalse("playlists are deleted, not rebuilt", detail.contains("playlists are rebuilt"))
        assertTrue("must mention playlists", detail.contains("Playlists"))
        assertTrue("must mention favourites", detail.contains("favourites"))
        assertTrue("must mention audiobook positions", detail.contains("audiobook positions"))
        assertTrue("must reassure about the music files", detail.contains("Music files are kept"))
    }

    @Test fun `no screen code is duplicated`() {
        val codes = allScreens().map { it.code }
        assertEquals("screen codes must be unique", codes.size, codes.toSet().size)
    }

    private fun allScreens(): List<Screen> = listOf(
        Screen.MainMenu, Screen.Search(), Screen.Music, Screen.Audiobooks,
        Screen.AudiobookOptions("sdcard|AUDIOBOOKS/Dune"), Screen.AudiobookChapters("sdcard|AUDIOBOOKS/Dune"),
        Screen.Songs, Screen.Favorites,
        Screen.RecentlyPlayed, Screen.Albums, Screen.AlbumSongs("Album"), Screen.Artists,
        Screen.ArtistAlbums("Artist"), Screen.ArtistSongs("Artist"), Screen.Folders(),
        Screen.Playlists, Screen.PlaylistTracks(5, "Road Trip"), Screen.TrackOptions(1),
        Screen.TrackBrowse(1), Screen.TrackDetails(1), Screen.AddToPlaylist(1),
        Screen.QueueOptions(0), Screen.QueueMove(0, 0), Screen.QueueManagement, Screen.NowPlaying,
        Screen.NowPlayingOptions, Screen.Queue, Screen.Audio, Screen.Settings,
        Screen.PlaybackTransitions, Screen.PlaybackSeeking,
        Screen.PlaybackVolume, Screen.PlaybackInterruptions, Screen.SoundEffects,
        Screen.EqualizerSettings, Screen.EqualizerPresets, Screen.OutputInformation,
        Screen.EqualizerBands, Screen.EqualizerBandLevel(0), Screen.SortOrder, Screen.Bluetooth,
        Screen.BluetoothDevice("AA:BB:CC:DD:EE:FF"), Screen.ConfirmAction("forget_device:AA:BB"),
        Screen.InterfaceSettings, Screen.LibrarySettings, Screen.Display, Screen.Controls,
        Screen.Balance, Screen.Brightness, Screen.ScreenTimeout, Screen.Storage,
        Screen.PlaybackHistory, Screen.System, Screen.Diagnostics, Screen.Reset, Screen.About
    )

    // ---- conditional settings rows -------------------------------------------------

    @Test fun `Crossfade Mode appears only while crossfade is on`() {
        val off = AppState(
            screenStack = listOf(ScreenEntry(Screen.PlaybackTransitions)),
            preferences = AppState().preferences.copy(crossfadeMs = 0)
        )
        assertFalse("crossfade_mode" in keys(off))

        val on = off.copy(preferences = off.preferences.copy(crossfadeMs = 4_000))
        assertTrue("crossfade_mode" in keys(on))
    }

    @Test fun `display and safe mode change subtitles only, never the row count`() {
        // These two actions deliberately skip selection clamping. That is only safe
        // while no row appears or disappears because of them.
        val screens = listOf(
            Screen.MainMenu, Screen.Settings, Screen.InterfaceSettings, Screen.Display,
            Screen.Audio, Screen.Diagnostics
        )
        screens.forEach { screen ->
            val before = AppState(screenStack = listOf(ScreenEntry(screen)))
            val displayChanged = AppReducer.reduce(
                before,
                AppAction.DisplayChanged(before.display.copy(brightnessPercent = 90))
            ).state
            val safeModeChanged = AppReducer.reduce(before, AppAction.SafeModeChanged(true)).state
            assertEquals(
                "${screen.code} changes row count on DisplayChanged and must clamp selection",
                ScreenContent.rows(before).size,
                ScreenContent.rows(displayChanged).size
            )
            assertEquals(
                "${screen.code} changes row count on SafeModeChanged and must clamp selection",
                ScreenContent.rows(before).size,
                ScreenContent.rows(safeModeChanged).size
            )
        }
    }

    @Test fun `turning crossfade off cannot leave the selection past the last row`() {
        val on = AppState(
            screenStack = listOf(ScreenEntry(Screen.PlaybackTransitions)),
            preferences = AppState().preferences.copy(crossfadeMs = 4_000)
        )
        val lastIndex = ScreenContent.rows(on).lastIndex
        val selected = on.copy(screenStack = listOf(ScreenEntry(Screen.PlaybackTransitions, lastIndex)))
        val off = AppReducer.reduce(
            selected,
            AppAction.PreferencesChanged(selected.preferences.copy(crossfadeMs = 0))
        ).state
        val rows = ScreenContent.rows(off)
        assertTrue("selection ${off.selectedIndex} outside ${rows.size} rows", off.selectedIndex < rows.size)
    }
}
