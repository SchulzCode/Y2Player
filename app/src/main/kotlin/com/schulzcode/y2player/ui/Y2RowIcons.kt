package com.schulzcode.y2player.ui

import com.schulzcode.y2player.core.state.Screen
import com.schulzcode.y2player.core.state.ScreenContent
import com.schulzcode.y2player.core.state.ScreenRow

object Y2RowIcons {

    fun forRow(row: ScreenRow, screen: Screen, currentTrackId: Long?, active: Boolean = false): Y2Icon = when (row) {
        is ScreenRow.TrackRow -> if (active) Y2Icon.PLAYING else Y2Icon.SONG
        is ScreenRow.Folder -> Y2Icon.FOLDER
        is ScreenRow.Group -> forGroupKey(row.key, screen)
        is ScreenRow.Action -> if (active && Y2RowState.isFavoriteKey(row.key)) {
            Y2Icon.FAVORITE_FILLED
        } else forActionKey(row.key)
    }

    // Album and artist groups carry the name itself as the key, so the screen decides.
    fun forGroupKey(key: String, screen: Screen): Y2Icon {
        if (screen == Screen.Albums || screen is Screen.ArtistAlbums) return Y2Icon.ALBUM
        if (screen == Screen.Artists) return Y2Icon.ARTIST
        return exactGroup(key) ?: prefixedGroup(key)
    }

    private fun exactGroup(key: String): Y2Icon? = when (key) {
        "info_artist" -> Y2Icon.ARTIST
        "info_album" -> Y2Icon.ALBUM
        "info_format" -> Y2Icon.INFO
        "info_path" -> Y2Icon.FOLDER
        "queue_track" -> Y2Icon.QUEUE
        "confirm_prompt" -> Y2Icon.WARNING
        "bt_device_status" -> Y2Icon.BLUETOOTH
        "dac_status", "dac_output", "dac_bypass" -> Y2Icon.DAC
        "history_summary", "history_location", "history_hint" -> Y2Icon.HISTORY
        "diag_usb" -> Y2Icon.DIAGNOSTICS
        "about_app" -> Y2Icon.INFO
        "about_device", "about_android", "about_firmware", "about_build" -> Y2Icon.SYSTEM
        "about_library" -> Y2Icon.LIBRARY
        "about_uptime" -> Y2Icon.TIMER
        "bt_empty", "bt_device_missing", "playlist_empty", "missing",
        "dac_limit", "effects_error", "effects_unavailable", "eq_unsupported",
        "bass_unsupported", "loudness_unsupported", "crossfade_mode_unavailable" -> Y2Icon.WARNING
        else -> null
    }

    private fun prefixedGroup(key: String): Y2Icon = when {
        key.startsWith("queue_missing:") -> Y2Icon.WARNING
        key.startsWith("capability:") || key.startsWith("log:") -> Y2Icon.DIAGNOSTICS
        else -> Y2Icon.INFO
    }

    fun forActionKey(key: String): Y2Icon = exactAction(key) ?: prefixedAction(key)

