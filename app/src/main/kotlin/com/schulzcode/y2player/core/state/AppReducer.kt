package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.TrackSortOrder
import com.schulzcode.y2player.core.state.AppEffect.*
import com.schulzcode.y2player.core.state.ScreenRow.Action
import com.schulzcode.y2player.core.state.ScreenRow.Folder
import com.schulzcode.y2player.core.state.ScreenRow.Group
import com.schulzcode.y2player.core.state.ScreenRow.TrackRow

object AppReducer {
    fun reduce(state: AppState, action: AppAction): Reduction = when (action) {
        AppAction.WheelClockwise -> moveSelection(state, 1)
        AppAction.WheelCounterClockwise -> moveSelection(state, -1)
        AppAction.Confirm -> confirm(state)
        AppAction.ConfirmLong -> confirmLong(state)
        AppAction.Back -> back(state)
        // Idempotent by design: pressing HOME on the home screen is a no-op.
        AppAction.NavigateHome -> Reduction(
            if (state.screenStack.size == 1 && state.currentScreen == Screen.MainMenu) state
            else state.copy(screenStack = listOf(ScreenEntry(Screen.MainMenu)))
        )
        AppAction.Left -> left(state)
        AppAction.Right -> right(state)
        AppAction.PlayPause -> Reduction(state, listOf(TogglePlayback))
        AppAction.MediaNext -> Reduction(state, listOf(NextTrack))
        AppAction.MediaPrevious -> Reduction(state, listOf(PreviousTrack))
        AppAction.MediaStop -> if (state.playback.status == com.schulzcode.y2player.core.model.PlaybackStatus.PLAYING || state.playback.status == com.schulzcode.y2player.core.model.PlaybackStatus.PREPARING) Reduction(state, listOf(TogglePlayback)) else Reduction(state)
        AppAction.SeekBackward -> if (state.currentScreen == Screen.NowPlaying) Reduction(state, listOf(SeekBy(-state.preferences.seekStepMs.toLong()))) else Reduction(state)
        AppAction.SeekForward -> if (state.currentScreen == Screen.NowPlaying) Reduction(state, listOf(SeekBy(state.preferences.seekStepMs.toLong()))) else Reduction(state)
        AppAction.SeekBackwardLong -> if (state.currentScreen == Screen.NowPlaying) Reduction(state, listOf(SeekBy(-state.preferences.longSeekStepMs.toLong()))) else Reduction(state)
        AppAction.SeekForwardLong -> if (state.currentScreen == Screen.NowPlaying) Reduction(state, listOf(SeekBy(state.preferences.longSeekStepMs.toLong()))) else Reduction(state)
        is AppAction.LibraryChanged -> Reduction(preserveSelection(state, state.copy(library = action.library)))
        is AppAction.PlaybackChanged -> Reduction(playbackChanged(state, action.playback))
        is AppAction.DeviceChanged -> Reduction(state.copy(device = action.device))
        is AppAction.BluetoothChanged -> Reduction(preserveSelection(state, state.copy(bluetooth = action.bluetooth)))
        is AppAction.DisplayChanged -> Reduction(state.copy(display = action.display))
        is AppAction.PreferencesChanged -> Reduction(state.copy(preferences = action.preferences))
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
        if (state.currentScreen == Screen.NowPlaying) return Reduction(state, listOf(AdjustVolume(if (delta > 0) 1 else -1)))
        val count = ScreenContent.rows(state).size
        return if (count == 0) Reduction(state) else Reduction(setSelected(state, (state.selectedIndex + delta).floorMod(count)))
    }

