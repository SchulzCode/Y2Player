package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.BuildConfig
import com.schulzcode.y2player.core.model.AudioCodecLabels
import com.schulzcode.y2player.core.model.AudioQualityMode
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.core.model.TrackSortOrder
import com.schulzcode.y2player.input.HapticLevel
import com.schulzcode.y2player.playback.VolumeCurve
import com.schulzcode.y2player.playback.VolumeMode
import java.io.File
import java.util.Locale

sealed interface ScreenRow {
    val title: String
    val subtitle: String?

    data class Action(override val title: String, override val subtitle: String? = null, val key: String) : ScreenRow
    data class TrackRow(val track: Track) : ScreenRow {
        override val title: String = track.title
        override val subtitle: String = buildString {
            append(track.displayArtist)
            if (!track.available) append(" · unavailable")
            if (track.favorite) append(" · ★")
        }
    }
    data class Group(override val title: String, override val subtitle: String? = null, val key: String) : ScreenRow
    data class Folder(override val title: String, val volumeId: String, val relativePath: String) : ScreenRow {
        // The folder icon already says "folder"; a subtitle repeating it is noise.
        override val subtitle: String? = null
    }
}

object ScreenContent {
    fun title(state: AppState): String = when (val screen = state.currentScreen) {
        Screen.MainMenu -> "Y2 Player"
        Screen.Songs -> "Songs"
        Screen.Favorites -> "Favorites"
        Screen.RecentlyPlayed -> "Recently Played"
        Screen.Albums -> "Albums"
        is Screen.AlbumSongs -> "Album"
        Screen.Artists -> "Artists"
        is Screen.ArtistSongs -> "Artist"
        is Screen.Folders -> if (screen.volumeId == null) "Folders" else screen.relativePath.takeIf { it.isNotBlank() } ?: volumeName(screen.volumeId)
        Screen.Playlists -> "Playlists"
        is Screen.PlaylistTracks -> screen.name
        is Screen.TrackOptions -> "Track Options"
        is Screen.AddToPlaylist -> "Add to Playlist"
        is Screen.QueueOptions -> "Queue Item"
        Screen.NowPlaying -> "Now Playing"
        Screen.NowPlayingOptions -> "Playback Options"
        Screen.Queue -> "Queue"
        Screen.Settings -> "Settings"
        Screen.PlaybackSettings -> "Playback"
        Screen.SoundSettings -> "Sound Effects"
        Screen.EqualizerBands -> "Equalizer Bands"
        Screen.SortOrder -> "Sort Order"
        Screen.Bluetooth -> "Bluetooth"
        Screen.Display -> "Display"
        Screen.Brightness -> "Brightness"
        Screen.ScreenTimeout -> "Screen Timeout"
        Screen.Storage -> "Storage"
        Screen.System -> "System"
        Screen.Diagnostics -> "Diagnostics"
        Screen.About -> "About"
    }

    /**
     * Rows for the current screen, memoised for the screens that can be large.
     *
     * Building these sorts the whole track list, which is capped at 100k items,
     * on the main thread inside the reducer — so a cache miss is the most
     * expensive thing this class can do. The cache holds [ROW_CACHE_ENTRIES]
     * screens rather than one: a single entry was thrashed by ordinary use,
     * because `preserveSelection` asks for the previous state's rows and then
     * the updated state's rows, and moving between two large screens evicted on
     * every step. Keeping a handful makes back-navigation and the
     * previous/updated pair free.
     */
    @Synchronized
    fun rows(state: AppState): List<ScreenRow> {
        if (!isLargeScreen(state.currentScreen)) return buildRows(state)
        val key = LargeRowsKey(
            screen = state.currentScreen,
            contentRevision = contentRevision(state),
            indexIdentity = System.identityHashCode(state.library.index),
            sortOrder = state.preferences.sortOrder,
            queueFingerprint = if (state.currentScreen == Screen.Queue) System.identityHashCode(state.playback.queue) else 0
        )
        cachedRows[key]?.let { return it }
        return buildRows(state).also { rows ->
            // Least-recently-used eviction: access order is maintained by the
            // map itself, so the entry dropped is the one longest unused.
            if (cachedRows.size >= ROW_CACHE_ENTRIES) {
                cachedRows.keys.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
            }
            cachedRows[key] = rows
        }
    }

    /**
     * Track-derived screens key their cache on [LibraryState.tracksRevision] so the
     * recently-played bump at every track transition (which only changes `revision`)
     * cannot invalidate — and re-sort on the main thread — a 30k-row screen. Screens
     * built from playlists or the recently-played list still key on `revision`.
     */
    private fun contentRevision(state: AppState): Long = when (state.currentScreen) {
        Screen.RecentlyPlayed, Screen.Playlists, is Screen.PlaylistTracks -> state.library.revision
        else -> state.library.tracksRevision
    }

