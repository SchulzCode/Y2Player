package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AudioQualityMode
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.input.HapticLevel
import com.schulzcode.y2player.playback.CrossfadeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppReducerTest {
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
        val result = AppReducer.reduce(AppState(), AppAction.WheelMoved(-1))
        assertEquals(ScreenContent.rows(AppState()).lastIndex, result.state.selectedIndex)
    }

    @Test fun mainMenuStopsAtTheBoundaryWhenWrappingIsDisabled() {
        val state = AppState(preferences = PlayerPreferencesState(wrapLists = false))
        val result = AppReducer.reduce(state, AppAction.WheelMoved(-1))
        assertEquals(0, result.state.selectedIndex)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun acceleratedMovementClampsAtTheBoundaryWhenWrappingIsDisabled() {
        val rowCount = ScreenContent.rows(AppState()).size
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu, rowCount - 2)),
            preferences = PlayerPreferencesState(wrapLists = false)
        )
        assertEquals(rowCount - 1, AppReducer.reduce(state, AppAction.WheelMoved(5)).state.selectedIndex)
    }

    @Test fun confirmationsRemainBoundedEvenWhenOrdinaryListsWrap() {
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.ConfirmAction("reset_library"), 1)))
        assertEquals(1, AppReducer.reduce(state, AppAction.WheelMoved(-1)).state.selectedIndex)
        val onConfirm = state.copy(screenStack = listOf(ScreenEntry(state.currentScreen, 2)))
        assertEquals(2, AppReducer.reduce(onConfirm, AppAction.WheelMoved(1)).state.selectedIndex)
    }

    @Test fun everyMainMenuItemRemainsReachableWithThePhysicalWheel() {
        var state = AppState()
        val reached = mutableSetOf(state.selectedIndex)
        repeat(ScreenContent.rows(state).size - 1) {
            state = AppReducer.reduce(state, AppAction.WheelMoved(1)).state
            reached += state.selectedIndex
        }
        assertEquals(ScreenContent.rows(state).indices.toSet(), reached)
    }

    @Test fun confirmingMusicThenSongsNavigatesToSongs() {
        val music = AppReducer.reduce(AppState(), AppAction.Confirm).state
        assertEquals(Screen.Music, music.currentScreen)
        assertEquals(Screen.Songs, AppReducer.reduce(selectKey(music, "songs"), AppAction.Confirm).state.currentScreen)
    }

    @Test fun confirmingTrackEmitsPlayCollection() {
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.Songs)), library = LibraryState(tracks = listOf(track)))
        val effect = AppReducer.reduce(state, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertEquals(listOf(1L), effect.trackIds)
        assertEquals(0, effect.startIndex)
    }

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
                    queue = testQueue(9L, 2L, 7L),
                    currentQueueEntryId = 2L
                )
            ),
            trackId = 2L
        )
        val result = AppReducer.reduce(state, AppAction.Confirm)

        assertEquals(Screen.NowPlaying, result.state.currentScreen)
        assertTrue("The press must not restart or requeue", result.effects.isEmpty())
        assertEquals(listOf(9L, 2L, 7L), result.state.playback.queue.map { it.trackId })
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

    @Test fun confirmingAQueueRowOpensItsCircularActions() {
        val second = track.copy(id = 2, title = "Second")
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Queue, selectedIndex = 2)),
            library = LibraryState(tracks = listOf(track, second)),
            playback = PlaybackSnapshot(
                status = PlaybackStatus.PLAYING,
                currentTrackId = 2L,
                queue = testQueue(1L, 2L),
                currentQueueEntryId = 2L
            )
        )
        val result = AppReducer.reduce(state, AppAction.Confirm)

        assertEquals(Screen.QueueOptions(2L), result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun confirmingADuplicateQueueRowPlaysThatPosition() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Queue, selectedIndex = 2)),
            library = LibraryState(tracks = listOf(track)),
            playback = PlaybackSnapshot(
                status = PlaybackStatus.PLAYING,
                currentTrackId = 1L,
                queue = testQueue(1L, 1L),
                currentQueueEntryId = 1L
            )
        )
        val options = AppReducer.reduce(state, AppAction.Confirm).state
        assertEquals(Screen.QueueOptions(2L), options.currentScreen)
        val effect = AppReducer.reduce(selectKey(options, "queue_play:2"), AppAction.Confirm)
            .effects.single() as AppEffect.PlayQueueEntry
        assertEquals(2L, effect.entryId)
    }

    @Test fun albumDetailStartsWithTheFirstTrackAndPlaysTheCollection() {
        val second = track.copy(id = 2, title = "Second", trackNumber = 2)
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.AlbumSongs("Album"))),
            library = LibraryState(tracks = listOf(second, track))
        )
        val onFirstTrack = selectFirstTrackRow(state)
        val firstRow = ScreenContent.rows(onFirstTrack)[onFirstTrack.selectedIndex] as ScreenRow.TrackRow
        assertEquals(track.id, firstRow.track.id)
        val effect = AppReducer.reduce(onFirstTrack, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertEquals(listOf(1L, 2L), effect.trackIds)
        assertEquals(0, effect.startIndex)
        assertEquals(Screen.NowPlaying, AppReducer.reduce(onFirstTrack, AppAction.Confirm).state.currentScreen)
    }

    @Test fun albumOffersShuffleAsTheFirstRow() {
        val second = track.copy(id = 2, title = "Second", trackNumber = 2)
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.AlbumSongs("Album"))),
            library = LibraryState(tracks = listOf(second, track))
        )
        assertEquals(ScreenContent.COLLECTION_SHUFFLE_KEY, (ScreenContent.rows(state).first() as ScreenRow.Action).key)

        val effect = AppReducer.reduce(state, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertTrue(effect.shuffled)
        assertEquals("every album track goes into the shuffle", setOf(1L, 2L), effect.trackIds.toSet())
    }

    @Test fun aSingleTrackAlbumHasNoShuffleRow() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.AlbumSongs("Album"))),
            library = LibraryState(tracks = listOf(track))
        )
        assertEquals(1, ScreenContent.rows(state).size)
        assertTrue(ScreenContent.rows(state).single() is ScreenRow.TrackRow)
    }

    @Test fun rightOnTheAlbumShuffleRowSkipsToTheNextTrack() {
        val second = track.copy(id = 2, title = "Second", trackNumber = 2)
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.AlbumSongs("Album"))),
            library = LibraryState(tracks = listOf(second, track))
        )
        val result = AppReducer.reduce(state, AppAction.Right)
        assertEquals(state.screenStack, result.state.screenStack)
        assertEquals(AppEffect.NextTrack, result.effects.single())
    }

    @Test fun longCenterOnTrackOpensTrackOptions() {
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.Songs)), library = LibraryState(tracks = listOf(track)))
        val result = AppReducer.reduce(state, AppAction.ConfirmLong)
        assertEquals(Screen.TrackOptions(1), result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun libraryRefreshRestoresFocusByTrackIdentityAfterReorder() {
        val second = track.copy(id = 2, title = "B")
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Songs, selectedIndex = 2)),
            library = LibraryState(tracks = listOf(track.copy(title = "A"), second))
        )
        val changed = LibraryState(tracks = listOf(track.copy(title = "Z"), second))
        val result = AppReducer.reduce(state, AppAction.LibraryChanged(changed)).state
        assertEquals(1, result.selectedIndex)
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

    @Test fun centerOnAudioEntersTheSelectedChild() {
        val state = selectKey(
            AppState(
                screenStack = listOf(ScreenEntry(Screen.Audio)),
                playback = com.schulzcode.y2player.core.model.PlaybackSnapshot(currentTrackId = 1L)
            ),
            "playback_transitions"
        )
        val result = AppReducer.reduce(state, AppAction.Confirm)
        assertEquals(Screen.PlaybackTransitions, result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun centerNavigatesThroughRealChildrenAcrossLibraryAndSettings() {
        val loaded = com.schulzcode.y2player.core.model.PlaybackSnapshot(currentTrackId = 1L)
        val albums = AppState(
            screenStack = listOf(ScreenEntry(Screen.Albums)),
            library = LibraryState(tracks = listOf(track)),
            playback = loaded
        )
        assertEquals(Screen.AlbumSongs("Album"), AppReducer.reduce(albums, AppAction.Confirm).state.currentScreen)

        val music = AppState(
            screenStack = listOf(ScreenEntry(Screen.Music)),
            library = LibraryState(tracks = listOf(track)),
            playback = loaded
        )
        val favouritesIndex = ScreenContent.rows(music).indexOfFirst { (it as? ScreenRow.Action)?.key == "favorites" }
        val onFavourites = music.copy(screenStack = listOf(ScreenEntry(Screen.Music, favouritesIndex)))
        assertEquals(Screen.Favorites, AppReducer.reduce(onFavourites, AppAction.Confirm).state.currentScreen)

        val settings = selectKey(
            AppState(screenStack = listOf(ScreenEntry(Screen.Audio)), playback = loaded),
            "sound_effects"
        )
        assertEquals(Screen.SoundEffects, AppReducer.reduce(settings, AppAction.Confirm).state.currentScreen)

        val display = selectKey(
            AppState(screenStack = listOf(ScreenEntry(Screen.Display)), playback = loaded),
            "brightness"
        )
        assertEquals(Screen.Brightness, AppReducer.reduce(display, AppAction.Confirm).state.currentScreen)
    }

    @Test fun favoriteMenuUsesFavoriteCollection() {
        val favorite = track.copy(favorite = true)
        val state = selectFirstTrackRow(
            AppState(screenStack = listOf(ScreenEntry(Screen.Favorites)), library = LibraryState(tracks = listOf(favorite)))
        )
        val effect = AppReducer.reduce(state, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertEquals(listOf(1L), effect.trackIds)
        assertFalse("picking a track plays the collection in order", effect.shuffled)
    }

    @Test fun favoritesOfferShuffleAsTheFirstRow() {
        val favorite = track.copy(favorite = true)
        val second = track.copy(id = 2, title = "B", favorite = true)
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Favorites)),
            library = LibraryState(tracks = listOf(favorite, second))
        )
        assertEquals(ScreenContent.COLLECTION_SHUFFLE_KEY, (ScreenContent.rows(state).first() as ScreenRow.Action).key)

        val effect = AppReducer.reduce(state, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertTrue(effect.shuffled)
        assertEquals("every favourite goes into the shuffle", setOf(1L, 2L), effect.trackIds.toSet())
    }

    @Test fun playlistShuffleRowStartsTheWholePlaylistShuffled() {
        val second = track.copy(id = 2, title = "Second")
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.PlaylistTracks(5, "Playlist 1"))),
            library = LibraryState(
                tracks = listOf(track, second),
                playlists = listOf(PlaylistSummary(5, "Playlist 1", 2)),
                playlistTrackIds = mapOf(5L to listOf(1L, 2L))
            )
        )
        assertEquals(ScreenContent.COLLECTION_SHUFFLE_KEY, (ScreenContent.rows(state).first() as ScreenRow.Action).key)

        val result = AppReducer.reduce(state, AppAction.Confirm)
        val effect = result.effects.single() as AppEffect.PlayCollection
        assertTrue(effect.shuffled)
        assertEquals(listOf(1L, 2L), effect.trackIds)
        assertEquals(Screen.NowPlaying, result.state.currentScreen)
    }

    @Test fun singleTrackPlaylistDoesNotOfferShuffle() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.PlaylistTracks(5, "Playlist 1"))),
            library = LibraryState(
                tracks = listOf(track),
                playlists = listOf(PlaylistSummary(5, "Playlist 1", 1)),
                playlistTrackIds = mapOf(5L to listOf(1L))
            )
        )
        assertTrue(ScreenContent.rows(state).none {
            (it as? ScreenRow.Action)?.key == ScreenContent.COLLECTION_SHUFFLE_KEY
        })
    }

    @Test fun shuffleRowIsAbsentWhenTheCollectionIsEmpty() {
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.Favorites)), library = LibraryState(tracks = listOf(track)))
        assertTrue(ScreenContent.rows(state).none { (it as? ScreenRow.Action)?.key == ScreenContent.COLLECTION_SHUFFLE_KEY })
    }

    private fun loadedAt(screen: Screen) = AppState(
        screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(screen)),
        library = LibraryState(tracks = listOf(track)),
        playback = PlaybackSnapshot(status = PlaybackStatus.PLAYING, currentTrackId = 1L)
    )

    @Test fun holdingPlayOpensNowPlayingFromAnyScreen() {
        for (screen in listOf(Screen.Settings, Screen.Songs, Screen.Display, Screen.Queue)) {
            val result = AppReducer.reduce(loadedAt(screen), AppAction.ShowNowPlaying)
            assertEquals(Screen.NowPlaying, result.state.currentScreen)
            assertTrue("navigation only, never a playback command", result.effects.isEmpty())
        }
    }

    @Test fun holdingPlayKeepsTheWayBack() {
        val result = AppReducer.reduce(loadedAt(Screen.Settings), AppAction.ShowNowPlaying)
        assertEquals(Screen.Settings, AppReducer.reduce(result.state, AppAction.Back).state.currentScreen)
    }

    @Test fun holdingPlayOnNowPlayingDoesNothing() {
        val first = AppReducer.reduce(loadedAt(Screen.Settings), AppAction.ShowNowPlaying).state
        val second = AppReducer.reduce(first, AppAction.ShowNowPlaying)
        assertEquals(first.screenStack.size, second.state.screenStack.size)
        assertEquals(Screen.NowPlaying, second.state.currentScreen)
    }

    @Test fun holdingPlayWithNothingLoadedDoesNothing() {
        val idle = AppState(
            screenStack = listOf(ScreenEntry(Screen.Settings)),
            library = LibraryState(tracks = listOf(track))
        )
        val result = AppReducer.reduce(idle, AppAction.ShowNowPlaying)
        assertEquals(Screen.Settings, result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun holdingPlayDoesNotDisturbPlayback() {
        val before = loadedAt(Screen.Settings)
        val result = AppReducer.reduce(before, AppAction.ShowNowPlaying)
        assertEquals(before.playback, result.state.playback)
    }

    private fun transitionRows(
        crossfadeMs: Int,
        mode: CrossfadeMode = CrossfadeMode.ALWAYS,
        quality: AudioQualityMode = AudioQualityMode.BALANCED
    ) = ScreenContent.rows(
        AppState(
            screenStack = listOf(ScreenEntry(Screen.PlaybackTransitions)),
            preferences = PlayerPreferencesState(
                crossfadeMs = crossfadeMs,
                crossfadeMode = mode,
                audioQualityMode = quality
            )
        )
    )

    @Test fun crossfadeModeRowIsHiddenWhileCrossfadeIsOff() {
        assertTrue(transitionRows(crossfadeMs = 0).none { (it as? ScreenRow.Action)?.key == "crossfade_mode" })
    }

    @Test fun crossfadeModeRowAppearsOnceADurationIsSet() {
        val row = transitionRows(crossfadeMs = 4_000)
            .filterIsInstance<ScreenRow.Action>().single { it.key == "crossfade_mode" }
        assertEquals("Always", row.subtitle)
    }

    @Test fun crossfadeModeRowCyclesTheMode() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.PlaybackTransitions)),
            preferences = PlayerPreferencesState(crossfadeMs = 4_000)
        )
        val effect = AppReducer.reduce(selectKey(state, "crossfade_mode"), AppAction.Confirm).effects.single()
        assertEquals(AppEffect.CycleCrossfadeMode, effect)
    }

    @Test fun crossfadeModeIsInertUnderTheDirectProfile() {
        val rows = transitionRows(crossfadeMs = 4_000, quality = AudioQualityMode.DIRECT_DAC)
        assertTrue(rows.none { (it as? ScreenRow.Action)?.key == "crossfade_mode" })
        val group = rows.filterIsInstance<ScreenRow.Group>().single { it.key == "crossfade_mode_unavailable" }
        assertEquals("Disabled by Direct profile", group.subtitle)
    }

    @Test fun gaplessSubtitleStaysTruthfulInShuffleOnlyMode() {
        fun gaplessSubtitle(crossfadeMs: Int, mode: CrossfadeMode) = transitionRows(crossfadeMs, mode)
            .filterIsInstance<ScreenRow.Action>().single { it.key == "gapless" }.subtitle

        assertEquals("On", gaplessSubtitle(0, CrossfadeMode.ALWAYS))
        assertEquals("Crossfade takes priority", gaplessSubtitle(4_000, CrossfadeMode.ALWAYS))
        assertEquals(
            "Crossfade takes priority while shuffling",
            gaplessSubtitle(4_000, CrossfadeMode.WHILE_SHUFFLING)
        )
    }

    private fun selectFirstTrackRow(state: AppState): AppState {
        val index = ScreenContent.rows(state).indexOfFirst { it is ScreenRow.TrackRow }
        return state.copy(screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index))
    }

    @Test fun playlistNavigationWorks() {
        val library = LibraryState(
            tracks = listOf(track),
            playlists = listOf(PlaylistSummary(5, "Playlist 1", 1)),
            playlistTrackIds = mapOf(5L to listOf(1L))
        )
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.Playlists, 0)), library = library)
        assertEquals(Screen.PlaylistTracks(5, "Playlist 1"), AppReducer.reduce(state, AppAction.Confirm).state.currentScreen)
    }

    @Test fun playbackHistoryOpensAScreenRatherThanClearingImmediately() {
        val library = AppState(screenStack = listOf(ScreenEntry(Screen.LibrarySettings)))
        val opened = AppReducer.reduce(selectKey(library, "playback_history"), AppAction.Confirm)
        assertEquals(Screen.PlaybackHistory, opened.state.currentScreen)
        assertEquals(listOf(AppEffect.RefreshPlaybackHistory), opened.effects)

        val asked = AppReducer.reduce(selectKey(opened.state, "history_clear"), AppAction.Confirm)
        assertEquals(Screen.ConfirmAction(ConfirmPrompts.CLEAR_HISTORY), asked.state.currentScreen)
        assertTrue("clearing must ask first", asked.effects.isEmpty())

        val confirmed = AppReducer.reduce(selectKey(asked.state, ScreenContent.CONFIRM_OK_KEY), AppAction.Confirm)
        assertEquals(listOf(AppEffect.ClearPlaybackHistory), confirmed.effects)
        assertEquals("stays on the history screen", Screen.PlaybackHistory, confirmed.state.currentScreen)
    }

    @Test fun playlistFileMaintenanceLivesUnderLibrarySettings() {
        val playlists = AppState(screenStack = listOf(ScreenEntry(Screen.Playlists)))
        val playlistKeys = ScreenContent.rows(playlists).filterIsInstance<ScreenRow.Action>().map { it.key }
        assertFalse("import is not a browsing action", "playlist_import_m3u" in playlistKeys)
        assertFalse("export is not a browsing action", "playlist_export_m3u" in playlistKeys)

        val library = AppState(screenStack = listOf(ScreenEntry(Screen.LibrarySettings)))
        assertEquals(
            listOf("storage", "sort", "playback_history", "playlist_import_m3u", "playlist_export_m3u"),
            ScreenContent.rows(library).map { (it as ScreenRow.Action).key }
        )
        assertEquals(
            listOf(AppEffect.ImportM3uPlaylists),
            AppReducer.reduce(selectKey(library, "playlist_import_m3u"), AppAction.Confirm).effects
        )
        assertEquals(
            listOf(AppEffect.ExportM3uPlaylists),
            AppReducer.reduce(selectKey(library, "playlist_export_m3u"), AppAction.Confirm).effects
        )
    }

    @Test fun longCenterOnQueueIsTheQuickPlayShortcut() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Queue, selectedIndex = 1)),
            library = LibraryState(tracks = listOf(track)),
            playback = com.schulzcode.y2player.core.model.PlaybackSnapshot(
                status = PlaybackStatus.PLAYING,
                currentTrackId = 1L,
                queue = testQueue(1L),
                currentQueueEntryId = 1L
            )
        )
        val result = AppReducer.reduce(state, AppAction.ConfirmLong)
        assertEquals(Screen.NowPlaying, result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun destructiveQueueActionsLiveUnderQueueManagement() {
        val options = AppState(
            screenStack = listOf(ScreenEntry(Screen.NowPlayingOptions)),
            library = LibraryState(tracks = listOf(track)),
            playback = PlaybackSnapshot(
                queue = testQueue(1L, 1L, 1L).mapIndexed { index, entry ->
                    if (index == 1) entry.copy(origin = com.schulzcode.y2player.core.model.QueueOrigin.UP_NEXT)
                    else entry
                },
                currentQueueEntryId = 1L
            )
        )
        val queueItem = AppState(
            screenStack = listOf(ScreenEntry(Screen.Queue), ScreenEntry(Screen.QueueOptions(1))),
            library = options.library,
            playback = options.playback
        )
        val optionKeys = ScreenContent.rows(queueItem).filterIsInstance<ScreenRow.Action>().map { it.key }
        assertEquals(listOf("queue_play:1", "queue_remove:1"), optionKeys)

        val queue = AppReducer.reduce(selectKey(options, "queue"), AppAction.Confirm).state
        assertEquals(Screen.Queue, queue.currentScreen)
        assertEquals(1, queue.selectedIndex)
        val management = AppReducer.reduce(selectKey(queue, "queue_actions"), AppAction.Confirm).state
        assertEquals(Screen.QueueManagement, management.currentScreen)
        assertEquals(
            listOf("queue_clear_up_next", "queue_clear_remaining", "queue_clear"),
            ScreenContent.rows(management).map { (it as ScreenRow.Action).key }
        )
        assertEquals(Screen.Queue, AppReducer.reduce(management, AppAction.Back).state.currentScreen)

        val asked = AppReducer.reduce(selectKey(management, "queue_clear"), AppAction.Confirm)
        assertEquals(Screen.ConfirmAction(ConfirmPrompts.CLEAR_QUEUE), asked.state.currentScreen)
        assertTrue("clearing the queue must ask first", asked.effects.isEmpty())

        val cleared = AppReducer.reduce(selectKey(asked.state, ScreenContent.CONFIRM_OK_KEY), AppAction.Confirm)
        assertEquals(listOf(AppEffect.ClearQueue), cleared.effects)
        assertEquals(Screen.QueueManagement, cleared.state.currentScreen)

        val cancelled = AppReducer.reduce(selectKey(asked.state, ScreenContent.CONFIRM_CANCEL_KEY), AppAction.Confirm)
        assertTrue("cancelling must not clear", cancelled.effects.isEmpty())
        assertEquals(Screen.QueueManagement, cancelled.state.currentScreen)
    }

    @Test fun deletingAPlaylistAsksFirstAndNamesIt() {
        val library = LibraryState(
            tracks = listOf(track),
            playlists = listOf(PlaylistSummary(5, "Road Trip", 1)),
            playlistTrackIds = mapOf(5L to listOf(1L))
        )
        val tracksScreen = AppState(
            screenStack = listOf(ScreenEntry(Screen.Playlists), ScreenEntry(Screen.PlaylistTracks(5, "Road Trip"))),
            library = library
        )
        val asked = AppReducer.reduce(selectKey(tracksScreen, "playlist_delete:5"), AppAction.Confirm)
        assertEquals(Screen.ConfirmAction(ConfirmPrompts.DELETE_PLAYLIST + "5"), asked.state.currentScreen)
        assertTrue("deleting must ask first", asked.effects.isEmpty())

        val prompt = ScreenContent.rows(asked.state).first() as ScreenRow.Group
        assertTrue("the prompt must name the playlist", prompt.title.contains("Road Trip"))

        val confirmed = AppReducer.reduce(selectKey(asked.state, ScreenContent.CONFIRM_OK_KEY), AppAction.Confirm)
        assertEquals(listOf(AppEffect.DeletePlaylist(5)), confirmed.effects)
        assertEquals("returns to the playlist list", Screen.Playlists, confirmed.state.currentScreen)
    }

    @Test fun trackMetadataLivesBehindTrackDetails() {
        val options = AppState(
            screenStack = listOf(ScreenEntry(Screen.TrackOptions(1))),
            library = LibraryState(tracks = listOf(track))
        )
        val details = AppReducer.reduce(selectKey(options, "track_details:1"), AppAction.Confirm).state
        assertEquals(Screen.TrackDetails(1), details.currentScreen)
        assertEquals(
            listOf("info_artist", "info_album", "info_format", "info_path"),
            ScreenContent.rows(details).map { (it as ScreenRow.Group).key }
        )
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
        val source = selectFirstTrackRow(
            AppState(
                screenStack = listOf(ScreenEntry(Screen.PlaylistTracks(5, "Playlist 1"))),
                library = library
            )
        )
        val options = AppReducer.reduce(source, AppAction.ConfirmLong).state
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
            screenStack = listOf(ScreenEntry(Screen.Queue, selectedIndex = 1)),
            library = LibraryState(tracks = listOf(track)),
            playback = com.schulzcode.y2player.core.model.PlaybackSnapshot(queue = testQueue(1L), currentQueueEntryId = 1L)
        )

        val result = AppReducer.reduce(state, AppAction.Confirm).state

        assertEquals(Screen.QueueOptions(1), result.currentScreen)
        assertEquals(0, result.selectedIndex)
    }

    @Test fun futureQueueItemMenuCanPromoteAndReorderWithoutClosing() {
        val second = track.copy(id = 2, title = "Second")
        val third = track.copy(id = 3, title = "Third")
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Queue, selectedIndex = 3)),
            library = LibraryState(tracks = listOf(track, second, third)),
            playback = PlaybackSnapshot(queue = testQueue(1L, 2L, 3L), currentQueueEntryId = 1L)
        )

        val options = AppReducer.reduce(state, AppAction.Confirm).state
        assertEquals(Screen.QueueOptions(3L), options.currentScreen)
        assertEquals(
            listOf("queue_play:3", "queue_next:3", "queue_move:3", "queue_remove:3"),
            ScreenContent.rows(options).map { (it as ScreenRow.Action).key }
        )

        val promoted = AppReducer.reduce(selectKey(options, "queue_next:3"), AppAction.Confirm)
        assertEquals(Screen.Queue, promoted.state.currentScreen)
        assertEquals(2, promoted.state.selectedIndex)
        assertEquals(AppEffect.PromoteQueueEntry(3L), promoted.effects.single())

        val moving = AppReducer.reduce(selectKey(options, "queue_move:3"), AppAction.Confirm).state
        assertEquals(Screen.QueueMove(3L, 2), moving.currentScreen)
        val preview = AppReducer.reduce(moving, AppAction.WheelMoved(-1)).state
        assertEquals(Screen.QueueMove(3L, 1), preview.currentScreen)
        assertEquals(listOf(1L, 3L, 2L), ScreenContent.rows(preview)
            .map { (it as ScreenRow.TrackRow).track.id })
        assertTrue(AppReducer.reduce(preview, AppAction.Back).effects.isEmpty())
        assertEquals(Screen.QueueOptions(3L), AppReducer.reduce(preview, AppAction.Back).state.currentScreen)

        val moved = AppReducer.reduce(preview, AppAction.Confirm)
        assertEquals(Screen.Queue, moved.state.currentScreen)
        assertEquals(2, moved.state.selectedIndex)
        assertEquals(AppEffect.MoveQueueEntry(3L, -1), moved.effects.single())
    }

    @Test fun playbackQueueShrinkNormalizesAStaleQueueSelection() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Queue, selectedIndex = 4)),
            playback = com.schulzcode.y2player.core.model.PlaybackSnapshot(
                queue = testQueue(1L, 2L, 3L, 4L, 5L),
                currentQueueEntryId = 5L
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
                queue = testQueue(1L, 2L, 3L, 4L),
                currentQueueEntryId = 4L
            )
        )

        val result = AppReducer.reduce(
            state,
            AppAction.PlaybackChanged(
                com.schulzcode.y2player.core.model.PlaybackSnapshot(queue = testQueue(1L), currentQueueEntryId = 1L)
            )
        ).state

        assertEquals(Screen.Queue, result.currentScreen)
        assertEquals(0, result.selectedIndex)
    }

    @Test fun playbackSettingsGroupRelatedPersistentPreferences() {
        val landing = AppState(screenStack = listOf(ScreenEntry(Screen.Audio)))
        assertEquals(
            listOf(
                "sound_effects",
                "playback_volume",
                "playback_transitions",
                "playback_interruptions",
                "output"
            ),
            ScreenContent.rows(landing).map { (it as ScreenRow.Action).key }
        )

        fun effectFor(screen: Screen, key: String): AppEffect {
            val base = AppState(screenStack = listOf(ScreenEntry(screen)))
            val rows = ScreenContent.rows(base)
            val index = rows.indexOfFirst { (it as? ScreenRow.Action)?.key == key }
            return AppReducer.reduce(
                base.copy(screenStack = listOf(ScreenEntry(screen, index))),
                AppAction.Confirm
            ).effects.single()
        }

        assertEquals(AppEffect.ToggleGapless, effectFor(Screen.PlaybackTransitions, "gapless"))
        assertEquals(AppEffect.CycleCrossfade, effectFor(Screen.PlaybackTransitions, "crossfade"))
        assertEquals(AppEffect.CycleVolumeMode, effectFor(Screen.PlaybackVolume, "volume_mode"))
        assertEquals(AppEffect.CycleReplayGain, effectFor(Screen.PlaybackVolume, "replay_gain"))
        assertEquals(AppEffect.ToggleResumePosition, effectFor(Screen.PlaybackInterruptions, "resume_position"))

        val playbackKeys = listOf(
            Screen.Audio,
            Screen.PlaybackTransitions,
            Screen.PlaybackSeeking,
            Screen.PlaybackVolume,
            Screen.PlaybackInterruptions
        ).flatMap { screen ->
            ScreenContent.rows(AppState(screenStack = listOf(ScreenEntry(screen))))
                .filterIsInstance<ScreenRow.Action>().map { it.key }
        }
        assertFalse("live controls belong to Now Playing options", "sleep_timer" in playbackKeys)
        assertFalse("live controls belong to Now Playing options", "shuffle" in playbackKeys)
        assertFalse("live controls belong to Now Playing options", "repeat" in playbackKeys)
    }

    @Test fun volumeRowReportsModeAndLevel() {
        val base = AppState(screenStack = listOf(ScreenEntry(Screen.PlaybackVolume)))
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
        val audio = AppState(screenStack = listOf(ScreenEntry(Screen.Audio)))
        val effectsIndex = ScreenContent.rows(audio).indexOfFirst { (it as? ScreenRow.Action)?.key == "sound_effects" }
        val sound = AppReducer.reduce(
            audio.copy(screenStack = listOf(ScreenEntry(Screen.Audio, effectsIndex))),
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
        assertEquals(Screen.SoundEffects, sound.currentScreen)
        val equalizer = AppReducer.reduce(selectKey(sound, "equalizer"), AppAction.Confirm).state
        assertEquals(Screen.EqualizerSettings, equalizer.currentScreen)
        val bands = AppReducer.reduce(selectKey(equalizer, "eq_bands"), AppAction.Confirm).state
        assertEquals(Screen.EqualizerBands, bands.currentScreen)
        assertEquals(3, ScreenContent.rows(bands).size)
    }

    @Test fun heldMediaDirectionKeysSeekFromMenusUsingConfiguredStep() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu)),
            preferences = PlayerPreferencesState(seekStepMs = 30_000)
        )
        assertEquals(AppEffect.SeekBy(-30_000), AppReducer.reduce(state, AppAction.SeekBackward).effects.single())
        assertEquals(AppEffect.SeekBy(30_000), AppReducer.reduce(state, AppAction.SeekForward).effects.single())
        val longState = state.copy(preferences = state.preferences.copy(longSeekStepMs = 60_000))
        assertEquals(AppEffect.SeekBy(-60_000), AppReducer.reduce(longState, AppAction.SeekBackwardLong).effects.single())
        assertEquals(AppEffect.SeekBy(60_000), AppReducer.reduce(longState, AppAction.SeekForwardLong).effects.single())
    }

    @Test fun multiSelectUsesCenterToToggleAndHoldCenterForBatchQueueActions() {
        val second = track.copy(id = 2, title = "Second")
        val songs = AppState(
            screenStack = listOf(ScreenEntry(Screen.Songs)),
            library = LibraryState(tracks = listOf(track, second))
        )
        val firstTrack = selectTrack(songs, 1L)
        val options = AppReducer.reduce(firstTrack, AppAction.ConfirmLong).state
        val selecting = AppReducer.reduce(selectKey(options, "track_multi:1"), AppAction.Confirm).state
        val screen = selecting.currentScreen as Screen.MultiSelect
        assertEquals(setOf(1), screen.selectedIndices)

        val deselected = AppReducer.reduce(selecting, AppAction.Confirm).state.currentScreen as Screen.MultiSelect
        assertTrue(deselected.selectedIndices.isEmpty())

        val chosen = selecting.copy(screenStack = selecting.screenStack.dropLast(1) +
            selecting.currentEntry.copy(screen = screen.copy(selectedIndices = setOf(0, 1))))
        val actions = AppReducer.reduce(chosen, AppAction.ConfirmLong).state
        assertEquals(Screen.CollectionOptions("2 selected", listOf(2L, 1L)), actions.currentScreen)
        val queued = AppReducer.reduce(selectKey(actions, "collection_up_next"), AppAction.Confirm)
        assertEquals(AppEffect.AddToUpNext(listOf(2L, 1L)), queued.effects.single())
    }

    @Test fun holdingAnAlbumOffersBatchQueueActions() {
        val second = track.copy(id = 2, title = "Second")
        val albums = AppState(
            screenStack = listOf(ScreenEntry(Screen.Albums)),
            library = LibraryState(tracks = listOf(track, second))
        )
        val actions = AppReducer.reduce(albums, AppAction.ConfirmLong).state
        assertEquals(Screen.CollectionOptions("Album", listOf(2L, 1L)), actions.currentScreen)
        val playNext = AppReducer.reduce(selectKey(actions, "collection_next"), AppAction.Confirm)
        assertEquals(AppEffect.PlayNext(listOf(2L, 1L)), playNext.effects.single())
    }

    @Test fun equalizerBandsUseCenterForAdjustmentInsteadOfLeftAndRight() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.EqualizerBands, selectedIndex = 1)),
            playback = PlaybackSnapshot(
                audioEffects = com.schulzcode.y2player.core.model.AudioEffectsState(
                    available = true,
                    equalizerSupported = true,
                    bandFrequenciesHz = listOf(60, 230, 910),
                    bandLevelsMb = listOf(0, 0, 0)
                )
            )
        )

        assertEquals(AppEffect.AdjustEqualizerBand(1, 1), AppReducer.reduce(state, AppAction.Confirm).effects.single())
        assertEquals(AppEffect.AdjustEqualizerBand(1, -1), AppReducer.reduce(state, AppAction.ConfirmLong).effects.single())
        assertEquals(AppEffect.PreviousTrack, AppReducer.reduce(state, AppAction.Left).effects.single())
        assertEquals(AppEffect.NextTrack, AppReducer.reduce(state, AppAction.Right).effects.single())
    }

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

    @Test fun controlsScreenOwnsHapticsAndUiSounds() {
        val interfaceSettings = selectKey(
            AppState(screenStack = listOf(ScreenEntry(Screen.InterfaceSettings))),
            "controls"
        )
        val controls = AppReducer.reduce(interfaceSettings, AppAction.Confirm).state
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

    @Test fun anAlbumReachedThroughAnArtistShowsOnlyThatArtistsTracks() {
        val albums = AppReducer.reduce(selectGroup(atArtists(), "Bowie"), AppAction.Confirm).state
        val songs = AppReducer.reduce(selectGroup(albums, "Greatest Hits"), AppAction.Confirm).state

        assertEquals(Screen.AlbumSongs("Greatest Hits", "Bowie"), songs.currentScreen)
        assertEquals(listOf("Cell"), ScreenContent.rows(songs).map { it.title })
    }

    @Test fun theGlobalAlbumsListStillMergesSharedAlbumNames() {
        val albums = AppState(screenStack = listOf(ScreenEntry(Screen.Albums)), library = artistLibrary)
        val songs = AppReducer.reduce(selectGroup(albums, "Greatest Hits"), AppAction.Confirm).state

        assertEquals(Screen.AlbumSongs("Greatest Hits", null), songs.currentScreen)
        assertEquals(
            listOf("Cell", "Dust"),
            ScreenContent.rows(songs).filterIsInstance<ScreenRow.TrackRow>().map { it.track.title }.sorted()
        )
    }

    @Test fun allSongsStillReachesTheFlatArtistList() {
        val albums = AppReducer.reduce(selectGroup(atArtists(), "Bowie"), AppAction.Confirm).state
        val songs = AppReducer.reduce(selectKey(albums, "artist_all_songs"), AppAction.Confirm).state

        assertEquals(Screen.ArtistSongs("Bowie"), songs.currentScreen)
        assertEquals(3, ScreenContent.rows(songs).filterIsInstance<ScreenRow.TrackRow>().size)
        assertEquals(ScreenContent.COLLECTION_SHUFFLE_KEY, (ScreenContent.rows(songs).first() as ScreenRow.Action).key)
    }

    @Test fun backFromAnAlbumReturnsToTheArtistsAlbums() {
        val albums = AppReducer.reduce(selectGroup(atArtists(), "Bowie"), AppAction.Confirm).state
        val songs = AppReducer.reduce(selectGroup(albums, "Hunky Dory"), AppAction.Confirm).state
        val back = AppReducer.reduce(songs, AppAction.Back).state
        assertEquals(Screen.ArtistAlbums("Bowie"), back.currentScreen)
        assertEquals(Screen.Artists, AppReducer.reduce(back, AppAction.Back).state.currentScreen)
    }

    @Test fun balanceIsReachableEvenWithoutAudioEffectSupport() {
        val volume = AppState(screenStack = listOf(ScreenEntry(Screen.PlaybackVolume)))
        val opened = AppReducer.reduce(selectKey(volume, "balance"), AppAction.Confirm).state
        assertEquals(Screen.Balance, opened.currentScreen)
        assertEquals("Centre · off", soundRowSubtitle(volume, "balance"))
    }

    @Test fun choosingABalanceLevelSetsItAndLeavesTheScreen() {
        val balance = AppState(screenStack = listOf(ScreenEntry(Screen.SoundEffects), ScreenEntry(Screen.Balance)))
        val result = AppReducer.reduce(selectKey(balance, "balance:-40"), AppAction.Confirm)

        assertEquals(listOf(AppEffect.SetBalance(-40)), result.effects)
        assertEquals("choosing a level returns to Sound", Screen.SoundEffects, result.state.currentScreen)
    }

    @Test fun theSoundRowAndTheBalanceScreenAgreeOnTheCurrentValue() {
        val leaning = AppState(
            screenStack = listOf(ScreenEntry(Screen.PlaybackVolume)),
            preferences = PlayerPreferencesState(balance = -100)
        )
        assertEquals("Left only", soundRowSubtitle(leaning, "balance"))

        val screen = leaning.copy(screenStack = listOf(ScreenEntry(Screen.Balance)))
        val selected = ScreenContent.rows(screen).filterIsInstance<ScreenRow.Action>()
            .filter { it.subtitle == "Selected" }
        assertEquals(listOf("Left only"), selected.map { it.title })
    }

    @Test fun theSoundLandingSeparatesControlsFromOutputInformation() {
        val withEffects = AppState(
            screenStack = listOf(ScreenEntry(Screen.SoundEffects)),
            playback = PlaybackSnapshot(
                audioEffects = com.schulzcode.y2player.core.model.AudioEffectsState(
                    available = true,
                    equalizerSupported = true,
                    bassBoostSupported = true,
                    loudnessSupported = true
                )
            )
        )
        assertEquals(
            listOf("effects_toggle", "equalizer", "bass", "loudness"),
            soundKeys(withEffects)
        )

        val equalizer = AppReducer.reduce(selectKey(withEffects, "equalizer"), AppAction.Confirm).state
        assertEquals(Screen.EqualizerSettings, equalizer.currentScreen)
        assertEquals(listOf("eq_preset", "eq_bands"), soundKeys(equalizer))

        assertEquals(AppEffect.CycleBassStrength, AppReducer.reduce(selectKey(withEffects, "bass"), AppAction.Confirm).effects.single())
        assertEquals(AppEffect.CycleLoudnessGain, AppReducer.reduce(selectKey(withEffects, "loudness"), AppAction.Confirm).effects.single())
    }

    @Test fun theDacInformationSurvivesMissingAudioEffectSupport() {
        val noEffects = AppState(
            screenStack = listOf(ScreenEntry(Screen.SoundEffects)),
            playback = PlaybackSnapshot(
                audioEffects = com.schulzcode.y2player.core.model.AudioEffectsState(available = false)
            )
        )
        assertTrue(
            "the unavailable notice must still appear",
            "effects_unavailable" in soundKeys(noEffects)
        )

        val audio = AppState(screenStack = listOf(ScreenEntry(Screen.Audio)), playback = noEffects.playback)
        val audioKeys = soundKeys(audio)
        assertTrue("volume must stay reachable", "playback_volume" in audioKeys)
        assertTrue("output must stay reachable", "output" in audioKeys)

        val output = AppReducer.reduce(selectKey(audio, "output"), AppAction.Confirm).state
        assertEquals(Screen.OutputInformation, output.currentScreen)
        assertTrue("the DAC status must not be hidden", "dac_status" in soundKeys(output))
        assertTrue("the profile must be adjustable here", "audio_quality" in soundKeys(output))

        val volume = AppReducer.reduce(selectKey(audio, "playback_volume"), AppAction.Confirm).state
        assertTrue("balance must stay reachable", "balance" in soundKeys(volume))
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

    @Test fun controlsScreenOwnsTheSingleListWrappingToggle() {
        val controls = AppState(screenStack = listOf(ScreenEntry(Screen.Controls)))
        val row = ScreenContent.rows(controls).filterIsInstance<ScreenRow.Action>()
            .single { it.key == "wrap_lists" }
        assertEquals("Continue from bottom to top", row.subtitle)
        assertEquals(
            listOf(AppEffect.ToggleWrapLists),
            AppReducer.reduce(selectKey(controls, "wrap_lists"), AppAction.Confirm).effects
        )

        val bounded = controls.copy(preferences = controls.preferences.copy(wrapLists = false))
        assertEquals(
            "Stop at the first and last item",
            ScreenContent.rows(bounded).filterIsInstance<ScreenRow.Action>()
                .single { it.key == "wrap_lists" }.subtitle
        )
    }

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

    @Test fun theControlsSummaryMentionsTheScreenOffWheelOnlyWhenEnabled() {
        val base = AppState(screenStack = listOf(ScreenEntry(Screen.InterfaceSettings)))
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
        assertEquals(listOf("brightness", "theme", "timeout", "keep_screen_on", "extra_track_info"), keys)
        assertFalse("haptics" in keys)
        assertFalse("ui_sounds" in keys)
    }

    @Test fun extraTrackInfoIsOffByDefaultAndTogglesFromDisplay() {
        val display = AppState(screenStack = listOf(ScreenEntry(Screen.Display)))
        val row = ScreenContent.rows(display)
            .filterIsInstance<ScreenRow.Action>().single { it.key == "extra_track_info" }
        assertEquals("Off", row.subtitle)

        val effect = AppReducer.reduce(selectKey(display, "extra_track_info"), AppAction.Confirm).effects.single()
        assertEquals(AppEffect.ToggleExtraTrackInfo, effect)

        val on = display.copy(preferences = display.preferences.copy(extraTrackInfo = true))
        assertEquals(
            "On · year, bitrate and genre",
            ScreenContent.rows(on).filterIsInstance<ScreenRow.Action>().single { it.key == "extra_track_info" }.subtitle
        )
    }

    @Test fun extraTrackInfoHasOneCanonicalControlUnderDisplay() {
        val interfaceKeys = ScreenContent.rows(
            AppState(screenStack = listOf(ScreenEntry(Screen.InterfaceSettings)))
        ).filterIsInstance<ScreenRow.Action>().map { it.key }
        assertEquals(listOf("display", "controls"), interfaceKeys)
        assertFalse("extra_track_info" in interfaceKeys)
    }

    @Test fun keepScreenOnLivesOnlyOnTheDisplayScreen() {
        fun keys(screen: Screen) = ScreenContent.rows(AppState(screenStack = listOf(ScreenEntry(screen))))
            .filterIsInstance<ScreenRow.Action>().map { it.key }
        assertTrue("keep_screen_on" in keys(Screen.Display))
        assertFalse("keep_screen_on" in keys(Screen.PlaybackTransitions))
    }

    @Test fun theControlsRowSummarisesBothSettings() {
        val base = AppState(
            screenStack = listOf(ScreenEntry(Screen.InterfaceSettings)),
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

    @Test fun theControlsRowOmitsHapticsWithoutAMotor() {
        val noMotor = AppState(screenStack = listOf(ScreenEntry(Screen.InterfaceSettings)))
        assertEquals("Sounds off", controlsSubtitle(noMotor))
    }

    @Test fun clearDiagnosticsRequiresExplicitConfirmation() {
        val diagnostics = selectKey(
            AppState(screenStack = listOf(ScreenEntry(Screen.Diagnostics))),
            "diag_clear"
        )
        val prompted = AppReducer.reduce(diagnostics, AppAction.Confirm)
        assertTrue(prompted.effects.isEmpty())
        assertEquals(Screen.ConfirmAction(ConfirmPrompts.CLEAR_DIAGNOSTICS), prompted.state.currentScreen)

        val confirmed = AppReducer.reduce(
            selectKey(prompted.state, ScreenContent.CONFIRM_OK_KEY),
            AppAction.Confirm
        )
        assertEquals(listOf(AppEffect.ClearDiagnostics), confirmed.effects)
    }

    @Test fun importBackupRequiresValidatedPreviewAndExplicitConfirmation() {
        val backup = selectKey(
            AppState(screenStack = listOf(ScreenEntry(Screen.BackupRestore))),
            "backup_import"
        )
        val inspect = AppReducer.reduce(backup, AppAction.Confirm)
        assertEquals(Screen.BackupRestore, inspect.state.currentScreen)
        assertEquals(listOf(AppEffect.InspectBackup), inspect.effects)

        val prompted = AppReducer.reduce(inspect.state, AppAction.BackupImportReady("1 playlist, 2 favorites"))
        assertEquals(Screen.ConfirmAction(ConfirmPrompts.IMPORT_BACKUP), prompted.state.currentScreen)
        assertTrue(prompted.effects.isEmpty())

        val confirmed = AppReducer.reduce(
            selectKey(prompted.state, ScreenContent.CONFIRM_OK_KEY),
            AppAction.Confirm
        )
        assertEquals(listOf(AppEffect.ImportBackup), confirmed.effects)
    }

    private fun controlsSubtitle(state: AppState): String? =
        ScreenContent.rows(state).filterIsInstance<ScreenRow.Action>()
            .first { it.key == "controls" }.subtitle

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