    private fun confirm(state: AppState): Reduction {
        if (state.currentScreen == Screen.NowPlaying) return Reduction(state, listOf(TogglePlayback))
        val screenRows = ScreenContent.rows(state)
        if (screenRows.isEmpty()) return confirmEmptyScreen(state)
        val row = screenRows.getOrNull(state.selectedIndex) ?: return Reduction(state)
        return when (val screen = state.currentScreen) {
            Screen.MainMenu -> confirmMainMenu(state, row)
            Screen.Songs, Screen.Favorites, Screen.RecentlyPlayed -> playSelected(state)
            is Screen.AlbumSongs, is Screen.ArtistSongs ->
                if ((row as? Action)?.key == "collection_play") playWholeCollection(state) else playSelected(state)
            Screen.Albums -> (row as? Group)?.key?.let { push(state, Screen.AlbumSongs(it)) } ?: Reduction(state)
            Screen.Artists -> (row as? Group)?.key?.let { push(state, Screen.ArtistSongs(it)) } ?: Reduction(state)
            is Screen.Folders -> when (row) {
                is Folder -> push(state, Screen.Folders(row.volumeId, row.relativePath))
                is TrackRow -> playSelected(state)
                else -> Reduction(state)
            }
            Screen.Playlists -> confirmPlaylists(state, row)
            is Screen.PlaylistTracks -> if (row is TrackRow) playSelected(state) else confirmPlaylistAction(state, screen, row)
            is Screen.TrackOptions -> confirmTrackOptions(state, screen, row)
            is Screen.AddToPlaylist -> confirmAddToPlaylist(state, screen.trackId, row)
            is Screen.QueueOptions -> confirmQueueOptions(state, row)
            Screen.Queue -> Reduction(pushState(state, Screen.NowPlaying), listOf(PlayQueueIndex(state.selectedIndex)))
            Screen.NowPlayingOptions -> confirmNowPlayingOptions(state, row)
            Screen.Settings -> confirmSettings(state, row)
            Screen.PlaybackSettings -> confirmPlaybackSettings(state, row)
            Screen.SoundSettings -> confirmSoundSettings(state, row)
            Screen.EqualizerBands -> confirmEqualizerBands(state, row)
            Screen.SortOrder -> confirmSortOrder(state, row)
            Screen.Bluetooth -> confirmBluetooth(state, row)
            Screen.Display -> confirmDisplay(state, row)
            Screen.Brightness -> confirmBrightness(state, row)
            Screen.ScreenTimeout -> confirmTimeout(state, row)
            Screen.Storage -> confirmStorage(state, row)
            Screen.System -> confirmSystem(state, row)
            Screen.Diagnostics -> confirmDiagnostics(state, row)
            Screen.About -> Reduction(state)
            Screen.NowPlaying -> Reduction(state, listOf(TogglePlayback))
        }
    }

