package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.input.HapticLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppReducerTest {
    /**
     * The row cache is process-wide, and the artist and album screens are cached
     * screens, so one test's rows could otherwise satisfy another's lookup.
     */
    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

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

    /**
     * Pressing the row that is already playing should show it, not start it over.
     * Restarting cost the listening position, and rebuilding the queue from the
     * visible list discarded the album or queue order being played.
     */
    @Test fun confirmingTheAlreadyPlayingTrackOnlyOpensNowPlaying() {
        val second = track.copy(id = 2, title = "Second")
        val state = selectTrack(
            AppState(
                screenStack = listOf(ScreenEntry(Screen.Songs)),
                library = LibraryState(tracks = listOf(track, second)),
                playback = PlaybackSnapshot(
                    status = PlaybackStatus.PLAYING,
                    currentTrackId = 2L,
                    positionMs = 42_000,
                    // A queue from somewhere else entirely: it must survive the press.
                    queue = listOf(9L, 2L, 7L),
                    currentQueueIndex = 1
                )
            ),
            trackId = 2L
        )
        val result = AppReducer.reduce(state, AppAction.Confirm)

        assertEquals(Screen.NowPlaying, result.state.currentScreen)
        assertTrue("The press must not restart or requeue", result.effects.isEmpty())
        assertEquals(listOf(9L, 2L, 7L), result.state.playback.queue)
    }

    @Test fun confirmingTheCurrentTrackWhilePausedDoesNotRestartOrResume() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Songs)),
            library = LibraryState(tracks = listOf(track)),
            playback = PlaybackSnapshot(status = PlaybackStatus.PAUSED, currentTrackId = 1L, positionMs = 5_000)
        )
        val result = AppReducer.reduce(state, AppAction.Confirm)

        assertEquals(Screen.NowPlaying, result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    /**
     * Idle means the row is only a leftover of what played last — after a reboot,
     * for instance — so the press has to start it in the normal way.
     */
    @Test fun confirmingTheLastPlayedTrackWhileIdleStillStartsIt() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Songs)),
            library = LibraryState(tracks = listOf(track)),
            playback = PlaybackSnapshot(status = PlaybackStatus.IDLE, currentTrackId = 1L)
        )
        val effect = AppReducer.reduce(state, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertEquals(listOf(1L), effect.trackIds)
    }

    @Test fun confirmingADifferentTrackStillPlaysIt() {
        val second = track.copy(id = 2, title = "Second")
        val state = selectTrack(
            AppState(
                screenStack = listOf(ScreenEntry(Screen.Songs)),
                library = LibraryState(tracks = listOf(track, second)),
                playback = PlaybackSnapshot(status = PlaybackStatus.PLAYING, currentTrackId = 1L)
            ),
            trackId = 2L
        )
        val effect = AppReducer.reduce(state, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertEquals(2L, effect.trackIds[effect.startIndex])
    }

    @Test fun confirmingTheCurrentQueueRowOnlyOpensNowPlaying() {
        val second = track.copy(id = 2, title = "Second")
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Queue, selectedIndex = 1)),
            library = LibraryState(tracks = listOf(track, second)),
            playback = PlaybackSnapshot(
                status = PlaybackStatus.PLAYING,
                currentTrackId = 2L,
                queue = listOf(1L, 2L),
                currentQueueIndex = 1
            )
        )
        val result = AppReducer.reduce(state, AppAction.Confirm)

        assertEquals(Screen.NowPlaying, result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    /**
     * The same track can appear in the queue twice, so the queue screen compares
     * positions. Pressing the *other* copy has to move playback to it.
     */
    @Test fun confirmingADuplicateQueueRowPlaysThatPosition() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Queue, selectedIndex = 1)),
            library = LibraryState(tracks = listOf(track)),
            playback = PlaybackSnapshot(
                status = PlaybackStatus.PLAYING,
                currentTrackId = 1L,
                queue = listOf(1L, 1L),
                currentQueueIndex = 0
            )
        )
        val effect = AppReducer.reduce(state, AppAction.Confirm).effects.single() as AppEffect.PlayQueueIndex
        assertEquals(1, effect.index)
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
        // Selected by key rather than left at index 0: this used to depend on
        // Playback happening to be the first row, so reordering the menu broke it
        // without saying anything about the behaviour under test.
        val state = selectKey(
            AppState(
                screenStack = listOf(ScreenEntry(Screen.Settings)),
                playback = com.schulzcode.y2player.core.model.PlaybackSnapshot(currentTrackId = 1L)
            ),
            "playback"
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

        val settings = selectKey(
            AppState(screenStack = listOf(ScreenEntry(Screen.Settings)), playback = loaded),
            "sound"
        )
        assertEquals(Screen.SoundSettings, AppReducer.reduce(settings, AppAction.Right).state.currentScreen)

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
        val playback = AppState(screenStack = listOf(ScreenEntry(Screen.Settings)))
        val soundIndex = ScreenContent.rows(playback).indexOfFirst { (it as? ScreenRow.Action)?.key == "sound" }
        val sound = AppReducer.reduce(
            playback.copy(screenStack = listOf(ScreenEntry(Screen.Settings, soundIndex))),
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

    /**
     * The Theme row lives on the Display screen, so `confirmDisplay` is the handler
     * that has to answer for it — putting it on the Settings handler instead leaves
     * a row that renders and does nothing when pressed.
     */
    @Test fun theThemeRowOnTheDisplayScreenTogglesTheTheme() {
        val state = selectKey(AppState(screenStack = listOf(ScreenEntry(Screen.Display))), "theme")
        assertEquals(
            listOf(AppEffect.ToggleLightTheme),
            AppReducer.reduce(state, AppAction.Confirm).effects
        )
    }

    @Test fun theThemeRowReportsWhichThemeIsActive() {
        val dark = AppState(screenStack = listOf(ScreenEntry(Screen.Display)))
        val light = dark.copy(preferences = dark.preferences.copy(lightTheme = true))
        assertEquals("Dark", themeRowSubtitle(dark))
        assertEquals("Light", themeRowSubtitle(light))
    }

    private fun themeRowSubtitle(state: AppState): String? =
        ScreenContent.rows(state).filterIsInstance<ScreenRow.Action>()
            .first { it.key == "theme" }.subtitle

    /**
     * Haptics and UI sounds are feedback for input, so they live on their own screen
     * rather than under Display, which is about the panel. These pin the move: the
     * rows work in their new home and are gone from the old one.
     */
    @Test fun controlsScreenOwnsHapticsAndUiSounds() {
        val settings = selectKey(AppState(screenStack = listOf(ScreenEntry(Screen.Settings))), "controls")
        val controls = AppReducer.reduce(settings, AppAction.Confirm).state
        assertEquals(Screen.Controls, controls.currentScreen)

        val withMotor = controls.copy(device = controls.device.copy(hapticsAvailable = true))
        assertEquals(
            listOf(AppEffect.ToggleUiSoundEffects),
            AppReducer.reduce(selectKey(withMotor, "ui_sounds"), AppAction.Confirm).effects
        )
        assertEquals(
            listOf(AppEffect.CycleHapticLevel),
            AppReducer.reduce(selectKey(withMotor, "haptics"), AppAction.Confirm).effects
        )
    }

    // ------------------------------------------------ Artists → albums → songs

    /** Two artists sharing an album name, which is what makes the scoping matter. */
    private val artistLibrary = LibraryState(
        tracks = listOf(
            track.copy(id = 1, title = "Aria", artist = "Bowie", album = "Hunky Dory"),
            track.copy(id = 2, title = "Bell", artist = "Bowie", album = "Hunky Dory"),
            track.copy(id = 3, title = "Cell", artist = "Bowie", album = "Greatest Hits"),
            track.copy(id = 4, title = "Dust", artist = "Queen", album = "Greatest Hits")
        )
    )

    private fun atArtists() = AppState(
        screenStack = listOf(ScreenEntry(Screen.Artists)),
        library = artistLibrary
    )

    private fun selectGroup(state: AppState, key: String): AppState {
        val index = ScreenContent.rows(state).indexOfFirst { (it as? ScreenRow.Group)?.key == key }
        require(index >= 0) { "Missing group $key on ${state.currentScreen}" }
        return state.copy(screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index))
    }

    /** Pressing an artist used to jump straight to every track; now it lists albums. */
    @Test fun pressingAnArtistOpensThatArtistsAlbums() {
        val result = AppReducer.reduce(selectGroup(atArtists(), "Bowie"), AppAction.Confirm)
        assertEquals(Screen.ArtistAlbums("Bowie"), result.state.currentScreen)
        assertTrue("navigating must not start playback", result.effects.isEmpty())
    }

    @Test fun theArtistAlbumsScreenListsAlbumsWithAnAllSongsRow() {
        val albums = AppReducer.reduce(selectGroup(atArtists(), "Bowie"), AppAction.Confirm).state
        val rows = ScreenContent.rows(albums)
        assertEquals("All Songs", rows.first().title)
        assertEquals("3 tracks", rows.first().subtitle)
        assertEquals(
            listOf("Greatest Hits", "Hunky Dory"),
            rows.drop(1).map { it.title }
        )
        assertEquals(listOf("1 track", "2 tracks"), rows.drop(1).map { it.subtitle })
        assertEquals("the header names the artist", "Bowie", ScreenContent.title(albums))
    }

    /**
     * The point of the whole change: an album reached through an artist promises
     * only that artist's tracks. Album names are not unique — two artists with a
     * "Greatest Hits" is ordinary — and before this the two merged.
     */
    @Test fun anAlbumReachedThroughAnArtistShowsOnlyThatArtistsTracks() {
        val albums = AppReducer.reduce(selectGroup(atArtists(), "Bowie"), AppAction.Confirm).state
        val songs = AppReducer.reduce(selectGroup(albums, "Greatest Hits"), AppAction.Confirm).state

        assertEquals(Screen.AlbumSongs("Greatest Hits", "Bowie"), songs.currentScreen)
        assertEquals(listOf("Cell"), ScreenContent.rows(songs).map { it.title })
    }

    /**
     * The global Albums list still merges them, deliberately: keying it by artist
     * too would split compilations, which is the case that list exists to show.
     */
    @Test fun theGlobalAlbumsListStillMergesSharedAlbumNames() {
        val albums = AppState(screenStack = listOf(ScreenEntry(Screen.Albums)), library = artistLibrary)
        val songs = AppReducer.reduce(selectGroup(albums, "Greatest Hits"), AppAction.Confirm).state

        assertEquals(Screen.AlbumSongs("Greatest Hits", null), songs.currentScreen)
        assertEquals(listOf("Cell", "Dust"), ScreenContent.rows(songs).map { it.title }.sorted())
    }

    /** The old flat view stays one press away for anyone who preferred it. */
    @Test fun allSongsStillReachesTheFlatArtistList() {
        val albums = AppReducer.reduce(selectGroup(atArtists(), "Bowie"), AppAction.Confirm).state
        val songs = AppReducer.reduce(selectKey(albums, "artist_all_songs"), AppAction.Confirm).state

        assertEquals(Screen.ArtistSongs("Bowie"), songs.currentScreen)
        assertEquals(3, ScreenContent.rows(songs).size)
    }

    /** Back must retrace the new middle step rather than skipping it. */
    @Test fun backFromAnAlbumReturnsToTheArtistsAlbums() {
        val albums = AppReducer.reduce(selectGroup(atArtists(), "Bowie"), AppAction.Confirm).state
        val songs = AppReducer.reduce(selectGroup(albums, "Hunky Dory"), AppAction.Confirm).state
        val back = AppReducer.reduce(songs, AppAction.Back).state
        assertEquals(Screen.ArtistAlbums("Bowie"), back.currentScreen)
        assertEquals(Screen.Artists, AppReducer.reduce(back, AppAction.Back).state.currentScreen)
    }

    /**
     * Balance sits above the effects rows on the Sound screen and must stay reachable
     * when the effect framework is missing, since it is a player gain rather than an
     * AudioEffect — an accessibility setting cannot depend on firmware luck.
     */
    @Test fun balanceIsReachableEvenWithoutAudioEffectSupport() {
        val sound = AppState(screenStack = listOf(ScreenEntry(Screen.SoundSettings)))
        val opened = AppReducer.reduce(selectKey(sound, "balance"), AppAction.Confirm).state
        assertEquals(Screen.Balance, opened.currentScreen)
        assertEquals("Centre · off", soundRowSubtitle(sound, "balance"))
    }

    @Test fun choosingABalanceLevelSetsItAndLeavesTheScreen() {
        val balance = AppState(screenStack = listOf(ScreenEntry(Screen.SoundSettings), ScreenEntry(Screen.Balance)))
        val result = AppReducer.reduce(selectKey(balance, "balance:-40"), AppAction.Confirm)

        assertEquals(listOf(AppEffect.SetBalance(-40)), result.effects)
        assertEquals("choosing a level returns to Sound", Screen.SoundSettings, result.state.currentScreen)
    }

    @Test fun theSoundRowAndTheBalanceScreenAgreeOnTheCurrentValue() {
        val leaning = AppState(
            screenStack = listOf(ScreenEntry(Screen.SoundSettings)),
            preferences = PlayerPreferencesState(balance = -100)
        )
        assertEquals("Left only", soundRowSubtitle(leaning, "balance"))

        val screen = leaning.copy(screenStack = listOf(ScreenEntry(Screen.Balance)))
        val selected = ScreenContent.rows(screen).filterIsInstance<ScreenRow.Action>()
            .filter { it.subtitle == "Selected" }
        assertEquals(listOf("Left only"), selected.map { it.title })
    }

    /**
     * Adjustable rows before read-only ones. Three DAC facts used to sit between
     * Balance and the effects toggle, so you scrolled past information to reach a
     * control.
     */
    @Test fun theSoundScreenPutsControlsBeforeInformation() {
        // Effects available, or there are no equalizer/bass/loudness rows to order
        // against — the missing-framework case is covered separately below.
        val withEffects = AppState(
            screenStack = listOf(ScreenEntry(Screen.SoundSettings)),
            playback = PlaybackSnapshot(
                audioEffects = com.schulzcode.y2player.core.model.AudioEffectsState(
                    available = true,
                    equalizerSupported = true,
                    bassBoostSupported = true,
                    loudnessSupported = true
                )
            )
        )
        val keys = soundKeys(withEffects)
        val lastControl = keys.indexOf("loudness")
        val firstFact = keys.indexOf("dac_status")
        assertTrue("expected controls then facts, got $keys", lastControl in 0 until firstFact)
        assertEquals(listOf("audio_quality", "balance"), keys.take(2))
    }

    /**
     * The restructure fixed a second thing: the old version returned early when the
     * effect framework was missing, which hid the DAC and route rows from exactly the
     * firmware whose behaviour they explain.
     */
    @Test fun theDacInformationSurvivesMissingAudioEffectSupport() {
        val noEffects = AppState(
            screenStack = listOf(ScreenEntry(Screen.SoundSettings)),
            playback = PlaybackSnapshot(
                audioEffects = com.schulzcode.y2player.core.model.AudioEffectsState(available = false)
            )
        )
        val keys = soundKeys(noEffects)
        assertTrue("the unavailable notice must still appear", "effects_unavailable" in keys)
        assertTrue("the DAC status must not be hidden with it", "dac_status" in keys)
        assertTrue("balance must stay reachable", "balance" in keys)
    }

    private fun soundKeys(state: AppState): List<String> = ScreenContent.rows(state).map {
        when (it) {
            is ScreenRow.Action -> it.key
            is ScreenRow.Group -> it.key
            else -> ""
        }
    }

    private fun soundRowSubtitle(state: AppState, key: String): String? =
        ScreenContent.rows(state).filterIsInstance<ScreenRow.Action>().first { it.key == key }.subtitle

    @Test fun controlsScreenOwnsTheScreenOffWheelToggle() {
        val controls = AppState(screenStack = listOf(ScreenEntry(Screen.Controls)))
        assertEquals(
            listOf(AppEffect.ToggleLocalKeysWhileScreenOff),
            AppReducer.reduce(selectKey(controls, "screen_off_keys"), AppAction.Confirm).effects
        )
    }

    /**
     * The row has to state the consequence, not just the state. Someone enabling it
     * is giving up the protection that stops a pocket pressing play.
     */
    @Test fun theScreenOffWheelRowStatesWhatItCosts() {
        val controls = AppState(screenStack = listOf(ScreenEntry(Screen.Controls)))
        fun subtitle(state: AppState) = ScreenContent.rows(state)
            .filterIsInstance<ScreenRow.Action>().first { it.key == "screen_off_keys" }.subtitle
        assertEquals("Off · stem controls only", subtitle(controls))
        assertEquals(
            "Active · can act in a pocket",
            subtitle(controls.copy(preferences = controls.preferences.copy(localKeysWhileScreenOff = true)))
        )
    }

    /** Only worth surfacing in the Settings summary when it is on. */
    @Test fun theControlsSummaryMentionsTheScreenOffWheelOnlyWhenEnabled() {
        val base = AppState(screenStack = listOf(ScreenEntry(Screen.Settings)))
        assertFalse(controlsSubtitle(base)!!.contains("screen-off"))
        assertTrue(
            controlsSubtitle(
                base.copy(preferences = base.preferences.copy(localKeysWhileScreenOff = true))
            )!!.endsWith("screen-off wheel")
        )
    }

    @Test fun displayNoLongerCarriesInputFeedbackRows() {
        val display = AppState(
            screenStack = listOf(ScreenEntry(Screen.Display)),
            device = DeviceState(hapticsAvailable = true)
        )
        val keys = ScreenContent.rows(display).filterIsInstance<ScreenRow.Action>().map { it.key }
        assertEquals(listOf("brightness", "theme", "timeout", "keep_screen_on"), keys)
    }

    /** One preference, one home: it was on Playback and Display under two names. */
    @Test fun keepScreenOnLivesOnlyOnTheDisplayScreen() {
        fun keys(screen: Screen) = ScreenContent.rows(AppState(screenStack = listOf(ScreenEntry(screen))))
            .filterIsInstance<ScreenRow.Action>().map { it.key }
        assertTrue("keep_screen_on" in keys(Screen.Display))
        assertFalse("keep_screen_on" in keys(Screen.PlaybackSettings))
    }

    /** The Settings row summarises both values, the way the Display row does. */
    @Test fun theControlsRowSummarisesBothSettings() {
        val base = AppState(
            screenStack = listOf(ScreenEntry(Screen.Settings)),
            device = DeviceState(hapticsAvailable = true)
        )
        assertEquals("Haptics off · Sounds off", controlsSubtitle(base))
        assertEquals(
            "Haptics medium · Sounds on",
            controlsSubtitle(
                base.copy(
                    preferences = base.preferences.copy(
                        hapticLevel = HapticLevel.MEDIUM,
                        uiSoundEffectsEnabled = true
                    )
                )
            )
        )
    }

    /** No motor means no haptics row, so the summary must not promise one. */
    @Test fun theControlsRowOmitsHapticsWithoutAMotor() {
        val noMotor = AppState(screenStack = listOf(ScreenEntry(Screen.Settings)))
        assertEquals("Sounds off", controlsSubtitle(noMotor))
    }

    private fun controlsSubtitle(state: AppState): String? =
        ScreenContent.rows(state).filterIsInstance<ScreenRow.Action>()
            .first { it.key == "controls" }.subtitle

    /** Selects a track by id, so a test does not depend on the screen's sort order. */
    private fun selectTrack(state: AppState, trackId: Long): AppState {
        val index = ScreenContent.rows(state).indexOfFirst { (it as? ScreenRow.TrackRow)?.track?.id == trackId }
        require(index >= 0) { "Missing track $trackId on ${state.currentScreen}" }
        return state.copy(
            screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index)
        )
    }

    private fun selectKey(state: AppState, key: String): AppState {
        val index = ScreenContent.rows(state).indexOfFirst { (it as? ScreenRow.Action)?.key == key }
        require(index >= 0) { "Missing row $key on ${state.currentScreen}" }
        return state.copy(
            screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index)
        )
    }

}