    private fun buildRows(state: AppState): List<ScreenRow> = when (val screen = state.currentScreen) {
        Screen.MainMenu -> mainMenuRows(state)
        Screen.Songs -> sorted(state.library.availableTracks, state.preferences.sortOrder).map(ScreenRow::TrackRow)
        Screen.Favorites -> sorted(state.library.favoriteTracks, state.preferences.sortOrder).map(ScreenRow::TrackRow)
        Screen.RecentlyPlayed -> state.library.recentlyPlayed.map(ScreenRow::TrackRow)
        Screen.Albums -> albumRows(state.library.availableTracks)
        is Screen.AlbumSongs -> albumDetailRows(state.library.availableTracks, screen.album)
        Screen.Artists -> artistRows(state.library.availableTracks)
        is Screen.ArtistSongs -> artistDetailRows(state.library.availableTracks, screen.artist)
        is Screen.Folders -> folderRows(state.library.availableTracks, screen)
        Screen.Playlists -> playlistRows(state)
        is Screen.PlaylistTracks -> playlistTrackRows(state, screen)
        is Screen.TrackOptions -> trackOptionRows(state, screen)
        is Screen.AddToPlaylist -> addToPlaylistRows(state)
        is Screen.QueueOptions -> queueOptionRows(state, screen.queueIndex)
        Screen.Queue -> queueRows(state)
        Screen.NowPlaying -> emptyList()
        Screen.NowPlayingOptions -> nowPlayingOptionsRows(state)
        Screen.Settings -> settingsRows(state)
        Screen.PlaybackSettings -> playbackRows(state)
        Screen.SoundSettings -> soundRows(state)
        Screen.EqualizerBands -> equalizerBandRows(state)
        Screen.SortOrder -> sortOrderRows(state)
        Screen.Bluetooth -> bluetoothRows(state)
        Screen.Display -> displayRows(state)
        Screen.Brightness -> brightnessRows(state)
        Screen.ScreenTimeout -> timeoutRows(state)
        Screen.Storage -> storageRows(state)
        Screen.System -> systemRows(state)
        Screen.Diagnostics -> diagnosticsRows(state)
        Screen.About -> aboutRows(state)
    }

    /**
     * The playable track ids on the current screen, plus the position of the
     * selected one.
     *
     * One pass and one list. The previous three-stage pipeline
     * (`filterIsInstance` → `filter` → `map`, then `indexOf`) allocated three
     * lists the size of the screen and then scanned one of them, so pressing
     * play on a 30k-track Songs list did roughly 90,000 list writes and boxed
     * 30,000 longs on the main thread before playback even started.
     */
    fun selectedTrackCollection(state: AppState): Pair<List<Long>, Int>? {
        val rows = rows(state)
        val selected = rows.getOrNull(state.selectedIndex) as? ScreenRow.TrackRow ?: return null
        val ids = ArrayList<Long>(rows.size)
        var index = -1
        rows.forEach { row ->
            if (row !is ScreenRow.TrackRow || !row.track.available) return@forEach
            if (row.track.id == selected.track.id && index < 0) index = ids.size
            ids.add(row.track.id)
        }
        return if (index >= 0) ids to index else null
    }

    /** Every playable track id on the current screen, in display order. */
    fun playableTrackIds(state: AppState): List<Long> {
        val rows = rows(state)
        val ids = ArrayList<Long>(rows.size)
        rows.forEach { row ->
            if (row is ScreenRow.TrackRow && row.track.available) ids.add(row.track.id)
        }
        return ids
    }

    /**
     * Stable identity used to restore wheel focus after a live list refresh.
     * Allocation-free, because it runs across potentially large refreshed lists.
     */
    fun sameRowIdentity(first: ScreenRow, second: ScreenRow): Boolean = when {
        first is ScreenRow.Action && second is ScreenRow.Action -> first.key == second.key
        first is ScreenRow.TrackRow && second is ScreenRow.TrackRow -> first.track.id == second.track.id
        first is ScreenRow.Group && second is ScreenRow.Group -> first.key == second.key
        first is ScreenRow.Folder && second is ScreenRow.Folder ->
            first.volumeId == second.volumeId && first.relativePath == second.relativePath
        else -> false
    }