    private fun exactAction(key: String): Y2Icon? = when (key) {
        "music" -> Y2Icon.MUSIC
        "audiobooks" -> Y2Icon.BOOK
        "settings" -> Y2Icon.SETTINGS

        "songs", "artist_all_songs" -> Y2Icon.SONG
        "albums" -> Y2Icon.ALBUM
        "artists" -> Y2Icon.ARTIST
        "playlists" -> Y2Icon.PLAYLIST
        "folders" -> Y2Icon.FOLDER
        "favorites" -> Y2Icon.FAVORITE
        "recent" -> Y2Icon.RECENT

        "shuffle_all", "collection_shuffle", "collection_up_next_shuffled", "shuffle" -> Y2Icon.SHUFFLE
        "collection_next" -> Y2Icon.NEXT
        "collection_up_next" -> Y2Icon.QUEUE
        "repeat" -> Y2Icon.REPEAT
        "queue", "queue_view", "queue_management" -> Y2Icon.QUEUE
        "queue_clear_up_next", "queue_clear_remaining", "queue_clear" -> Y2Icon.REMOVE
        "sleep_timer" -> Y2Icon.TIMER
        "playlist_create", "playlist_create_and_add" -> Y2Icon.ADD
        "playlist_import_m3u", "playlist_export_m3u", "playlist_files" -> Y2Icon.PLAYLIST

        "audio" -> Y2Icon.HEADPHONES
        "output" -> Y2Icon.DAC
        "sound_effects" -> Y2Icon.EQUALIZER
        "playback_transitions", "gapless", "crossfade", "crossfade_mode", "pause_fade" -> Y2Icon.CROSSFADE
        "playback_volume", "volume_mode", "replay_gain", "balance" -> Y2Icon.VOLUME
        "playback_seeking", "seek_step", "long_seek_step", "previous_threshold" -> Y2Icon.NEXT
        "playback_interruptions", "duck_focus", "pause_disconnect" -> Y2Icon.WARNING
        "resume_position" -> Y2Icon.RECENT
        "equalizer", "eq_preset", "eq_bands", "effects_toggle", "sound_dynamics",
        "bass", "loudness" -> Y2Icon.EQUALIZER
        "audio_quality", "output_information" -> Y2Icon.DAC
        "bluetooth", "bt_toggle" -> Y2Icon.BLUETOOTH
        "bt_scan", "bt_refresh" -> Y2Icon.REFRESH

        "interface" -> Y2Icon.SLIDERS
        "display", "brightness", "theme", "timeout", "keep_screen_on" -> Y2Icon.DISPLAY
        "controls", "haptics" -> Y2Icon.WHEEL
        "wrap_lists" -> Y2Icon.REPEAT
        "screen_off_keys" -> Y2Icon.DISPLAY
        "ui_sounds" -> Y2Icon.SPEAKER
        "extra_track_info", "technical_details" -> Y2Icon.INFO

        "library_settings" -> Y2Icon.LIBRARY
        "sort" -> Y2Icon.SORT
        "storage" -> Y2Icon.STORAGE
        "rescan" -> Y2Icon.REFRESH
        "playback_history", "listening_history" -> Y2Icon.HISTORY
        "history_clear" -> Y2Icon.REMOVE

        "system" -> Y2Icon.SYSTEM
        "diagnostics", "diag_export", "diag_clear", "diag_verbose" -> Y2Icon.DIAGNOSTICS
        "backup_restore", "backup_export", "backup_import" -> Y2Icon.STORAGE
        "reset", "reset_queue", "reset_library" -> Y2Icon.REMOVE
        "reset_safe_mode" -> Y2Icon.WARNING
        "android_settings" -> Y2Icon.SETTINGS
        "about" -> Y2Icon.INFO

        ScreenContent.CONFIRM_CANCEL_KEY -> Y2Icon.CHEVRON
        ScreenContent.CONFIRM_OK_KEY -> Y2Icon.CHECK
        else -> null
    }

    private fun prefixedAction(key: String): Y2Icon = when {
        key.startsWith("audiobook_chapters:") -> Y2Icon.CHAPTERS
        key.startsWith("np_audiobook_chapters:") -> Y2Icon.CHAPTERS
        key.startsWith("audiobook_restart:") -> Y2Icon.REFRESH
        key.startsWith("audiobook_clear:") -> Y2Icon.REMOVE
        key.startsWith("audiobook:") -> Y2Icon.BOOK
        key.startsWith("playlist:") || key.startsWith("playlist_add:") -> Y2Icon.PLAYLIST
        key.startsWith("playlist_delete:") -> Y2Icon.REMOVE
        key.startsWith("track_next:") -> Y2Icon.NEXT
        key.startsWith("track_queue:") -> Y2Icon.QUEUE
        key.startsWith("track_favorite:") || key.startsWith("np_favorite:") -> Y2Icon.FAVORITE
        key.startsWith("track_playlist:") || key.startsWith("np_playlist:") -> Y2Icon.PLAYLIST
        key.startsWith("track_multi:") -> Y2Icon.CHECK
        key.startsWith("track_remove_playlist:") -> Y2Icon.REMOVE
        key.startsWith("track_browse:") -> Y2Icon.CHEVRON
        key.startsWith("track_details:") -> Y2Icon.INFO
        key.startsWith("track_album:") -> Y2Icon.ALBUM
        key.startsWith("track_artist:") -> Y2Icon.ARTIST
        key.startsWith("np_track_options:") -> Y2Icon.CHEVRON
        key.startsWith("np_track_details:") -> Y2Icon.INFO
        key.startsWith("queue_play:") -> Y2Icon.PLAYING
        key.startsWith("queue_next:") -> Y2Icon.NEXT
        key.startsWith("queue_move:") -> Y2Icon.SORT
        key.startsWith("queue_remove:") -> Y2Icon.REMOVE
        key.startsWith("bt_device_activate:") -> Y2Icon.BLUETOOTH
        key.startsWith("bt_device_forget:") -> Y2Icon.REMOVE
        key.startsWith("bt_device:") -> Y2Icon.BLUETOOTH
        key.startsWith("storage:") -> Y2Icon.STORAGE
        key.startsWith("sort:") -> Y2Icon.SORT
        key.startsWith("balance:") -> Y2Icon.VOLUME
        key.startsWith("brightness:") || key.startsWith("timeout:") -> Y2Icon.DISPLAY
        key.startsWith("eq_band:") -> Y2Icon.EQUALIZER
        else -> Y2Icon.ACTION
    }
}
