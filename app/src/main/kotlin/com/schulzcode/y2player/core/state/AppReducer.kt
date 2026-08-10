package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.TrackSortOrder
import com.schulzcode.y2player.core.state.AppEffect.*
import com.schulzcode.y2player.core.state.ScreenRow.Action
import com.schulzcode.y2player.core.state.ScreenRow.Folder
import com.schulzcode.y2player.core.state.ScreenRow.Group
import com.schulzcode.y2player.core.state.ScreenRow.TrackRow
import com.schulzcode.y2player.playback.AudioBalance

object AppReducer {
    fun reduce(state: AppState, action: AppAction): Reduction = when (action) {
        is AppAction.WheelMoved -> moveSelection(state, action.delta)
        AppAction.Confirm -> confirm(state)
        AppAction.ConfirmLong -> confirmLong(state)
        AppAction.ShowNowPlaying -> showNowPlaying(state)
        AppAction.Back -> back(state)
        AppAction.NavigateHome -> Reduction(
            if (state.screenStack.size == 1 && state.currentScreen == Screen.MainMenu) state
            else state.copy(screenStack = listOf(ScreenEntry(Screen.MainMenu)))
        )
        AppAction.Left -> Reduction(state, listOf(PreviousTrack))
        AppAction.Right -> Reduction(state, listOf(NextTrack))
        AppAction.PlayPause -> Reduction(state, listOf(TogglePlayback))
        AppAction.MediaNext -> Reduction(state, listOf(NextTrack))
        AppAction.MediaPrevious -> Reduction(state, listOf(PreviousTrack))
        AppAction.MediaStop -> if (state.playback.status == com.schulzcode.y2player.core.model.PlaybackStatus.PLAYING || state.playback.status == com.schulzcode.y2player.core.model.PlaybackStatus.PREPARING) Reduction(state, listOf(TogglePlayback)) else Reduction(state)
        AppAction.SeekBackward -> Reduction(state, listOf(SeekBy(-state.preferences.seekStepMs.toLong())))
        AppAction.SeekForward -> Reduction(state, listOf(SeekBy(state.preferences.seekStepMs.toLong())))
        AppAction.SeekBackwardLong -> Reduction(state, listOf(SeekBy(-state.preferences.longSeekStepMs.toLong())))
        AppAction.SeekForwardLong -> Reduction(state, listOf(SeekBy(state.preferences.longSeekStepMs.toLong())))
        is AppAction.LibraryChanged -> Reduction(preserveSelection(state, state.copy(library = action.library)))
        is AppAction.PlaybackChanged -> Reduction(playbackChanged(state, action.playback))
        is AppAction.DeviceChanged -> Reduction(preserveSelection(state, state.copy(device = action.device)))
        is AppAction.BluetoothChanged -> Reduction(preserveSelection(state, state.copy(bluetooth = action.bluetooth)))
        is AppAction.DisplayChanged -> Reduction(state.copy(display = action.display))
        is AppAction.PreferencesChanged -> Reduction(preserveSelection(state, state.copy(preferences = action.preferences)))
        is AppAction.DiagnosticsChanged -> Reduction(preserveSelection(state, state.copy(diagnostics = action.diagnostics)))
        is AppAction.SafeModeChanged -> Reduction(state.copy(safeMode = action.enabled))
        is AppAction.ShowMessage -> Reduction(state.copy(transientMessage = action.message))
        is AppAction.SelectIndex -> Reduction(normalizeSelection(setSelected(state, action.index.coerceAtLeast(0))))
    }

    private fun playbackChanged(state: AppState, playback: com.schulzcode.y2player.core.model.PlaybackSnapshot): AppState {
        val progressOnly = isProgressOnlyUpdate(state.playback, playback)
        val updated = state.copy(playback = playback)
        if (progressOnly) return updated

        val options = updated.currentScreen as? Screen.QueueOptions
        return if (options != null && options.queueIndex !in playback.queue.indices) {
            normalizeSelection(pop(updated))
        } else normalizeSelection(updated)
    }

    private fun moveSelection(state: AppState, delta: Int): Reduction {
        if (delta == 0) return Reduction(state)
        if (state.currentScreen == Screen.NowPlaying) return Reduction(state, listOf(AdjustVolume(if (delta > 0) 1 else -1)))
        val count = ScreenContent.rows(state).size
        if (count == 0) return Reduction(state)
        val next = ListNavigationPolicy.nextIndex(
            screen = state.currentScreen,
            currentIndex = state.selectedIndex,
            delta = delta,
            itemCount = count,
            wrapLists = state.preferences.wrapLists
        )
        return if (next == state.selectedIndex) Reduction(state) else Reduction(setSelected(state, next))
    }