    /**
     * Home contains browsing categories only. The playing track stays in the player
     * pane, queue controls live in Playback Options, and smart playlists live under
     * Playlists.
     */
    private fun mainMenuRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Songs", null, "songs"),
        ScreenRow.Action("Albums", null, "albums"),
        ScreenRow.Action("Artists", null, "artists"),
        ScreenRow.Action("Playlists", null, "playlists"),
        ScreenRow.Action("Folders", null, "folders"),
        ScreenRow.Action("Shuffle All", null, "shuffle_all"),
        ScreenRow.Action("Settings", if (state.safeMode) "SAFE MODE" else null, "settings")
    )

    /** Smart playlists first (played daily), user playlists next, maintenance last. */
    private fun playlistRows(state: AppState): List<ScreenRow> = buildList {
        add(ScreenRow.Action("Favorites", trackCountLabel(state.library.favoriteTracks.size), "playlist_favorites"))
        add(ScreenRow.Action("Recently Played", trackCountLabel(state.library.recentlyPlayed.size), "playlist_recent"))
        state.library.playlists.forEach { add(ScreenRow.Action(it.name, trackCountLabel(it.trackCount), "playlist:${it.id}")) }
        add(ScreenRow.Action("New Playlist", null, "playlist_create"))
        add(ScreenRow.Action("Import M3U Playlists", "Find M3U/M3U8 files on music storage", "playlist_import_m3u"))
        add(ScreenRow.Action("Export M3U Playlists", "Write UTF-8 playlists to Y2Player/Playlists", "playlist_export_m3u"))
    }

    private fun playlistTrackRows(state: AppState, screen: Screen.PlaylistTracks): List<ScreenRow> = buildList {
        val tracks = state.library.playlistTrackIds[screen.playlistId].orEmpty().mapNotNull(state.library.byId::get)
        if (tracks.isEmpty()) add(ScreenRow.Group("Playlist is empty", "Add tracks from Track Options", "playlist_empty"))
        else tracks.forEach { add(ScreenRow.TrackRow(it)) }
        add(ScreenRow.Action("Delete Playlist", "Removes this playlist, not its music", "playlist_delete:${screen.playlistId}"))
    }

    private fun trackOptionRows(state: AppState, screen: Screen.TrackOptions): List<ScreenRow> {
        val trackId = screen.trackId
        val track = state.library.byId[trackId] ?: return listOf(ScreenRow.Group("Track unavailable", null, "missing"))
        return buildList {
            add(ScreenRow.Action("Play Now", track.title, "track_play:$trackId"))
            add(ScreenRow.Action("Play Next", null, "track_next:$trackId"))
            add(ScreenRow.Action("Add to Queue", null, "track_queue:$trackId"))
            add(ScreenRow.Action(if (track.favorite) "Remove Favorite" else "Add Favorite", null, "track_favorite:$trackId"))
            add(ScreenRow.Action("Add to Playlist", null, "track_playlist:$trackId"))
            screen.sourcePlaylistId?.let { playlistId ->
                add(ScreenRow.Action("Remove from Playlist", "The music file is kept", "track_remove_playlist:$playlistId:$trackId"))
            }
            add(ScreenRow.Group("Artist", track.displayArtist, "info_artist"))
            add(ScreenRow.Group("Album", track.displayAlbum, "info_album"))
            add(ScreenRow.Group("Format", formatTrack(state, track), "info_format"))
            add(ScreenRow.Group("Location", track.relativePath, "info_path"))
        }
    }

    private fun addToPlaylistRows(state: AppState): List<ScreenRow> = buildList {
        add(ScreenRow.Action("New Playlist", null, "playlist_create_and_add"))
        state.library.playlists.forEach { add(ScreenRow.Action(it.name, trackCountLabel(it.trackCount), "playlist_add:${it.id}")) }
    }

    /**
     * Queue rows stay 1:1 with queue indices: Confirm dispatches
     * PlayQueueIndex(selectedIndex) and Right opens QueueOptions(selectedIndex).
     * Unknown ids therefore render as placeholders instead of being omitted.
     */
    private fun queueRows(state: AppState): List<ScreenRow> = state.playback.queue.mapIndexed { index, id ->
        state.library.byId[id]?.let(ScreenRow::TrackRow)
            ?: ScreenRow.Group("Unavailable track", "Not in the current library", "queue_missing:$index")
    }

    private fun queueOptionRows(state: AppState, index: Int): List<ScreenRow> {
        val track = state.playback.queue.getOrNull(index)?.let(state.library.byId::get)
        return listOf(
            ScreenRow.Group(track?.title ?: "Unknown track", track?.displayArtist, "queue_track"),
            ScreenRow.Action("Play Now", null, "queue_play:$index"),
            ScreenRow.Action("Move Up", null, "queue_up:$index"),
            ScreenRow.Action("Move Down", null, "queue_down:$index"),
            ScreenRow.Action("Remove", null, "queue_remove:$index"),
            ScreenRow.Action("Clear Upcoming", null, "queue_clear_upcoming"),
            ScreenRow.Action("Clear Queue", null, "queue_clear")
        )
    }

    /**
     * Hold-center menu on Now Playing. Puts every playback control the engine
     * supports one gesture away from the playing screen: shuffle, repeat, favorite,
     * queue, sleep timer, add-to-playlist and album/artist navigation, which are
     * otherwise reachable only through Settings or the library lists.
     */
    private fun nowPlayingOptionsRows(state: AppState): List<ScreenRow> {
        val track = state.playback.currentTrackId?.let(state.library.byId::get)
        return buildList {
            add(ScreenRow.Action("Shuffle", onOff(state.playback.shuffleEnabled), "shuffle"))
            add(ScreenRow.Action(
                "Repeat",
                state.playback.repeatMode.name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) },
                "repeat"
            ))
            if (track != null) {
                add(ScreenRow.Action(
                    if (track.favorite) "Remove Favorite" else "Add Favorite",
                    track.title,
                    "np_favorite:${track.id}"
                ))
            }
            add(ScreenRow.Group("Playing Next", playingNextLabel(state), "np_next"))
            add(ScreenRow.Action("Queue", queuePositionLabel(state), "queue"))
            add(ScreenRow.Action("Sleep Timer", sleepTimerSubtitle(state), "sleep_timer"))
            if (track != null) {
                add(ScreenRow.Action("Add to Playlist", null, "np_playlist:${track.id}"))
                add(ScreenRow.Action("Go to Album", track.displayAlbum, "np_album"))
                add(ScreenRow.Action("Go to Artist", track.displayArtist, "np_artist"))
            }
        }
    }

    /** Best-effort preview of the next track without exposing engine internals. */
    private fun playingNextLabel(state: AppState): String {
        val playback = state.playback
        if (playback.queue.isEmpty()) return "Queue is empty"
        if (playback.repeatMode == RepeatMode.ONE) {
            return state.library.byId[playback.currentTrackId]?.title?.let { "$it · repeat one" } ?: "Current track repeats"
        }
        // The preloaded track is authoritative (it honors shuffle order); otherwise
        // derive from the visible queue, which is exact when shuffle is off.
        val nextId = playback.nextTrackId ?: run {
            if (playback.shuffleEnabled) null
            else playback.currentQueueIndex?.let { index ->
                playback.queue.getOrNull(index + 1)
                    ?: if (playback.repeatMode == RepeatMode.ALL) playback.queue.firstOrNull() else null
            }
        }
        return when {
            nextId == null -> if (playback.shuffleEnabled) "Decided by shuffle" else "End of queue"
            else -> state.library.byId[nextId]?.title ?: "Unavailable track"
        }
    }

    private fun queuePositionLabel(state: AppState): String {
        val size = state.playback.queue.size
        val index = state.playback.currentQueueIndex
        return if (size == 0) "Empty" else if (index == null) trackCountLabel(size) else "${index + 1} of $size"
    }

    /**
     * The six settings remain one short wheel list. Rescan lived here and in Storage;
     * the duplicate is gone. Diagnostics, Android settings and About (visited only
     * occasionally) stay grouped under System.
     */
    private fun settingsRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Playback", playbackSummary(state), "playback"),
        ScreenRow.Action("Sort Order", sortLabel(state.preferences.sortOrder), "sort"),
        ScreenRow.Action("Bluetooth", bluetoothSummary(state), "bluetooth"),
        ScreenRow.Action("Display", "${state.display.brightnessPercent}% · ${timeoutLabel(state.display.screenTimeoutMs)}", "display"),
        ScreenRow.Action("Storage", scanSubtitle(state), "storage"),
        ScreenRow.Action("System", "Diagnostics · Android settings · About", "system")
    )

    /**
     * Y2Player deliberately owns every music-related setting, but once it is the
     * only launcher on the device it is also the only route to the platform
     * Settings app. Without this row, date/time, locale, factory reset and
     * framework-level recovery become unreachable — so one low-prominence entry
     * lives here, deep in System, where a listener will not stumble into it.
     */
    private fun systemRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Diagnostics", if (state.diagnostics.formatProbeRunning) "Format test running" else "Logs and hardware tests", "diagnostics"),
        ScreenRow.Action("Android Settings", "System configuration and recovery", "android_settings"),
        ScreenRow.Action("About", "Y2 Player ${BuildConfig.VERSION_NAME}", "about")
    )

    private fun playbackRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Action("Shuffle", onOff(state.playback.shuffleEnabled), "shuffle"),
        ScreenRow.Action("Repeat", state.playback.repeatMode.name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }, "repeat"),
        ScreenRow.Action(
            "Gapless Playback",
            if (state.preferences.crossfadeMs > 0) "Crossfade takes priority" else onOff(state.preferences.gaplessEnabled),
            "gapless"
        ),
        ScreenRow.Action("Crossfade", millisecondsLabel(state.preferences.crossfadeMs), "crossfade"),
        ScreenRow.Action("Pause / Resume Fade", millisecondsLabel(state.preferences.pauseResumeFadeMs), "pause_fade"),
        ScreenRow.Action("Seek Step", secondsLabel(state.preferences.seekStepMs), "seek_step"),
        ScreenRow.Action("Hold Seek Step", secondsLabel(state.preferences.longSeekStepMs), "long_seek_step"),
        ScreenRow.Action("Previous Restarts Track", thresholdLabel(state.preferences.previousRestartThresholdMs), "previous_threshold"),
        ScreenRow.Action("Focus Ducking", if (state.preferences.duckOnFocusLoss) "Lower volume" else "Pause", "duck_focus"),
        ScreenRow.Action("Volume Control", volumeModeLabel(state), "volume_mode"),
        ScreenRow.Action("Sleep Timer", sleepTimerSubtitle(state), "sleep_timer"),
        ScreenRow.Action("Sound Effects", soundSummary(state), "sound"),
        ScreenRow.Action(
            "Wired Speaker Fallback",
            if (state.preferences.pauseOnDisconnect) "Off · wired unplug pauses" else "On · Bluetooth still always pauses",
            "pause_disconnect"
        ),
        ScreenRow.Action("Resume Position", onOff(state.preferences.resumePosition), "resume_position"),
        ScreenRow.Action("Keep Screen On", onOff(state.preferences.keepScreenOnWhilePlaying), "keep_screen_on")
    )

    private fun soundRows(state: AppState): List<ScreenRow> {
        val effects = state.playback.audioEffects
        val dac = state.playback.dac
        val direct = state.preferences.audioQualityMode == AudioQualityMode.DIRECT_DAC
        val preset = when {
            !effects.equalizerSupported -> "Unsupported"
            state.preferences.equalizerPreset < 0 -> "Custom"
            else -> effects.presetNames.getOrNull(state.preferences.equalizerPreset) ?: "Preset ${state.preferences.equalizerPreset + 1}"
        }
        return buildList {
            add(ScreenRow.Action("Audio Quality", state.preferences.audioQualityMode.label, "audio_quality"))
            add(ScreenRow.Group(
                "CS43131 DAC",
                when {
                    !dac.detected -> "Not detected through firmware"
                    dac.hiFiRequestAccepted -> "Detected · Hi-Fi route requested"
                    direct -> "Detected · vendor route unavailable"
                    else -> "Detected"
                },
                "dac_status"
            ))
            val route = buildList {
                dac.outputSampleRate?.let { add("${it / 1000.0} kHz") }
                dac.outputFormat?.let(::add)
            }.ifEmpty { listOf("Firmware route not reported") }.joinToString(" · ")
            add(ScreenRow.Group("Android Output", route, "dac_output"))
            dac.limitation?.let { add(ScreenRow.Group("Firmware Limit", it, "dac_limit")) }
            if (direct) {
                add(ScreenRow.Group("Direct-mode DSP", "Effects, crossfade and fades are bypassed during playback", "dac_bypass"))
            }
            if (!effects.available) {
                add(ScreenRow.Group("Sound Effects", effects.errorMessage ?: "No compatible Android audio effects found", "effects_unavailable"))
                return@buildList
            }
            add(ScreenRow.Action("Sound Effects", if (direct) "Bypassed by Direct DAC" else onOff(state.preferences.audioEffectsEnabled), "effects_toggle"))
            if (effects.equalizerSupported) {
                add(ScreenRow.Action("Equalizer Preset", preset, "eq_preset"))
                add(ScreenRow.Action("Custom Equalizer", "${effects.bandFrequenciesHz.size} bands · use left/right", "eq_bands"))
            } else add(ScreenRow.Group("Equalizer", "Unsupported by this firmware", "eq_unsupported"))
            if (effects.bassBoostSupported) add(ScreenRow.Action("Bass Boost", percent(state.preferences.bassStrength, 1000), "bass"))
            else add(ScreenRow.Group("Bass Boost", "Unsupported by this firmware", "bass_unsupported"))
            if (effects.loudnessSupported) add(ScreenRow.Action("Loudness", gainLabel(state.preferences.loudnessGainMb), "loudness"))
            else add(ScreenRow.Group("Loudness", "Unsupported by this firmware", "loudness_unsupported"))
            effects.errorMessage?.let { add(ScreenRow.Group("Last effect error", it, "effects_error")) }
        }
    }

    private fun equalizerBandRows(state: AppState): List<ScreenRow> {
        val effects = state.playback.audioEffects
        return effects.bandFrequenciesHz.mapIndexed { index, frequency ->
            val level = effects.bandLevelsMb.getOrNull(index) ?: state.preferences.equalizerBandLevelsMb.getOrNull(index) ?: 0
            ScreenRow.Action(formatFrequency(frequency), signedDb(level), "eq_band:$index")
        }
    }

    private fun sortOrderRows(state: AppState): List<ScreenRow> = TrackSortOrder.entries.map { order ->
        // The accent active-state tint marks the current choice; a "Current" subtitle
        // would say the same thing twice.
        ScreenRow.Action(sortLabel(order), null, "sort:${order.storageId}")
    }

    private fun bluetoothRows(state: AppState): List<ScreenRow> = buildList {
        add(ScreenRow.Action("Bluetooth", when (state.bluetooth.adapterMode) {
            BluetoothAdapterMode.UNSUPPORTED -> "Unavailable"
            BluetoothAdapterMode.OFF -> "Off"
            BluetoothAdapterMode.TURNING_ON -> "Turning on…"
            BluetoothAdapterMode.ON -> "On"
            BluetoothAdapterMode.TURNING_OFF -> "Turning off…"
        }, "bt_toggle"))
        if (state.bluetooth.adapterMode == BluetoothAdapterMode.ON) {
            add(ScreenRow.Action(if (state.bluetooth.isDiscovering) "Stop Scan" else "Scan for Devices", state.bluetooth.pendingOperation ?: "Audio devices only", "bt_scan"))
            add(ScreenRow.Action("Refresh Audio Service", "Reacquire A2DP backend", "bt_refresh"))
            state.bluetooth.devices.forEach { device ->
                val subtitle = when {
                    device.audioStreaming -> "Streaming audio · right to forget"
                    device.linkState == BluetoothLinkState.CONNECTED -> "Connected for audio · right to forget"
                    device.linkState == BluetoothLinkState.CONNECTING -> "Connecting…"
                    device.linkState == BluetoothLinkState.DISCONNECTING -> "Disconnecting…"
                    device.bonding -> "Pairing…"
                    device.bonded -> "Paired · center to connect · right to forget"
                    else -> "Nearby · center to pair"
                }
                val maskedIdentity = "…${device.address.takeLast(5)}"
                add(ScreenRow.Action("${device.name} · $maskedIdentity", subtitle, "bt_device:${device.address}"))
            }
            if (state.bluetooth.devices.isEmpty() && !state.bluetooth.isDiscovering) add(ScreenRow.Group("No audio devices", "Select Scan for Devices", "bt_empty"))
        }
    }

    private fun displayRows(state: AppState): List<ScreenRow> = buildList {
        add(ScreenRow.Action("Brightness", "${state.display.brightnessPercent}%", "brightness"))
        add(ScreenRow.Action("Screen Timeout", timeoutLabel(state.display.screenTimeoutMs), "timeout"))
        add(ScreenRow.Action("Keep Display On", if (state.preferences.keepScreenOnWhilePlaying) "While playing" else "Off", "keep_screen_on"))
        add(
            ScreenRow.Action(
                "System UI Sounds",
                if (state.preferences.uiSoundEffectsEnabled) "On" else "Off · recommended",
                "ui_sounds"
            )
        )
        // Omitted entirely when the device has no motor: a setting that provably
        // cannot do anything is worse than no setting.
        if (state.device.hapticsAvailable) {
            add(ScreenRow.Action("Wheel Haptics", hapticLabel(state.preferences.hapticLevel), "haptics"))
        }
    }

    /**
     * States the pulse duration because that is literally what the level is.
     * API 19 has no amplitude control, so calling these "intensities" would be a
     * lie the user could feel.
     */
    private fun hapticLabel(level: HapticLevel): String =
        if (level == HapticLevel.OFF) "Off" else "${level.label} · ${level.durationMs} ms pulse"

    private fun brightnessRows(state: AppState): List<ScreenRow> = BRIGHTNESS_LEVELS.map { percent ->
        ScreenRow.Action("$percent%", null, "brightness:$percent")
    }

    private fun timeoutRows(state: AppState): List<ScreenRow> = TIMEOUT_LEVELS.map { timeout ->
        ScreenRow.Action(timeoutLabel(timeout), null, "timeout:$timeout")
    }

    private fun storageRows(state: AppState): List<ScreenRow> = buildList {
        state.device.storageVolumes.forEach { volume ->
            val count = state.library.tracks.count { it.volumeId == volume.id && it.available }
            val subtitle = if (volume.available) "${trackCountLabel(count)} · ${formatBytes(volume.freeBytes)} free of ${formatBytes(volume.totalBytes)}" else "Not mounted · metadata retained"
            add(ScreenRow.Action(volume.label, subtitle, "storage:${volume.id}"))
        }
        add(ScreenRow.Action("Rescan Library", scanSubtitle(state), "rescan"))
    }

    private fun diagnosticsRows(state: AppState): List<ScreenRow> = buildList {
        add(ScreenRow.Action("Export Diagnostics", state.diagnostics.exportedPath ?: "Save logs to internal storage", "diag_export"))
        add(ScreenRow.Action("Run Format Test", if (state.diagnostics.formatProbeRunning) "Testing…" else "Muted prepare/play/seek test", "diag_formats"))
        add(ScreenRow.Action(
            "Verbose Diagnostics",
            if (state.preferences.verboseDiagnostics) "On · detailed event log" else "Off · errors only",
            "diag_verbose"
        ))
        // Read-only stock-firmware status; this app never switches USB modes.
        add(ScreenRow.Group("USB", state.diagnostics.usb.summary(), "diag_usb"))
        add(ScreenRow.Action("Clear Queue", "Playback queue and session", "diag_reset_queue"))
        add(ScreenRow.Action("Reset Library", "Index, playlists and history", "diag_reset_library"))
        add(ScreenRow.Action(if (state.safeMode) "Exit Safe Mode" else "Enter Safe Mode", if (state.safeMode) "Normal startup on next launch" else "Disable auto scan and restore", "diag_safe_mode"))
        state.diagnostics.formatProbeResults.forEach { result ->
            add(ScreenRow.Group("${if (result.success) "✓" else "✗"} ${result.extension}", result.message, "probe:${result.extension}"))
        }
        state.diagnostics.recentLines.takeLast(12).reversed().forEachIndexed { index, line ->
            add(ScreenRow.Group("Log ${index + 1}", line.take(120), "log:$index"))
        }
    }

    private fun aboutRows(state: AppState): List<ScreenRow> = listOf(
        ScreenRow.Group("Y2 Player", "Version ${BuildConfig.VERSION_NAME}", "about_app"),
        ScreenRow.Group("Device", state.device.deviceModel, "about_device"),
        ScreenRow.Group("System", state.device.androidVersion, "about_android"),
        ScreenRow.Group("Firmware", state.device.firmwareBuild, "about_firmware"),
        ScreenRow.Group("Library", "${state.library.availableTracks.size} available · ${state.library.totalIndexedTracks} indexed", "about_library"),
        ScreenRow.Group("Uptime", formatUptime(state.device.uptimeMs), "about_uptime"),
        ScreenRow.Group("Build", "Local-only · API 19 · no network", "about_build")
    )

    private fun albumRows(tracks: List<Track>): List<ScreenRow> {
        val albums = LinkedHashMap<String, AlbumAccumulator>()
        tracks.forEach { track ->
            val album = track.displayAlbum
            val artist = track.displayArtist
            val value = albums[album]
            if (value == null) albums[album] = AlbumAccumulator(1, artist)
            else {
                value.count += 1
                if (value.singleArtist != artist) value.singleArtist = null
            }
        }
        return albums.entries.sortedWith { first, second -> compareText(first.key, second.key) }.map { (album, value) ->
            ScreenRow.Group(album, value.singleArtist ?: trackCountLabel(value.count), album)
        }
    }

    private fun albumDetailRows(
        tracks: List<Track>,
        album: String
    ): List<ScreenRow> {
        return albumSorted(
            tracks.filter { it.displayAlbum == album }
        ).map(ScreenRow::TrackRow)
    }

    private fun artistRows(tracks: List<Track>): List<ScreenRow> {
        val counts = LinkedHashMap<String, Int>()
        tracks.forEach { track -> counts[track.displayArtist] = (counts[track.displayArtist] ?: 0) + 1 }
        return counts.entries.sortedWith { first, second -> compareText(first.key, second.key) }.map { (artist, count) ->
            ScreenRow.Group(artist, trackCountLabel(count), artist)
        }
    }

    private fun artistDetailRows(
        tracks: List<Track>,
        artist: String
    ): List<ScreenRow> {
        return artistSorted(
            tracks.filter { it.displayArtist == artist }
        ).map(ScreenRow::TrackRow)
    }

    private fun folderRows(tracks: List<Track>, screen: Screen.Folders): List<ScreenRow> {
        if (screen.volumeId == null) return tracks.groupBy { it.volumeId }.keys.sorted().map { ScreenRow.Folder(volumeName(it), it, "") }
        val prefix = screen.relativePath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val matching = tracks.filter { it.volumeId == screen.volumeId && it.relativePath.startsWith(prefix) }
        val folders = linkedSetOf<String>()
        val directTracks = mutableListOf<Track>()
        matching.forEach { track ->
            val remainder = track.relativePath.removePrefix(prefix)
            val separator = remainder.indexOf('/')
            if (separator >= 0) folders += remainder.substring(0, separator) else directTracks += track
        }
        return buildList {
            folders.sortedWith(::compareText).forEach { add(ScreenRow.Folder(it, screen.volumeId, (prefix + it).trim('/'))) }
            sorted(directTracks, TrackSortOrder.TITLE).forEach { add(ScreenRow.TrackRow(it)) }
        }
    }

    private fun sorted(tracks: List<Track>, order: TrackSortOrder): List<Track> = when (order) {
        TrackSortOrder.TITLE -> tracks.sortedWith { first, second ->
            compareText(first.title, second.title).takeUnless { it == 0 }
                ?: compareText(first.displayArtist, second.displayArtist)
        }
        TrackSortOrder.ARTIST -> tracks.sortedWith { first, second ->
            compareText(first.displayArtist, second.displayArtist).takeUnless { it == 0 }
                ?: compareText(first.title, second.title)
        }
        TrackSortOrder.ALBUM -> tracks.sortedWith { first, second ->
            compareText(first.displayAlbum, second.displayAlbum).takeUnless { it == 0 }
                ?: compareValues(first.discNumber ?: 0, second.discNumber ?: 0).takeUnless { it == 0 }
                ?: compareValues(first.trackNumber ?: Int.MAX_VALUE, second.trackNumber ?: Int.MAX_VALUE)
        }
        TrackSortOrder.ADDED -> tracks.sortedByDescending { it.addedAt }
        TrackSortOrder.RECENT -> tracks.sortedByDescending { it.modifiedAt }
    }

    private fun albumSorted(tracks: List<Track>): List<Track> = tracks.sortedWith { first, second ->
        compareValues(first.discNumber ?: 0, second.discNumber ?: 0).takeUnless { it == 0 }
            ?: compareValues(first.trackNumber ?: Int.MAX_VALUE, second.trackNumber ?: Int.MAX_VALUE).takeUnless { it == 0 }
            ?: compareText(first.title, second.title)
    }
    private fun artistSorted(tracks: List<Track>): List<Track> = tracks.sortedWith { first, second ->
        compareText(first.displayAlbum, second.displayAlbum).takeUnless { it == 0 }
            ?: compareValues(first.discNumber ?: 0, second.discNumber ?: 0).takeUnless { it == 0 }
            ?: compareValues(first.trackNumber ?: Int.MAX_VALUE, second.trackNumber ?: Int.MAX_VALUE).takeUnless { it == 0 }
            ?: compareText(first.title, second.title)
    }
    private fun compareText(first: String, second: String): Int = String.CASE_INSENSITIVE_ORDER.compare(first, second)
    private fun playbackSummary(state: AppState): String = buildList {
        if (state.playback.shuffleEnabled) add("Shuffle")
        if (state.playback.repeatMode != RepeatMode.OFF) add("Repeat ${state.playback.repeatMode.name.lowercase(Locale.US)}")
        if (state.preferences.crossfadeMs > 0) add("${state.preferences.crossfadeMs / 1000}s crossfade")
        else if (state.preferences.gaplessEnabled) add("Gapless")
    }.ifEmpty { listOf("Standard") }.joinToString(" · ")

    private fun bluetoothSummary(state: AppState): String = when {
        state.bluetooth.adapterMode == BluetoothAdapterMode.UNSUPPORTED -> "Unavailable"
        state.bluetooth.adapterMode == BluetoothAdapterMode.OFF -> "Off"
        state.bluetooth.audioStreaming -> "Streaming"
        state.bluetooth.audioConnected -> state.bluetooth.devices.firstOrNull { it.linkState == BluetoothLinkState.CONNECTED }?.name ?: "Audio connected"
        state.bluetooth.adapterMode == BluetoothAdapterMode.ON -> "On · not connected"
        else -> "Changing state…"
    }

    private fun scanSubtitle(state: AppState): String = if (state.library.isScanning) {
        "${state.library.scanProgress.processedFiles} files · ${state.library.scanProgress.currentPath?.substringAfterLast('/') ?: "scanning"}"
    } else "${state.library.availableTracks.size} available tracks"

    private fun formatTrack(state: AppState, track: Track): String = buildList {
        val extension = track.extension.uppercase(Locale.US)
        add(AudioCodecLabels.label(track.codec, track.extension))
        track.sampleRate?.let { add("${it / 1000.0} kHz") }
        track.bitDepth?.let { add("$it-bit") }
        if (track.durationMs > 0) add(duration(track.durationMs))
        val probe = state.diagnostics.formatProbeResults.firstOrNull { it.extension.equals(extension, true) }
        add(when {
            probe == null -> "discovered · not device-tested"
            probe.success -> "device-tested ✓"
            else -> "device test failed ✗"
        })
    }.joinToString(" · ")

    private fun duration(ms: Long): String {
        val seconds = ms / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun sortLabel(order: TrackSortOrder): String = when (order) {
        TrackSortOrder.TITLE -> "Title"
        TrackSortOrder.ARTIST -> "Artist"
        TrackSortOrder.ALBUM -> "Album"
        TrackSortOrder.ADDED -> "Recently Added"
        TrackSortOrder.RECENT -> "File Modified"
    }


    private fun millisecondsLabel(value: Int): String = if (value <= 0) "Off" else if (value < 1_000) "${value} ms" else "${value / 1_000} seconds"
    private fun secondsLabel(value: Int): String = "${value / 1_000} seconds"
    private fun thresholdLabel(value: Int): String = if (value <= 0) "Always previous" else "After ${value / 1_000} seconds"
    private fun sleepTimerLabel(state: AppState): String {
        val mode = state.playback.sleepTimerMode
        val remaining = state.playback.sleepTimerRemainingMs
        return if (remaining != null && remaining > 0) "${mode.label} · ${duration(remaining)} left" else mode.label
    }
    private fun sleepTimerSubtitle(state: AppState): String =
        "${sleepTimerLabel(state)} · Stops playback after the selected time"
    /**
     * Shows the level alongside the mode, so a user who has attenuated in-app can
     * see why the device is quiet without opening a second screen.
     */
    private fun volumeModeLabel(state: AppState): String = when (state.preferences.volumeMode) {
        VolumeMode.SYSTEM -> "System · hardware keys"
        VolumeMode.PERCEPTUAL -> "In-app · ${VolumeCurve.percentForLevel(state.preferences.volumeLevel)}%"
    }

    private fun soundSummary(state: AppState): String = when {
        !state.playback.audioEffects.available -> "Unavailable"
        !state.preferences.audioEffectsEnabled -> "Off"
        else -> "On"
    }
    private fun percent(value: Int, maximum: Int): String = "${(value * 100 / maximum.coerceAtLeast(1)).coerceIn(0, 100)}%"
    private fun gainLabel(millibels: Int): String = if (millibels <= 0) "Off" else "+${millibels / 100.0} dB"
    private fun signedDb(millibels: Int): String = String.format(Locale.US, "%+.1f dB", millibels / 100.0)
    private fun formatFrequency(hz: Int): String = if (hz >= 1000) String.format(Locale.US, "%.1f kHz", hz / 1000.0) else "$hz Hz"

    private fun onOff(value: Boolean): String = if (value) "On" else "Off"

    /** Grammar-correct counts everywhere; empty collections read as words, not zeros. */
    fun trackCountLabel(count: Int): String = when (count) {
        0 -> "Empty"
        1 -> "1 track"
        else -> "$count tracks"
    }
    fun volumeName(volumeId: String): String = when (volumeId) { "internal" -> "Internal Storage"; "sdcard" -> "SD Card"; else -> volumeId.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
    fun parentFolder(screen: Screen.Folders): Screen.Folders? {
        if (screen.volumeId == null) return null
        if (screen.relativePath.isBlank()) return Screen.Folders()
        return Screen.Folders(screen.volumeId, File(screen.relativePath).parent.orEmpty().replace('\\', '/'))
    }
    fun timeoutLabel(timeoutMs: Int): String = when (timeoutMs) {
        Int.MAX_VALUE -> "Never"; 15_000 -> "15 seconds"; 30_000 -> "30 seconds"; 60_000 -> "1 minute";
        120_000 -> "2 minutes"; 300_000 -> "5 minutes"; 600_000 -> "10 minutes"
        else -> if (timeoutMs >= 60_000) "${timeoutMs / 60_000} minutes" else "${timeoutMs / 1_000} seconds"
    }
    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb) else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    }
    private fun formatUptime(milliseconds: Long): String {
        val totalMinutes = milliseconds.coerceAtLeast(0) / 60_000
        return if (totalMinutes >= 60) "${totalMinutes / 60}h ${totalMinutes % 60}m" else "${totalMinutes}m"
    }

    private data class LargeRowsKey(
        val screen: Screen,
        val contentRevision: Long,
        val indexIdentity: Int,
        val sortOrder: TrackSortOrder,
        val queueFingerprint: Int
    )

    private data class AlbumAccumulator(var count: Int, var singleArtist: String?)

    /**
     * Access-ordered so the eviction in [rows] drops the least recently used
     * screen. Guarded by the same monitor as [rows] and [clearCachedRows];
     * LinkedHashMap in access order mutates on read, so it must not be touched
     * outside that lock.
     */
    private val cachedRows = LinkedHashMap<LargeRowsKey, List<ScreenRow>>(
        ROW_CACHE_ENTRIES + 1, 0.75f, true
    )

    @Synchronized
    fun clearCachedRows() {
        cachedRows.clear()
    }

    private fun isLargeScreen(screen: Screen): Boolean = when (screen) {
        Screen.Songs, Screen.Favorites, Screen.RecentlyPlayed, Screen.Albums, Screen.Artists,
        Screen.Playlists, Screen.Queue -> true
        is Screen.AlbumSongs, is Screen.ArtistSongs, is Screen.Folders, is Screen.PlaylistTracks -> true
        else -> false
    }

    /**
     * Four screens of rows. Enough to cover a browse path (list → detail → back)
     * and the previous/updated pair the reducer needs, small enough that the
     * retained row objects stay a bounded fraction of the track list.
     */
    private const val ROW_CACHE_ENTRIES = 4

    val BRIGHTNESS_LEVELS = listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
    val TIMEOUT_LEVELS = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000, Int.MAX_VALUE)
}
