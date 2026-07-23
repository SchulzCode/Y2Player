package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReducerNowPlayingTest {
    private val track = Track(
        id = 1,
        volumeId = "internal",
        absolutePath = "/storage/sdcard0/song.mp3",
        relativePath = "song.mp3",
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

    private fun nowPlayingState() = AppState(
        screenStack = listOf(ScreenEntry(Screen.NowPlaying)),
        library = LibraryState(tracks = listOf(track)),
        playback = PlaybackSnapshot(currentTrackId = 1, queue = listOf(1L), currentQueueIndex = 0)
    )

    private fun optionsState() = nowPlayingState().let {
        it.copy(screenStack = it.screenStack + ScreenEntry(Screen.NowPlayingOptions))
    }

    private fun optionsReductionFor(key: String): Reduction {
        val base = optionsState()
        val index = ScreenContent.rows(base).indexOfFirst { (it as? ScreenRow.Action)?.key == key }
        assertTrue("row $key must exist", index >= 0)
        val selected = base.copy(screenStack = base.screenStack.dropLast(1) + base.currentEntry.copy(selectedIndex = index))
        return AppReducer.reduce(selected, AppAction.Confirm)
    }

    @Test fun startingPlaybackNavigatesToNowPlaying() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Songs)),
            library = LibraryState(tracks = listOf(track))
        )
        val result = AppReducer.reduce(state, AppAction.Confirm)
        assertEquals(Screen.NowPlaying, result.state.currentScreen)
        assertTrue(result.effects.single() is AppEffect.PlayCollection)
        // Back must return to the originating list.
        assertEquals(Screen.Songs, AppReducer.reduce(result.state, AppAction.Back).state.currentScreen)
    }

    @Test fun mainMenuHasNoBluetoothDuplicateAndKeepsMusicEntriesFirst() {
        val rows = ScreenContent.rows(AppState())
        assertTrue(rows.none { (it as? ScreenRow.Action)?.key == "bluetooth" })
        assertEquals("songs", (rows.first() as ScreenRow.Action).key)
        assertEquals("settings", (rows.last() as ScreenRow.Action).key)
    }

    /** G2: HOME re-entry returns a launcher to its top level, from any depth. */
    @Test fun navigateHomeResetsTheScreenStack() {
        val deep = AppState(
            screenStack = listOf(
                ScreenEntry(Screen.MainMenu, 3),
                ScreenEntry(Screen.Albums, 7),
                ScreenEntry(Screen.AlbumSongs("Album"), 2)
            )
        )
        val result = AppReducer.reduce(deep, AppAction.NavigateHome).state
        assertEquals(1, result.screenStack.size)
        assertEquals(Screen.MainMenu, result.currentScreen)
        assertEquals(0, result.selectedIndex)
    }

    /** Idempotent: pressing HOME on the home screen must not disturb the selection. */
    @Test fun navigateHomeOnHomeScreenIsANoOp() {
        val home = AppState(screenStack = listOf(ScreenEntry(Screen.MainMenu, 4)))
        val result = AppReducer.reduce(home, AppAction.NavigateHome).state
        assertEquals(4, result.selectedIndex)
    }

    /** G3: the platform Settings escape hatch lives at the bottom of System. */
    @Test fun systemScreenExposesTheAndroidSettingsEscapeHatch() {
        val system = AppState(screenStack = listOf(ScreenEntry(Screen.System)))
        val rows = ScreenContent.rows(system)
        val index = rows.indexOfFirst { (it as? ScreenRow.Action)?.key == "android_settings" }
        assertTrue("Android Settings row must exist", index >= 0)
        val selected = system.copy(screenStack = listOf(ScreenEntry(Screen.System, index)))
        assertEquals(AppEffect.OpenAndroidSettings, AppReducer.reduce(selected, AppAction.Confirm).effects.single())
    }

    @Test fun holdCenterOnNowPlayingOpensTheOptionsMenu() {
        val result = AppReducer.reduce(nowPlayingState(), AppAction.ConfirmLong)
        assertEquals(Screen.NowPlayingOptions, result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun holdCenterOnATrackListOpensTrackOptions() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Songs)),
            library = LibraryState(tracks = listOf(track))
        )
        assertEquals(Screen.TrackOptions(1), AppReducer.reduce(state, AppAction.ConfirmLong).state.currentScreen)
    }

    @Test fun optionsMenuTogglesShuffleAndRepeat() {
        assertEquals(AppEffect.ToggleShuffle, optionsReductionFor("shuffle").effects.single())
        assertEquals(AppEffect.CycleRepeat, optionsReductionFor("repeat").effects.single())
        assertEquals(AppEffect.CycleSleepTimer, optionsReductionFor("sleep_timer").effects.single())
    }

    @Test fun optionsMenuTogglesFavoriteOfTheCurrentTrack() {
        assertEquals(AppEffect.ToggleFavorite(1), optionsReductionFor("np_favorite:1").effects.single())
    }

    @Test fun optionsMenuNavigatesToAlbumAndArtist() {
        assertEquals(Screen.AlbumSongs("Album"), optionsReductionFor("np_album").state.currentScreen)
        assertEquals(Screen.ArtistSongs("Artist"), optionsReductionFor("np_artist").state.currentScreen)
    }

    @Test fun queueOpensFocusedOnTheCurrentTrackFromPlaybackOptions() {
        val base = optionsState().let {
            it.copy(playback = it.playback.copy(queue = listOf(7L, 8L, 1L), currentQueueIndex = 2))
        }
        val index = ScreenContent.rows(base).indexOfFirst { (it as? ScreenRow.Action)?.key == "queue" }
        val selected = base.copy(screenStack = base.screenStack.dropLast(1) + base.currentEntry.copy(selectedIndex = index))
        val result = AppReducer.reduce(selected, AppAction.Confirm).state
        assertEquals(Screen.Queue, result.currentScreen)
        assertEquals(2, result.selectedIndex)
    }

    @Test fun shuffleAllStartsPlaybackAndOpensThePlayer() {
        val state = AppState(library = LibraryState(tracks = listOf(track)))
        val index = ScreenContent.rows(state).indexOfFirst { (it as? ScreenRow.Action)?.key == "shuffle_all" }
        val selected = state.copy(screenStack = listOf(ScreenEntry(Screen.MainMenu, index)))
        val result = AppReducer.reduce(selected, AppAction.Confirm)
        assertEquals(Screen.NowPlaying, result.state.currentScreen)
        assertEquals(AppEffect.ShuffleAll, result.effects.single())
    }

    @Test fun rightOnHomeOpensThePlayerOnlyWhenATrackIsLoaded() {
        val idle = AppState(library = LibraryState(tracks = listOf(track)))
        assertEquals(Screen.MainMenu, AppReducer.reduce(idle, AppAction.Right).state.currentScreen)
        val playing = idle.copy(playback = PlaybackSnapshot(currentTrackId = 1, queue = listOf(1L), currentQueueIndex = 0))
        assertEquals(Screen.NowPlaying, AppReducer.reduce(playing, AppAction.Right).state.currentScreen)
    }

    @Test fun playlistsScreenLeadsWithFavoritesAndRecentlyPlayed() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Playlists)),
            library = LibraryState(tracks = listOf(track))
        )
        val rows = ScreenContent.rows(state)
        assertEquals("playlist_favorites", (rows[0] as ScreenRow.Action).key)
        assertEquals("playlist_recent", (rows[1] as ScreenRow.Action).key)
        assertEquals(Screen.Favorites, AppReducer.reduce(state, AppAction.Confirm).state.currentScreen)
    }

    @Test fun settingsGroupsMaintenanceUnderSystem() {
        val settings = AppState(screenStack = listOf(ScreenEntry(Screen.Settings)))
        val rows = ScreenContent.rows(settings)
        assertEquals(6, rows.size)
        val systemIndex = rows.indexOfFirst { (it as? ScreenRow.Action)?.key == "system" }
        val selected = settings.copy(screenStack = listOf(ScreenEntry(Screen.Settings, systemIndex)))
        val system = AppReducer.reduce(selected, AppAction.Confirm).state
        assertEquals(Screen.System, system.currentScreen)
        val diagIndex = ScreenContent.rows(system).indexOfFirst { (it as? ScreenRow.Action)?.key == "diagnostics" }
        val diagSelected = system.copy(screenStack = system.screenStack.dropLast(1) + system.currentEntry.copy(selectedIndex = diagIndex))
        assertEquals(Screen.Diagnostics, AppReducer.reduce(diagSelected, AppAction.Confirm).state.currentScreen)
    }

    @Test fun playingNextLabelReflectsRepeatOneAndQueueOrder() {
        val base = optionsState()
        val repeatOne = base.copy(playback = base.playback.copy(repeatMode = RepeatMode.ONE))
        val nextRow = ScreenContent.rows(repeatOne).first { (it as? ScreenRow.Group)?.key == "np_next" }
        assertTrue(nextRow.subtitle!!.contains("repeat one"))

        val linear = base.copy(
            playback = base.playback.copy(queue = listOf(1L, 1L), currentQueueIndex = 0)
        )
        val linearRow = ScreenContent.rows(linear).first { (it as? ScreenRow.Group)?.key == "np_next" }
        assertEquals("Song", linearRow.subtitle)
    }
}