    private fun confirm(state: AppState): Reduction {
        if (state.currentScreen == Screen.NowPlaying) return Reduction(state)
        val screenRows = ScreenContent.rows(state)
        if (screenRows.isEmpty()) return confirmEmptyScreen(state)
        val row = screenRows.getOrNull(state.selectedIndex) ?: return Reduction(state)
        return when (val screen = state.currentScreen) {
            Screen.MainMenu -> confirmMainMenu(state, row)
            Screen.Music -> confirmMusic(state, row)
            Screen.Audiobooks -> confirmAudiobook(state, row)
            is Screen.AudiobookOptions -> confirmAudiobookOptions(state, screen, row)
            is Screen.AudiobookChapters -> confirmAudiobookChapter(state, screen, row)
            Screen.Songs, Screen.Favorites, Screen.RecentlyPlayed,
            is Screen.ArtistSongs, is Screen.AlbumSongs ->
                if ((row as? Action)?.key == ScreenContent.COLLECTION_SHUFFLE_KEY) shuffleCollection(state)
                else playSelected(state)
            is Screen.ArtistAlbums -> confirmArtistAlbums(state, screen, row)
            Screen.Albums -> (row as? Group)?.key?.let { push(state, Screen.AlbumSongs(it)) } ?: Reduction(state)
            Screen.Artists -> (row as? Group)?.key?.let { push(state, Screen.ArtistAlbums(it)) } ?: Reduction(state)
            is Screen.Folders -> when (row) {
                is Folder -> push(state, Screen.Folders(row.volumeId, row.relativePath))
                is TrackRow -> playSelected(state)
                else -> Reduction(state)
            }
            Screen.Playlists -> confirmPlaylists(state, row)
            is Screen.PlaylistTracks -> when {
                row is TrackRow -> playSelected(state)
                (row as? Action)?.key == ScreenContent.COLLECTION_SHUFFLE_KEY -> shuffleCollection(state)
                else -> confirmPlaylistAction(state, screen, row)
            }
            is Screen.TrackOptions -> confirmTrackOptions(state, screen, row)
            is Screen.TrackBrowse -> confirmTrackBrowse(state, screen, row)
            is Screen.TrackDetails -> Reduction(state)
            is Screen.AddToPlaylist -> confirmAddToPlaylist(state, screen.trackId, row)
            is Screen.QueueOptions -> confirmQueueOptions(state, row)
            Screen.QueueManagement -> confirmQueueManagement(state, row)
            Screen.Queue ->
                if (state.selectedIndex == state.playback.currentQueueIndex &&
                    isTrackLoaded(state, state.playback.currentTrackId)
                ) Reduction(toNowPlaying(state))
                else Reduction(toNowPlaying(state), listOf(PlayQueueIndex(state.selectedIndex)))
            Screen.NowPlayingOptions -> confirmNowPlayingOptions(state, row)
            Screen.Audio -> confirmAudio(state, row)
            Screen.Settings -> confirmSettings(state, row)
            Screen.PlaybackTransitions, Screen.PlaybackSeeking, Screen.PlaybackVolume,
            Screen.PlaybackInterruptions -> confirmPlaybackPreference(state, row)
            Screen.SoundEffects -> confirmSoundEffects(state, row)
            Screen.EqualizerSettings -> confirmEqualizerSettings(state, row)
            Screen.OutputInformation -> confirmOutput(state, row)
            Screen.EqualizerBands -> confirmEqualizerBands(state, row)
            Screen.SortOrder -> confirmSortOrder(state, row)
            Screen.Bluetooth -> confirmBluetooth(state, row)
            is Screen.BluetoothDevice -> confirmBluetoothDevice(state, row)
            is Screen.ConfirmAction -> confirmConfirmAction(state, screen, row)
            Screen.InterfaceSettings -> confirmInterfaceSettings(state, row)
            Screen.LibrarySettings -> confirmLibrarySettings(state, row)
            Screen.Display -> confirmDisplay(state, row)
            Screen.Controls -> confirmControls(state, row)
            Screen.Balance -> confirmBalance(state, row)
            Screen.Brightness -> confirmBrightness(state, row)
            Screen.ScreenTimeout -> confirmTimeout(state, row)
            Screen.Storage -> confirmStorage(state, row)
            Screen.PlaybackHistory -> confirmPlaybackHistory(state, row)
            Screen.System -> confirmSystem(state, row)
            Screen.Diagnostics -> confirmDiagnostics(state, row)
            Screen.Reset -> confirmReset(state, row)
            Screen.About -> Reduction(state)
            Screen.NowPlaying -> Reduction(state)
        }
    }

