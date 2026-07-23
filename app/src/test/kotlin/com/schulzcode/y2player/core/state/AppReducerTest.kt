package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReducerTest {
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

    @Test fun mainMenuWrapsSelection() {
        val result = AppReducer.reduce(AppState(), AppAction.WheelCounterClockwise)
        assertEquals(ScreenContent.rows(AppState()).lastIndex, result.state.selectedIndex)
    }

    @Test fun everyMainMenuItemRemainsReachableWithThePhysicalWheel() {
        var state = AppState()
        val reached = mutableSetOf(state.selectedIndex)
        repeat(ScreenContent.rows(state).size - 1) {
            state = AppReducer.reduce(state, AppAction.WheelClockwise).state
            reached += state.selectedIndex
        }
        assertEquals(ScreenContent.rows(state).indices.toSet(), reached)
    }

    @Test fun confirmingSongsNavigatesToSongs() {
        assertEquals(Screen.Songs, AppReducer.reduce(AppState(), AppAction.Confirm).state.currentScreen)
    }

    @Test fun confirmingTrackEmitsPlayCollection() {
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.Songs)), library = LibraryState(tracks = listOf(track)))
        val effect = AppReducer.reduce(state, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertEquals(listOf(1L), effect.trackIds)
        assertEquals(0, effect.startIndex)
    }

    @Test fun albumDetailStartsWithTheFirstTrackAndPlaysTheCollection() {
        val second = track.copy(id = 2, title = "Second", trackNumber = 2)
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.AlbumSongs("Album"))),
            library = LibraryState(tracks = listOf(second, track))
        )
        val firstRow = ScreenContent.rows(state).first() as ScreenRow.TrackRow
        assertEquals(track.id, firstRow.track.id)
        val effect = AppReducer.reduce(state, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertEquals(listOf(1L, 2L), effect.trackIds)
        assertEquals(Screen.NowPlaying, AppReducer.reduce(state, AppAction.Confirm).state.currentScreen)
    }

    @Test fun rightOnTrackOpensTrackOptions() {
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.Songs)), library = LibraryState(tracks = listOf(track)))
        val result = AppReducer.reduce(state, AppAction.Right)
        assertEquals(Screen.TrackOptions(1), result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun libraryRefreshRestoresFocusByTrackIdentityAfterReorder() {
        val second = track.copy(id = 2, title = "B")
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Songs, selectedIndex = 1)),
            library = LibraryState(tracks = listOf(track.copy(title = "A"), second))
        )
        val changed = LibraryState(tracks = listOf(track.copy(title = "Z"), second))
        val result = AppReducer.reduce(state, AppAction.LibraryChanged(changed)).state
        assertEquals(0, result.selectedIndex)
        assertEquals(2L, (ScreenContent.rows(result)[result.selectedIndex] as ScreenRow.TrackRow).track.id)
    }

    @Test fun emptyLibraryAndQueueHavePhysicalCenterActions() {
        val noStorage = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.Songs))
        )
        assertEquals(Screen.Storage, AppReducer.reduce(noStorage, AppAction.Confirm).state.currentScreen)

        val emptyQueue = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.Queue)),
            device = DeviceState(internalStorageAvailable = true)
        )
        assertEquals(Screen.MainMenu, AppReducer.reduce(emptyQueue, AppAction.Confirm).state.currentScreen)
    }

    @Test fun rightOnSettingsEntersTheSelectedChildInsteadOfOpeningNowPlaying() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Settings)),
            playback = com.schulzcode.y2player.core.model.PlaybackSnapshot(currentTrackId = 1L)
        )
        val result = AppReducer.reduce(state, AppAction.Right)
        assertEquals(Screen.PlaybackSettings, result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun rightNavigatesThroughRealChildrenAcrossLibraryAndSettings() {
        val loaded = com.schulzcode.y2player.core.model.PlaybackSnapshot(currentTrackId = 1L)
        val albums = AppState(
            screenStack = listOf(ScreenEntry(Screen.Albums)),
            library = LibraryState(tracks = listOf(track)),
            playback = loaded
        )
        assertEquals(Screen.AlbumSongs("Album"), AppReducer.reduce(albums, AppAction.Right).state.currentScreen)

        val playlists = AppState(
            screenStack = listOf(ScreenEntry(Screen.Playlists)),
            library = LibraryState(tracks = listOf(track)),
            playback = loaded
        )
        assertEquals(Screen.Favorites, AppReducer.reduce(playlists, AppAction.Right).state.currentScreen)

        val playbackSettings = selectKey(
            AppState(screenStack = listOf(ScreenEntry(Screen.PlaybackSettings)), playback = loaded),
            "sound"
        )
        assertEquals(Screen.SoundSettings, AppReducer.reduce(playbackSettings, AppAction.Right).state.currentScreen)

        val display = selectKey(
            AppState(screenStack = listOf(ScreenEntry(Screen.Display)), playback = loaded),
            "brightness"
        )
        assertEquals(Screen.Brightness, AppReducer.reduce(display, AppAction.Right).state.currentScreen)
    }

    @Test fun rightOnEveryNestedLeafStaysPutEvenWhenATrackIsLoaded() {
        val loaded = com.schulzcode.y2player.core.model.PlaybackSnapshot(currentTrackId = 1L, queue = listOf(1L))
        val baseLibrary = LibraryState(tracks = listOf(track))
        val states = listOf(
            selectKey(AppState(screenStack = listOf(ScreenEntry(Screen.PlaybackSettings)), playback = loaded), "shuffle"),
            selectKey(AppState(screenStack = listOf(ScreenEntry(Screen.SoundSettings)), playback = loaded), "audio_quality"),
            AppState(screenStack = listOf(ScreenEntry(Screen.SortOrder)), playback = loaded),
            AppState(screenStack = listOf(ScreenEntry(Screen.Bluetooth)), playback = loaded),
            selectKey(AppState(screenStack = listOf(ScreenEntry(Screen.Display)), playback = loaded), "keep_screen_on"),
            AppState(screenStack = listOf(ScreenEntry(Screen.Brightness)), playback = loaded),
            AppState(screenStack = listOf(ScreenEntry(Screen.ScreenTimeout)), playback = loaded),
            selectKey(AppState(screenStack = listOf(ScreenEntry(Screen.Storage)), playback = loaded), "rescan"),
            selectKey(AppState(screenStack = listOf(ScreenEntry(Screen.System)), playback = loaded), "android_settings"),
            selectKey(AppState(screenStack = listOf(ScreenEntry(Screen.Diagnostics)), playback = loaded), "diag_export"),
            AppState(screenStack = listOf(ScreenEntry(Screen.About)), playback = loaded),
            AppState(screenStack = listOf(ScreenEntry(Screen.TrackOptions(1))), library = baseLibrary, playback = loaded),
            AppState(screenStack = listOf(ScreenEntry(Screen.AddToPlaylist(1))), library = baseLibrary, playback = loaded),
            AppState(screenStack = listOf(ScreenEntry(Screen.QueueOptions(0))), library = baseLibrary, playback = loaded),
            AppState(screenStack = listOf(ScreenEntry(Screen.NowPlayingOptions)), library = baseLibrary, playback = loaded)
        )

        states.forEach { state ->
            val result = AppReducer.reduce(state, AppAction.Right)
            assertEquals("Right left ${state.currentScreen}", state.screenStack, result.state.screenStack)
            assertTrue("Right activated ${state.currentScreen}", result.effects.isEmpty())
        }
    }

    @Test fun favoriteMenuUsesFavoriteCollection() {
        val favorite = track.copy(favorite = true)
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.Favorites)), library = LibraryState(tracks = listOf(favorite)))
        assertEquals(listOf(1L), (AppReducer.reduce(state, AppAction.Confirm).effects.single() as AppEffect.PlayCollection).trackIds)
    }

    @Test fun playlistNavigationWorks() {
        val library = LibraryState(
            tracks = listOf(track),
            playlists = listOf(PlaylistSummary(5, "Playlist 1", 1)),
            playlistTrackIds = mapOf(5L to listOf(1L))
        )
        // Index 2: Favorites and Recently Played smart playlists occupy rows 0 and 1.
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.Playlists, 2)), library = library)
        assertEquals(Screen.PlaylistTracks(5, "Playlist 1"), AppReducer.reduce(state, AppAction.Confirm).state.currentScreen)
    }

    @Test fun queueRightOpensQueueOptions() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Queue)),
            library = LibraryState(tracks = listOf(track)),
            playback = com.schulzcode.y2player.core.model.PlaybackSnapshot(queue = listOf(1L), currentQueueIndex = 0)
        )
        assertEquals(Screen.QueueOptions(0), AppReducer.reduce(state, AppAction.Right).state.currentScreen)
    }

    @Test fun selectingBrightnessEmitsInternalSettingEffect() {
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.Display), ScreenEntry(Screen.Brightness, 4)))
        val result = AppReducer.reduce(state, AppAction.Confirm)
        assertEquals(Screen.Display, result.state.currentScreen)
        assertEquals(AppEffect.SetBrightness(50), result.effects.single())
    }
    @Test fun playlistTrackOptionsCanRemoveFromSourcePlaylist() {
        val library = LibraryState(
            tracks = listOf(track),
            playlists = listOf(PlaylistSummary(5, "Playlist 1", 1)),
            playlistTrackIds = mapOf(5L to listOf(1L))
        )
        val source = AppState(
            screenStack = listOf(ScreenEntry(Screen.PlaylistTracks(5, "Playlist 1"))),
            library = library
        )
        val options = AppReducer.reduce(source, AppAction.Right).state
        assertEquals(Screen.TrackOptions(1, 5), options.currentScreen)
        val removeIndex = ScreenContent.rows(options).indexOfFirst { (it as? ScreenRow.Action)?.key?.startsWith("track_remove_playlist:") == true }
        val result = AppReducer.reduce(options.copy(screenStack = options.screenStack.dropLast(1) + options.currentEntry.copy(selectedIndex = removeIndex)), AppAction.Confirm)
        assertEquals(AppEffect.RemoveTrackFromPlaylist(5, 1), result.effects.single())
    }

    @Test fun nestedFolderBackPopsTheExistingStackWithoutDuplicatingParents() {
        val state = AppState(
            screenStack = listOf(
                ScreenEntry(Screen.MainMenu),
                ScreenEntry(Screen.Folders()),
                ScreenEntry(Screen.Folders("internal", "Music")),
                ScreenEntry(Screen.Folders("internal", "Music/Album"))
            )
        )

        val result = AppReducer.reduce(state, AppAction.Back).state

        assertEquals(3, result.screenStack.size)
        assertEquals(Screen.Folders("internal", "Music"), result.currentScreen)
    }

    @Test fun queueOptionsInitiallyFocusTheFirstActionableRow() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Queue)),
            playback = com.schulzcode.y2player.core.model.PlaybackSnapshot(queue = listOf(1L), currentQueueIndex = 0)
        )

        val result = AppReducer.reduce(state, AppAction.Right).state

        assertEquals(Screen.QueueOptions(0), result.currentScreen)
        assertEquals(1, result.selectedIndex)
    }

    @Test fun playbackQueueShrinkNormalizesAStaleQueueSelection() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Queue, selectedIndex = 4)),
            playback = com.schulzcode.y2player.core.model.PlaybackSnapshot(
                queue = listOf(1L, 2L, 3L, 4L, 5L),
                currentQueueIndex = 4
            )
        )

        val result = AppReducer.reduce(
            state,
            AppAction.PlaybackChanged(com.schulzcode.y2player.core.model.PlaybackSnapshot())
        ).state

        assertEquals(0, result.selectedIndex)
    }

    @Test fun invalidQueueOptionsScreenIsClosedWhenTheQueueChanges() {
        val state = AppState(
            screenStack = listOf(
                ScreenEntry(Screen.Queue),
                ScreenEntry(Screen.QueueOptions(3), selectedIndex = 2)
            ),
            playback = com.schulzcode.y2player.core.model.PlaybackSnapshot(
                queue = listOf(1L, 2L, 3L, 4L),
                currentQueueIndex = 3
            )
        )

        val result = AppReducer.reduce(
            state,
            AppAction.PlaybackChanged(
                com.schulzcode.y2player.core.model.PlaybackSnapshot(queue = listOf(1L), currentQueueIndex = 0)
            )
        ).state

        assertEquals(Screen.Queue, result.currentScreen)
        assertEquals(0, result.selectedIndex)
    }

    @Test fun playbackSettingsExposeGaplessCrossfadeAndSleepActions() {
        val base = AppState(screenStack = listOf(ScreenEntry(Screen.PlaybackSettings)))
        fun effectFor(key: String): AppEffect {
            val rows = ScreenContent.rows(base)
            val index = rows.indexOfFirst { (it as? ScreenRow.Action)?.key == key }
            return AppReducer.reduce(
                base.copy(screenStack = listOf(ScreenEntry(Screen.PlaybackSettings, index))),
                AppAction.Confirm
            ).effects.single()
        }

        assertEquals(AppEffect.ToggleGapless, effectFor("gapless"))
        assertEquals(AppEffect.CycleCrossfade, effectFor("crossfade"))
        assertEquals(AppEffect.CycleSleepTimer, effectFor("sleep_timer"))
        assertEquals(AppEffect.CycleVolumeMode, effectFor("volume_mode"))
    }

    /**
     * The volume row has to state the current level, otherwise a user who left
     * the player attenuated has no way to discover why it is quiet.
     */
    @Test fun volumeRowReportsModeAndLevel() {
        val base = AppState(screenStack = listOf(ScreenEntry(Screen.PlaybackSettings)))
        fun subtitleOf(state: AppState): String? = ScreenContent.rows(state)
            .filterIsInstance<ScreenRow.Action>().first { it.key == "volume_mode" }.subtitle

        assertTrue(subtitleOf(base)!!.startsWith("System"))
        val perceptual = base.copy(
            preferences = base.preferences.copy(
                volumeMode = com.schulzcode.y2player.playback.VolumeMode.PERCEPTUAL,
                volumeLevel = com.schulzcode.y2player.playback.VolumeCurve.STEPS / 2
            )
        )
        assertEquals("In-app · 50%", subtitleOf(perceptual))
    }

    @Test fun soundSettingsNavigateToDeviceEqualizerBands() {
        val playback = AppState(screenStack = listOf(ScreenEntry(Screen.PlaybackSettings)))
        val soundIndex = ScreenContent.rows(playback).indexOfFirst { (it as? ScreenRow.Action)?.key == "sound" }
        val sound = AppReducer.reduce(
            playback.copy(screenStack = listOf(ScreenEntry(Screen.PlaybackSettings, soundIndex))),
            AppAction.Confirm
        ).state.copy(
            playback = com.schulzcode.y2player.core.model.PlaybackSnapshot(
                audioEffects = com.schulzcode.y2player.core.model.AudioEffectsState(
                    available = true,
                    equalizerSupported = true,
                    bandFrequenciesHz = listOf(60, 230, 910),
                    bandLevelsMb = listOf(0, 0, 0)
                )
            )
        )

        assertEquals(Screen.SoundSettings, sound.currentScreen)
        val bandIndex = ScreenContent.rows(sound).indexOfFirst { (it as? ScreenRow.Action)?.key == "eq_bands" }
        val bands = AppReducer.reduce(
            sound.copy(screenStack = sound.screenStack.dropLast(1) + sound.currentEntry.copy(selectedIndex = bandIndex)),
            AppAction.Confirm
        ).state
        assertEquals(Screen.EqualizerBands, bands.currentScreen)
        assertEquals(AppEffect.AdjustEqualizerBand(0, -1), AppReducer.reduce(bands, AppAction.Left).effects.single())
        assertEquals(AppEffect.AdjustEqualizerBand(0, 1), AppReducer.reduce(bands, AppAction.Right).effects.single())
    }

    @Test fun nowPlayingSeekUsesConfiguredStep() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.NowPlaying)),
            preferences = PlayerPreferencesState(seekStepMs = 30_000)
        )
        assertEquals(AppEffect.SeekBy(-30_000), AppReducer.reduce(state, AppAction.SeekBackward).effects.single())
        assertEquals(AppEffect.SeekBy(30_000), AppReducer.reduce(state, AppAction.SeekForward).effects.single())
        val longState = state.copy(preferences = state.preferences.copy(longSeekStepMs = 60_000))
        assertEquals(AppEffect.SeekBy(-60_000), AppReducer.reduce(longState, AppAction.SeekBackwardLong).effects.single())
        assertEquals(AppEffect.SeekBy(60_000), AppReducer.reduce(longState, AppAction.SeekForwardLong).effects.single())
    }

    private fun selectKey(state: AppState, key: String): AppState {
        val index = ScreenContent.rows(state).indexOfFirst { (it as? ScreenRow.Action)?.key == key }
        require(index >= 0) { "Missing row $key on ${state.currentScreen}" }
        return state.copy(
            screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index)
        )
    }

}