    private fun confirmMainMenu(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "songs" -> push(state, Screen.Songs)
            "albums" -> push(state, Screen.Albums)
            "artists" -> push(state, Screen.Artists)
            "playlists" -> push(state, Screen.Playlists)
            "folders" -> push(state, Screen.Folders())
            "shuffle_all" -> Reduction(pushState(state, Screen.NowPlaying), listOf(ShuffleAll))
            "settings" -> push(state, Screen.Settings)
            else -> Reduction(state)
        }
    }

    private fun confirmPlaylists(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when {
            // Favorites and Recently Played are playlists in the user's mental model;
            // they live here instead of occupying home rows.
            key == "playlist_favorites" -> push(state, Screen.Favorites)
            key == "playlist_recent" -> push(state, Screen.RecentlyPlayed)
            key == "playlist_create" -> Reduction(state, listOf(CreatePlaylist))
            key == "playlist_import_m3u" -> Reduction(state, listOf(ImportM3uPlaylists))
            key == "playlist_export_m3u" -> Reduction(state, listOf(ExportM3uPlaylists))
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
        return if (key.startsWith("playlist_delete:")) Reduction(pop(state), listOf(DeletePlaylist(screen.playlistId))) else Reduction(state)
    }

    private fun confirmTrackOptions(state: AppState, screen: Screen.TrackOptions, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        val trackId = screen.trackId
        return when {
            key.startsWith("track_play:") -> Reduction(pushState(pop(state), Screen.NowPlaying), listOf(PlayCollection(listOf(trackId), 0)))
            key.startsWith("track_next:") -> Reduction(pop(state), listOf(PlayNext(trackId)))
            key.startsWith("track_queue:") -> Reduction(pop(state), listOf(AddToQueue(trackId)))
            key.startsWith("track_favorite:") -> Reduction(pop(state), listOf(ToggleFavorite(trackId)))
            key.startsWith("track_playlist:") -> push(state, Screen.AddToPlaylist(trackId))
            key.startsWith("track_remove_playlist:") -> {
                val parts = key.split(':')
                val playlistId = parts.getOrNull(1)?.toLongOrNull() ?: return Reduction(state)
                Reduction(pop(state), listOf(RemoveTrackFromPlaylist(playlistId, trackId)))
            }
            else -> Reduction(state)
        }
    }

    private fun confirmAddToPlaylist(state: AppState, trackId: Long, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        // Leaving Add to Playlist pops both it and the Track Options screen that
        // opened it, landing back on the originating list.
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
            key.startsWith("queue_play:") -> Reduction(pushState(pop(state), Screen.NowPlaying), listOf(PlayQueueIndex(index())))
            key.startsWith("queue_up:") -> Reduction(pop(state), listOf(MoveQueueItem(index(), -1)))
            key.startsWith("queue_down:") -> Reduction(pop(state), listOf(MoveQueueItem(index(), 1)))
            key.startsWith("queue_remove:") -> Reduction(pop(state), listOf(RemoveQueueIndex(index())))
            key == "queue_clear_upcoming" -> Reduction(pop(state), listOf(ClearUpcoming))
            key == "queue_clear" -> Reduction(pop(state), listOf(ClearQueue))
            else -> Reduction(state)
        }
    }

    private fun confirmSettings(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "playback" -> push(state, Screen.PlaybackSettings)
            "sort" -> push(state, Screen.SortOrder)
            "bluetooth" -> push(state, Screen.Bluetooth)
            "display" -> push(state, Screen.Display)
            "storage" -> push(state, Screen.Storage)
            "system" -> push(state, Screen.System)
            else -> Reduction(state)
        }
    }

    private fun confirmSystem(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "diagnostics" -> push(state, Screen.Diagnostics)
            "android_settings" -> Reduction(state, listOf(OpenAndroidSettings))
            "about" -> push(state, Screen.About)
            else -> Reduction(state)
        }
    }

    private fun confirmPlaybackSettings(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "shuffle" -> Reduction(state, listOf(ToggleShuffle))
            "repeat" -> Reduction(state, listOf(CycleRepeat))
            "pause_disconnect" -> Reduction(state, listOf(TogglePauseOnDisconnect))
            "resume_position" -> Reduction(state, listOf(ToggleResumePosition))
            "keep_screen_on" -> Reduction(state, listOf(ToggleKeepScreenOn))
            "gapless" -> Reduction(state, listOf(ToggleGapless))
            "crossfade" -> Reduction(state, listOf(CycleCrossfade))
            "pause_fade" -> Reduction(state, listOf(CyclePauseFade))
            "seek_step" -> Reduction(state, listOf(CycleSeekStep))
            "long_seek_step" -> Reduction(state, listOf(CycleLongSeekStep))
            "previous_threshold" -> Reduction(state, listOf(CyclePreviousThreshold))
            "duck_focus" -> Reduction(state, listOf(ToggleDuckOnFocusLoss))
            "volume_mode" -> Reduction(state, listOf(CycleVolumeMode))
            "sleep_timer" -> Reduction(state, listOf(CycleSleepTimer))
            "sound" -> push(state, Screen.SoundSettings)
            else -> Reduction(state)
        }
    }


    private fun confirmSoundSettings(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "audio_quality" -> Reduction(state, listOf(CycleAudioQuality))
            "effects_toggle" -> Reduction(state, listOf(ToggleAudioEffects))
            "eq_preset" -> Reduction(state, listOf(CycleEqualizerPreset))
            "eq_bands" -> push(state, Screen.EqualizerBands)
            "bass" -> Reduction(state, listOf(CycleBassStrength))
            "loudness" -> Reduction(state, listOf(CycleLoudnessGain))
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

    private fun confirmDisplay(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "brightness" -> push(state, Screen.Brightness)
            "timeout" -> push(state, Screen.ScreenTimeout)
            "keep_screen_on" -> Reduction(state, listOf(ToggleKeepScreenOn))
            "ui_sounds" -> Reduction(state, listOf(ToggleUiSoundEffects))
            "haptics" -> Reduction(state, listOf(CycleHapticLevel))
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

    private fun confirmDiagnostics(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        return when (key) {
            "diag_export" -> Reduction(state, listOf(ExportDiagnostics))
            "diag_formats" -> Reduction(state, listOf(RunFormatProbe))
            "diag_reset_queue" -> Reduction(state, listOf(ClearQueue))
            "diag_reset_library" -> Reduction(state, listOf(ResetLibrary))
            "diag_verbose" -> Reduction(state, listOf(ToggleVerboseDiagnostics))
            "diag_safe_mode" -> Reduction(state, listOf(if (state.safeMode) ExitSafeMode else EnterSafeMode))
            else -> Reduction(state)
        }
    }

    /**
     * Hold-center = contextual options everywhere: the Now Playing menu on the
     * playback screen, and the same context menu a Right-press opens on list rows.
     * One gesture, one meaning.
     */
    private fun confirmLong(state: AppState): Reduction = when (state.currentScreen) {
        Screen.NowPlaying -> push(state, Screen.NowPlayingOptions)
        else -> right(state)
    }

    private fun confirmNowPlayingOptions(state: AppState, row: ScreenRow): Reduction {
        val key = (row as? Action)?.key ?: return Reduction(state)
        val track = state.playback.currentTrackId?.let { state.library.byId[it] }
        return when {
            key == "shuffle" -> Reduction(state, listOf(ToggleShuffle))
            key == "repeat" -> Reduction(state, listOf(CycleRepeat))
            key == "sleep_timer" -> Reduction(state, listOf(CycleSleepTimer))
            key == "queue" -> push(state, Screen.Queue, selectedIndex = state.playback.currentQueueIndex ?: 0)
            key.startsWith("np_favorite:") -> {
                val id = key.substringAfter(':').toLongOrNull() ?: return Reduction(state)
                // Stay on the menu; the row label flips when the library updates.
                Reduction(state, listOf(ToggleFavorite(id)))
            }
            key.startsWith("np_playlist:") -> {
                val id = key.substringAfter(':').toLongOrNull() ?: return Reduction(state)
                push(state, Screen.AddToPlaylist(id))
            }
            key == "np_album" -> track?.let { push(state, Screen.AlbumSongs(it.displayAlbum)) } ?: Reduction(state)
            key == "np_artist" -> track?.let { push(state, Screen.ArtistSongs(it.displayArtist)) } ?: Reduction(state)
            else -> Reduction(state)
        }
    }

    private fun playSelected(state: AppState): Reduction {
        val selection = ScreenContent.selectedTrackCollection(state) ?: return Reduction(state)
        // Music first: starting playback lands on Now Playing; Back returns to the list.
        return Reduction(pushState(state, Screen.NowPlaying), listOf(PlayCollection(selection.first, selection.second)))
    }

    private fun playWholeCollection(state: AppState): Reduction {
        val ids = ScreenContent.playableTrackIds(state)
        return if (ids.isEmpty()) Reduction(state)
        else Reduction(pushState(state, Screen.NowPlaying), listOf(PlayCollection(ids, 0)))
    }

    private fun confirmEmptyScreen(state: AppState): Reduction {
        if (state.library.isScanning) return Reduction(state)
        val storageAvailable = state.device.internalStorageAvailable || state.device.removableStorageAvailable
        return when {
            !storageAvailable -> push(state, Screen.Storage)
            state.currentScreen == Screen.Songs || state.currentScreen == Screen.Albums ||
                state.currentScreen == Screen.Artists -> push(state, Screen.Storage)
            state.currentScreen == Screen.Queue || state.currentScreen == Screen.Favorites ||
                state.currentScreen == Screen.RecentlyPlayed || state.currentScreen is Screen.PlaylistTracks ||
                state.currentScreen is Screen.Folders -> back(state)
            else -> Reduction(state)
        }
    }

    private fun left(state: AppState): Reduction = when (state.currentScreen) {
        Screen.NowPlaying -> Reduction(state, listOf(PreviousTrack))
        Screen.EqualizerBands -> Reduction(state, listOf(AdjustEqualizerBand(state.selectedIndex, -1)))
        else -> back(state)
    }

    private fun right(state: AppState): Reduction = when (val screen = state.currentScreen) {
        // Home's right pane IS the player; Right steps into it when something is loaded.
        Screen.MainMenu -> if (state.playback.currentTrackId != null) push(state, Screen.NowPlaying) else Reduction(state)
        Screen.NowPlaying -> Reduction(state, listOf(NextTrack))
        Screen.EqualizerBands -> Reduction(state, listOf(AdjustEqualizerBand(state.selectedIndex, 1)))
        Screen.Queue -> if (state.playback.queue.isEmpty()) Reduction(state) else push(state, Screen.QueueOptions(state.selectedIndex), selectedIndex = 1)
        Screen.Bluetooth -> {
            val row = ScreenContent.rows(state).getOrNull(state.selectedIndex) as? Action
            val key = row?.key.orEmpty()
            if (key.startsWith("bt_device:")) Reduction(state, listOf(ForgetBluetoothDevice(key.substringAfter(':'))))
            else Reduction(state)
        }
        Screen.Songs, Screen.Favorites, Screen.RecentlyPlayed, is Screen.AlbumSongs, is Screen.ArtistSongs -> {
            val row = ScreenContent.rows(state).getOrNull(state.selectedIndex) as? TrackRow
            if (row == null) deeperNavigation(state) else push(state, Screen.TrackOptions(row.track.id))
        }
        is Screen.Folders -> {
            val row = ScreenContent.rows(state).getOrNull(state.selectedIndex)
            if (row is TrackRow) push(state, Screen.TrackOptions(row.track.id)) else deeperNavigation(state)
        }
        is Screen.PlaylistTracks -> {
            val row = ScreenContent.rows(state).getOrNull(state.selectedIndex) as? TrackRow
            if (row == null) deeperNavigation(state) else push(state, Screen.TrackOptions(row.track.id, screen.playlistId))
        }
        else -> deeperNavigation(state)
    }

    /**
     * Right means "enter the selected child", never "activate this row". Reuse
     * Confirm's screen mapping, but accept only a pure push to a non-player
     * screen. Settings toggles, destructive actions and other leaf rows remain
     * untouched. Now Playing is intentionally reachable by Right only from the
     * split home screen handled above.
     */
    private fun deeperNavigation(state: AppState): Reduction {
        val candidate = confirm(state)
        val pushedChild = candidate.state.screenStack.size > state.screenStack.size
        return if (candidate.effects.isEmpty() && pushedChild && candidate.state.currentScreen != Screen.NowPlaying) {
            candidate
        } else Reduction(state)
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
    private fun pop(state: AppState): AppState = if (state.screenStack.size <= 1) state else state.copy(screenStack = state.screenStack.dropLast(1))
    private fun setSelected(state: AppState, index: Int): AppState = state.copy(screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index))
    private fun normalizeSelection(state: AppState): AppState {
        val count = ScreenContent.rows(state).size
        return setSelected(state, if (count == 0) 0 else state.selectedIndex.coerceIn(0, count - 1))
    }

    private fun preserveSelection(previous: AppState, updated: AppState): AppState {
        val selectedRow = ScreenContent.rows(previous).getOrNull(previous.selectedIndex)
            ?: return normalizeSelection(updated)
        val newRows = ScreenContent.rows(updated)
        val restoredIndex = newRows.indexOfFirst { ScreenContent.sameRowIdentity(selectedRow, it) }
        return if (restoredIndex >= 0) setSelected(updated, restoredIndex) else normalizeSelection(updated)
    }
    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
}