    private fun confirmMainMenu(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "music" -> push(state, Screen.Music)
            "audiobooks" -> Reduction(pushState(state, Screen.Audiobooks), listOf(RefreshAudiobooks))
            "shuffle_all" -> Reduction(toNowPlaying(state), listOf(ShuffleAll))
            "settings" -> push(state, Screen.Settings)
            else -> Reduction(state)
        }
    }

    private fun confirmMusic(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "shuffle_all" -> Reduction(toNowPlaying(state), listOf(ShuffleAll))
            "songs" -> push(state, Screen.Songs)
            "albums" -> push(state, Screen.Albums)
            "artists" -> push(state, Screen.Artists)
            "playlists" -> push(state, Screen.Playlists)
            "favorites" -> push(state, Screen.Favorites)
            "recent" -> push(state, Screen.RecentlyPlayed)
            "folders" -> push(state, Screen.Folders())
            else -> Reduction(state)
        }
    }

    private fun confirmPlaylists(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when {
            key == "playlist_create" -> Reduction(state, listOf(CreatePlaylist))
            key.startsWith("playlist:") -> {
                val id = key.substringAfter(':').toLongOrNull() ?: return Reduction(state)
                val playlist = state.library.playlists.firstOrNull { it.id == id } ?: return Reduction(state)
                push(state, Screen.PlaylistTracks(id, playlist.name))
            }
            else -> Reduction(state)
        }
    }

    private fun confirmPlaylistAction(state: AppState, screen: Screen.PlaylistTracks, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return if (key.startsWith("playlist_delete:")) push(
            state,
            Screen.ConfirmAction(ConfirmPrompts.DELETE_PLAYLIST + screen.playlistId),
            selectedIndex = ScreenContent.CONFIRM_DEFAULT_INDEX
        ) else Reduction(state)
    }

    private fun confirmTrackOptions(state: AppState, screen: Screen.TrackOptions, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        val trackId = screen.trackId
        return when {
            key.startsWith("track_next:") -> Reduction(pop(state), listOf(PlayNext(trackId)))
            key.startsWith("track_queue:") -> Reduction(pop(state), listOf(AddToQueue(trackId)))
            key.startsWith("track_favorite:") -> Reduction(pop(state), listOf(ToggleFavorite(trackId)))
            key.startsWith("track_playlist:") -> push(state, Screen.AddToPlaylist(trackId))
            key.startsWith("track_browse:") -> push(state, Screen.TrackBrowse(trackId))
            key.startsWith("track_details:") -> push(state, Screen.TrackDetails(trackId))
            key.startsWith("track_remove_playlist:") -> {
                val parts = key.split(':')
                val playlistId = parts.getOrNull(1)?.toLongOrNull() ?: return Reduction(state)
                Reduction(pop(state), listOf(RemoveTrackFromPlaylist(playlistId, trackId)))
            }
            else -> Reduction(state)
        }
    }

    private fun confirmTrackBrowse(state: AppState, screen: Screen.TrackBrowse, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        val track = state.library.byId[screen.trackId] ?: return Reduction(state)
        return when {
            key.startsWith("track_album:") -> push(state, Screen.AlbumSongs(track.displayAlbum, track.albumArtistName))
            key.startsWith("track_artist:") -> push(state, Screen.ArtistSongs(track.primaryArtist))
            else -> Reduction(state)
        }
    }

    private fun confirmAddToPlaylist(state: AppState, trackId: Long, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        fun closeAddToPlaylist(): AppState = state.screenStack.dropLast(2)
            .let { state.copy(screenStack = it.ifEmpty { listOf(ScreenEntry(Screen.MainMenu)) }) }
        return when {
            key == "playlist_create_and_add" -> Reduction(closeAddToPlaylist(), listOf(CreatePlaylistWithTrack(trackId)))
            key.startsWith("playlist_add:") -> {
                val id = key.substringAfter(':').toLongOrNull() ?: return Reduction(state)
                Reduction(closeAddToPlaylist(), listOf(AddTrackToPlaylist(id, trackId)))
            }
            else -> Reduction(state)
        }
    }

    private fun confirmQueueOptions(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        fun index() = key.substringAfter(':', "-1").toIntOrNull() ?: -1
        return when {
            key.startsWith("queue_play:") -> Reduction(toNowPlaying(pop(state)), listOf(PlayQueueIndex(index())))
            key.startsWith("queue_up:") -> Reduction(pop(state), listOf(MoveQueueItem(index(), -1)))
            key.startsWith("queue_down:") -> Reduction(pop(state), listOf(MoveQueueItem(index(), 1)))
            key.startsWith("queue_remove:") -> Reduction(pop(state), listOf(RemoveQueueIndex(index())))
            key == "queue_management" -> push(state, Screen.QueueManagement)
            else -> Reduction(state)
        }
    }

    private fun confirmQueueManagement(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "queue_clear_upcoming" -> Reduction(state.screenStack.dropLast(2).let {
                state.copy(screenStack = it.ifEmpty { listOf(ScreenEntry(Screen.MainMenu)) })
            }, listOf(ClearUpcoming))
            "queue_clear" -> push(
                state,
                Screen.ConfirmAction(ConfirmPrompts.CLEAR_QUEUE),
                selectedIndex = ScreenContent.CONFIRM_DEFAULT_INDEX
            )
            else -> Reduction(state)
        }
    }

    private fun confirmAudio(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "output" -> push(state, Screen.OutputInformation)
            "playback_transitions" -> push(state, Screen.PlaybackTransitions)
            "playback_volume" -> push(state, Screen.PlaybackVolume)
            "sound_effects" -> push(state, Screen.SoundEffects)
            "playback_interruptions" -> push(state, Screen.PlaybackInterruptions)
            else -> Reduction(state)
        }
    }

    private fun confirmSettings(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "audio" -> push(state, Screen.Audio)
            "bluetooth" -> push(state, Screen.Bluetooth)
            "interface" -> push(state, Screen.InterfaceSettings)
            // The Listening History subtitle is read here, so the count has to be
            // fetched on the way in rather than when that row is pressed.
            "library_settings" -> Reduction(pushState(state, Screen.LibrarySettings), listOf(RefreshPlaybackHistory))
            "system" -> push(state, Screen.System)
            else -> Reduction(state)
        }
    }

    private fun confirmInterfaceSettings(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "display" -> push(state, Screen.Display)
            "controls" -> push(state, Screen.Controls)
            "extra_track_info" -> Reduction(state, listOf(ToggleExtraTrackInfo))
            else -> Reduction(state)
        }
    }

    private fun confirmLibrarySettings(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "sort" -> push(state, Screen.SortOrder)
            "storage" -> push(state, Screen.Storage)
            "playback_history" -> Reduction(pushState(state, Screen.PlaybackHistory), listOf(RefreshPlaybackHistory))
            "playlist_import_m3u" -> Reduction(state, listOf(ImportM3uPlaylists))
            "playlist_export_m3u" -> Reduction(state, listOf(ExportM3uPlaylists))
            else -> Reduction(state)
        }
    }

    private fun confirmSystem(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "diagnostics" -> push(state, Screen.Diagnostics)
            "reset" -> push(state, Screen.Reset)
            "android_settings" -> Reduction(state, listOf(OpenAndroidSettings))
            "about" -> push(state, Screen.About)
            else -> Reduction(state)
        }
    }

    private fun confirmPlaybackPreference(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "pause_disconnect" -> Reduction(state, listOf(TogglePauseOnDisconnect))
            "gapless" -> Reduction(state, listOf(ToggleGapless))
            "crossfade" -> Reduction(state, listOf(CycleCrossfade))
            "crossfade_mode" -> Reduction(state, listOf(CycleCrossfadeMode))
            "pause_fade" -> Reduction(state, listOf(CyclePauseFade))
            "seek_step" -> Reduction(state, listOf(CycleSeekStep))
            "long_seek_step" -> Reduction(state, listOf(CycleLongSeekStep))
            "previous_threshold" -> Reduction(state, listOf(CyclePreviousThreshold))
            "duck_focus" -> Reduction(state, listOf(ToggleDuckOnFocusLoss))
            "volume_mode" -> Reduction(state, listOf(CycleVolumeMode))
            "replay_gain" -> Reduction(state, listOf(CycleReplayGain))
            "balance" -> push(state, Screen.Balance)
            "resume_position" -> Reduction(state, listOf(ToggleResumePosition))
            else -> Reduction(state)
        }
    }

    private fun confirmSoundEffects(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "effects_toggle" -> Reduction(state, listOf(ToggleAudioEffects))
            "equalizer" -> push(state, Screen.EqualizerSettings)
            "bass" -> Reduction(state, listOf(CycleBassStrength))
            "loudness" -> Reduction(state, listOf(CycleLoudnessGain))
            else -> Reduction(state)
        }
    }

    private fun confirmOutput(state: AppState, row: ScreenRow): Reduction =
        if ((row as? Action)?.key == "audio_quality") Reduction(state, listOf(CycleAudioQuality))
        else Reduction(state)

    private fun confirmReset(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "reset_queue" -> push(state, Screen.ConfirmAction(ConfirmPrompts.CLEAR_QUEUE), selectedIndex = ScreenContent.CONFIRM_DEFAULT_INDEX)
            "reset_library" -> push(state, Screen.ConfirmAction(ConfirmPrompts.RESET_LIBRARY), selectedIndex = ScreenContent.CONFIRM_DEFAULT_INDEX)
            "reset_safe_mode" -> Reduction(state, listOf(if (state.safeMode) ExitSafeMode else EnterSafeMode))
            else -> Reduction(state)
        }
    }

    private fun confirmEqualizerSettings(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "eq_preset" -> Reduction(state, listOf(CycleEqualizerPreset))
            "eq_bands" -> push(state, Screen.EqualizerBands)
            else -> Reduction(state)
        }
    }

    private fun confirmEqualizerBands(state: AppState, row: ScreenRow): Reduction {
        val index = (row as? Action)?.key?.substringAfter("eq_band:", "")?.toIntOrNull() ?: return Reduction(state)
        return Reduction(state, listOf(AdjustEqualizerBand(index, 1)))
    }

    private fun confirmSortOrder(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        val raw = key.substringAfter("sort:", "")
        val order = TrackSortOrder.fromStorage(raw).takeIf { raw == it.storageId || raw == it.name } ?: return Reduction(state)
        return Reduction(pop(state), listOf(SetSortOrder(order)))
    }

    private fun confirmBluetooth(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when {
            key == "bt_toggle" -> when (state.bluetooth.adapterMode) {
                BluetoothAdapterMode.ON, BluetoothAdapterMode.TURNING_ON -> Reduction(state, listOf(SetBluetoothEnabled(false)))
                BluetoothAdapterMode.OFF, BluetoothAdapterMode.TURNING_OFF -> Reduction(state, listOf(SetBluetoothEnabled(true)))
                BluetoothAdapterMode.UNSUPPORTED -> Reduction(state.copy(transientMessage = "Bluetooth hardware is unavailable"))
            }
            key == "bt_scan" -> Reduction(state, listOf(if (state.bluetooth.isDiscovering) StopBluetoothScan else StartBluetoothScan))
            key == "bt_refresh" -> Reduction(state, listOf(RefreshBluetoothService))
            key.startsWith("bt_device:") -> Reduction(state, listOf(ActivateBluetoothDevice(key.substringAfter(':'))))
            else -> Reduction(state)
        }
    }

    private fun confirmAudiobook(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        if (!key.startsWith(ScreenContent.AUDIOBOOK_KEY_PREFIX)) return Reduction(state)
        val entry = ScreenContent.audiobookEntry(state, key.substringAfter(':')) ?: return Reduction(state)
        if (entry.chapterIds.isEmpty()) return Reduction(state)
        return Reduction(toNowPlaying(state), listOf(PlayCollection(entry.chapterIds, entry.startIndex)))
    }

    private fun confirmAudiobookOptions(
        state: AppState,
        screen: Screen.AudiobookOptions,
        row: ScreenRow
    ): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        val folderKey = screen.folderKey
        return when {
            key.startsWith(ScreenContent.AUDIOBOOK_CHAPTERS_KEY) ->
                push(state, Screen.AudiobookChapters(folderKey), selectedIndex = currentChapterIndex(state, folderKey))
            key.startsWith(ScreenContent.AUDIOBOOK_RESTART_KEY) -> {
                val entry = ScreenContent.audiobookEntry(state, folderKey) ?: return Reduction(state)
                if (entry.chapterIds.isEmpty()) return Reduction(state)
                Reduction(toNowPlaying(state), listOf(PlayCollection(entry.chapterIds, 0, fromStart = true)))
            }
            key.startsWith(ScreenContent.AUDIOBOOK_CLEAR_KEY) -> push(
                state,
                Screen.ConfirmAction(ConfirmPrompts.CLEAR_AUDIOBOOK + folderKey),
                selectedIndex = ScreenContent.CONFIRM_DEFAULT_INDEX
            )
            else -> Reduction(state)
        }
    }

    private fun confirmAudiobookChapter(
        state: AppState,
        screen: Screen.AudiobookChapters,
        row: ScreenRow
    ): Reduction {
        val track = (row as? TrackRow)?.track ?: return Reduction(state)
        val entry = ScreenContent.audiobookEntry(state, screen.folderKey) ?: return Reduction(state)
        val index = entry.chapterIds.indexOf(track.id)
        if (index < 0) return Reduction(state)
        return Reduction(toNowPlaying(state), listOf(PlayCollection(entry.chapterIds, index, fromStart = true)))
    }

    private fun currentChapterIndex(state: AppState, folderKey: String): Int =
        ScreenContent.audiobookEntry(state, folderKey)?.let { it.chapterNumber?.minus(1) } ?: 0

    private fun confirmBluetoothDevice(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when {
            key.startsWith("bt_device_activate:") ->
                Reduction(pop(state), listOf(ActivateBluetoothDevice(key.substringAfter(':'))))
            key.startsWith("bt_device_forget:") -> push(
                state,
                Screen.ConfirmAction(ConfirmPrompts.FORGET_DEVICE + key.substringAfter(':')),
                selectedIndex = ScreenContent.CONFIRM_DEFAULT_INDEX
            )
            else -> Reduction(state)
        }
    }

    private fun confirmConfirmAction(state: AppState, screen: Screen.ConfirmAction, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            ScreenContent.CONFIRM_CANCEL_KEY -> Reduction(pop(state))
            ScreenContent.CONFIRM_OK_KEY -> confirmedAction(state, screen.key)
            else -> Reduction(state)
        }
    }

    private fun confirmedAction(state: AppState, actionKey: String): Reduction = when {
        actionKey.startsWith(ConfirmPrompts.FORGET_DEVICE) ->
            Reduction(pop(state, 2), listOf(ForgetBluetoothDevice(actionKey.substringAfter(':'))))
        actionKey.startsWith(ConfirmPrompts.CLEAR_AUDIOBOOK) ->
            Reduction(pop(state, 2), listOf(ClearAudiobookProgress(actionKey.substringAfter(':'))))
        actionKey == ConfirmPrompts.CLEAR_QUEUE -> Reduction(pop(state), listOf(ClearQueue))
        actionKey == ConfirmPrompts.RESET_LIBRARY -> Reduction(pop(state), listOf(ResetLibrary))
        actionKey == ConfirmPrompts.CLEAR_HISTORY -> Reduction(pop(state), listOf(ClearPlaybackHistory))
        actionKey.startsWith(ConfirmPrompts.DELETE_PLAYLIST) -> {
            val id = actionKey.substringAfter(':').toLongOrNull()
            if (id == null) Reduction(pop(state)) else Reduction(pop(state, 2), listOf(DeletePlaylist(id)))
        }
        else -> Reduction(pop(state))
    }

    private fun confirmDisplay(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "brightness" -> push(state, Screen.Brightness)
            "timeout" -> push(state, Screen.ScreenTimeout)
            "keep_screen_on" -> Reduction(state, listOf(ToggleKeepScreenOn))
            "extra_track_info" -> Reduction(state, listOf(ToggleExtraTrackInfo))
            "theme" -> Reduction(state, listOf(ToggleLightTheme))
            else -> Reduction(state)
        }
    }

    private fun confirmArtistAlbums(state: AppState, screen: Screen.ArtistAlbums, row: ScreenRow): Reduction = when {
        (row as? Action)?.key == "artist_all_songs" -> push(state, Screen.ArtistSongs(screen.artist))
        row is Group -> push(
            state,
            Screen.AlbumSongs(
                row.key,
                ScreenContent.albumArtistForArtistAlbum(state, screen.artist, row.key)
            )
        )
        else -> Reduction(state)
    }

    private fun confirmBalance(state: AppState, row: ScreenRow): Reduction {
        val value = (row as? Action)?.key?.substringAfter("balance:", "")?.toIntOrNull()
            ?: return Reduction(state)
        return Reduction(pop(state), listOf(SetBalance(AudioBalance.clamp(value))))
    }

    private fun confirmControls(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "ui_sounds" -> Reduction(state, listOf(ToggleUiSoundEffects))
            "playback_seeking" -> push(state, Screen.PlaybackSeeking)
            "haptics" -> Reduction(state, listOf(CycleHapticLevel))
            "wrap_lists" -> Reduction(state, listOf(ToggleWrapLists))
            "screen_off_keys" -> Reduction(state, listOf(ToggleLocalKeysWhileScreenOff))
            else -> Reduction(state)
        }
    }

    private fun confirmBrightness(state: AppState, row: ScreenRow): Reduction {
        val percent = (row as? Action)?.key?.substringAfter("brightness:", "")?.toIntOrNull() ?: return Reduction(state)
        return Reduction(pop(state), listOf(SetBrightness(percent)))
    }

    private fun confirmTimeout(state: AppState, row: ScreenRow): Reduction {
        val timeout = (row as? Action)?.key?.substringAfter("timeout:", "")?.toIntOrNull() ?: return Reduction(state)
        return Reduction(pop(state), listOf(SetScreenTimeout(timeout)))
    }

    private fun confirmStorage(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when {
            key == "rescan" -> Reduction(state, listOf(RequestLibraryScan))
            key.startsWith("storage:") -> {
                val id = key.substringAfter(':')
                if (state.device.storageVolumes.firstOrNull { it.id == id }?.available == true) push(state, Screen.Folders(id, ""))
                else Reduction(state.copy(transientMessage = "Storage is not mounted"))
            }
            else -> Reduction(state)
        }
    }

    private fun confirmPlaybackHistory(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return if (key == "history_clear") push(
            state,
            Screen.ConfirmAction(ConfirmPrompts.CLEAR_HISTORY),
            selectedIndex = ScreenContent.CONFIRM_DEFAULT_INDEX
        ) else Reduction(state)
    }

    private fun confirmDiagnostics(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "diag_export" -> Reduction(state, listOf(ExportDiagnostics))
            "diag_verbose" -> Reduction(state, listOf(ToggleVerboseDiagnostics))
            else -> Reduction(state)
        }
    }

    private fun confirmLong(state: AppState): Reduction = when (state.currentScreen) {
        Screen.NowPlaying -> push(state, Screen.NowPlayingOptions)
        Screen.NowPlayingOptions -> back(state)
        Screen.EqualizerBands -> Reduction(state, listOf(AdjustEqualizerBand(state.selectedIndex, -1)))
        else -> openContextOptions(state)
    }

    private fun showNowPlaying(state: AppState): Reduction = when {
        state.currentScreen == Screen.NowPlaying -> Reduction(state)
        state.playback.currentTrackId == null && state.playback.queue.isEmpty() -> Reduction(state)
        else -> Reduction(toNowPlaying(state))
    }

    private fun confirmNowPlayingOptions(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when {
            key == "shuffle" -> Reduction(state, listOf(ToggleShuffle))
            key == "repeat" -> Reduction(state, listOf(CycleRepeat))
            key == "sleep_timer" -> Reduction(state, listOf(CycleSleepTimer))
            key == "queue" -> push(state, Screen.Queue, selectedIndex = state.playback.currentQueueIndex ?: 0)
            key.startsWith("np_favorite:") -> {
                val id = key.substringAfter(':').toLongOrNull() ?: return Reduction(state)
                Reduction(state, listOf(ToggleFavorite(id)))
            }
            key.startsWith("np_playlist:") -> {
                val id = key.substringAfter(':').toLongOrNull() ?: return Reduction(state)
                push(state, Screen.AddToPlaylist(id))
            }
            key.startsWith("np_track_options:") -> {
                val id = key.substringAfter(':').toLongOrNull() ?: return Reduction(state)
                push(state, Screen.TrackOptions(id, fromNowPlaying = true))
            }
            else -> Reduction(state)
        }
    }

    private fun playSelected(state: AppState): Reduction {
        val selection = ScreenContent.selectedTrackCollection(state) ?: return Reduction(state)
        if (isTrackLoaded(state, selection.first.getOrNull(selection.second))) {
            return Reduction(toNowPlaying(state))
        }
        return Reduction(toNowPlaying(state), listOf(PlayCollection(selection.first, selection.second)))
    }

    private fun isTrackLoaded(state: AppState, trackId: Long?): Boolean =
        trackId != null && state.playback.currentTrackId == trackId &&
            (state.playback.status == PlaybackStatus.PLAYING || state.playback.status == PlaybackStatus.PAUSED)

    private fun shuffleCollection(state: AppState): Reduction {
        val ids = ScreenContent.playableTrackIds(state)
        return if (ids.isEmpty()) Reduction(state)
        else Reduction(toNowPlaying(state), listOf(PlayCollection(ids, 0, shuffled = true)))
    }


    private fun confirmEmptyScreen(state: AppState): Reduction {
        if (state.library.isScanning) return Reduction(state)
        val storageAvailable = state.device.internalStorageAvailable || state.device.removableStorageAvailable
        return when {
            !storageAvailable -> push(state, Screen.Storage)
            state.currentScreen == Screen.Songs || state.currentScreen == Screen.Albums ||
                state.currentScreen == Screen.Artists ||
                state.currentScreen == Screen.Audiobooks -> push(state, Screen.Storage)
            state.currentScreen == Screen.Queue || state.currentScreen == Screen.Favorites ||
                state.currentScreen == Screen.RecentlyPlayed || state.currentScreen is Screen.PlaylistTracks ||
                state.currentScreen is Screen.Folders || state.currentScreen is Screen.AlbumSongs ||
                state.currentScreen is Screen.ArtistSongs || state.currentScreen is Screen.ArtistAlbums ||
                state.currentScreen is Screen.AudiobookChapters -> back(state)
            // Confirm must never be inert on an empty screen: the empty state always
            // offers an action, so leaving is the safe default.
            else -> back(state)
        }
    }

    private fun openContextOptions(state: AppState): Reduction = when (val screen = state.currentScreen) {
        Screen.Queue -> if (state.playback.queue.isEmpty()) Reduction(state) else push(state, Screen.QueueOptions(state.selectedIndex), selectedIndex = 1)
        Screen.Bluetooth -> {
            val row = ScreenContent.rows(state).getOrNull(state.selectedIndex) as? Action
            val key = row?.key.orEmpty()
            if (key.startsWith("bt_device:")) push(state, Screen.BluetoothDevice(key.substringAfter(':')), selectedIndex = 1)
            else Reduction(state)
        }
        Screen.Songs, Screen.Favorites, Screen.RecentlyPlayed, is Screen.AlbumSongs,
        is Screen.ArtistSongs, is Screen.AudiobookChapters -> {
            val row = ScreenContent.rows(state).getOrNull(state.selectedIndex) as? TrackRow
            if (row == null) Reduction(state) else push(state, Screen.TrackOptions(row.track.id))
        }
        Screen.Audiobooks -> {
            val key = (ScreenContent.rows(state).getOrNull(state.selectedIndex) as? Action)?.key
            if (key == null || !key.startsWith(ScreenContent.AUDIOBOOK_KEY_PREFIX)) Reduction(state)
            else push(state, Screen.AudiobookOptions(key.substringAfter(':')))
        }
        is Screen.Folders -> {
            val row = ScreenContent.rows(state).getOrNull(state.selectedIndex)
            if (row is TrackRow) push(state, Screen.TrackOptions(row.track.id)) else Reduction(state)
        }
        is Screen.PlaylistTracks -> {
            val row = ScreenContent.rows(state).getOrNull(state.selectedIndex) as? TrackRow
            if (row == null) Reduction(state) else push(state, Screen.TrackOptions(row.track.id, screen.playlistId))
        }
        else -> Reduction(state)
    }

    private fun back(state: AppState): Reduction {
        if (state.screenStack.size > 1) return Reduction(pop(state))
        val current = state.currentScreen
        if (current is Screen.Folders) {
            val parent = ScreenContent.parentFolder(current)
            if (parent != null) return Reduction(state.copy(screenStack = listOf(ScreenEntry(parent))))
        }
        return Reduction(state)
    }

    private fun push(state: AppState, screen: Screen, selectedIndex: Int = 0): Reduction =
        Reduction(pushState(state, screen, selectedIndex))
    private fun pushState(state: AppState, screen: Screen, selectedIndex: Int = 0): AppState =
        state.copy(screenStack = state.screenStack + ScreenEntry(screen, selectedIndex))
    private fun pop(state: AppState, count: Int = 1): AppState {
        val keep = (state.screenStack.size - count).coerceAtLeast(1)
        return if (keep == state.screenStack.size) state else state.copy(screenStack = state.screenStack.take(keep))
    }

    private fun toNowPlaying(state: AppState): AppState {
        if (state.currentScreen == Screen.NowPlaying) return state
        val existing = state.screenStack.indexOfLast { it.screen == Screen.NowPlaying }
        return if (existing < 0) pushState(state, Screen.NowPlaying)
        else state.copy(screenStack = state.screenStack.take(existing + 1))
    }
    private fun setSelected(state: AppState, index: Int): AppState = state.copy(screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index))
    private fun normalizeSelection(state: AppState): AppState {
        val count = ScreenContent.rows(state).size
        val first = ListNavigationPolicy.firstSelectableIndex(state.currentScreen, count)
        return setSelected(state, if (count == 0) 0 else state.selectedIndex.coerceIn(first, count - 1))
    }

    private fun preserveSelection(previous: AppState, updated: AppState): AppState {
        val selectedRow = ScreenContent.rows(previous).getOrNull(previous.selectedIndex)
            ?: return normalizeSelection(updated)
        val newRows = ScreenContent.rows(updated)
        val restoredIndex = newRows.indexOfFirst { ScreenContent.sameRowIdentity(selectedRow, it) }
        return if (restoredIndex >= 0) setSelected(updated, restoredIndex) else normalizeSelection(updated)
    }
}
