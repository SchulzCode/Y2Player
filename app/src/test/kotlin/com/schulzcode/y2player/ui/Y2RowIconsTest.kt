package com.schulzcode.y2player.ui

import com.schulzcode.y2player.core.state.Screen
import com.schulzcode.y2player.core.state.ScreenContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Y2RowIconsTest {

    private val stableActionKeys = listOf(
        "about", "albums", "android_settings", "artist_all_songs",
        "artists", "audio", "audio_quality", "audiobooks",
        "balance", "bass", "bluetooth", "brightness",
        "bt_refresh", "bt_scan", "bt_toggle", "collection_shuffle",
        "confirm_cancel", "confirm_ok", "controls", "diag_export",
        "diag_verbose", "diagnostics", "display", "duck_focus",
        "effects_toggle", "eq_bands", "eq_preset", "equalizer",
        "extra_track_info", "favorites", "folders", "haptics",
        "history_clear", "interface", "keep_screen_on", "library_settings",
        "long_seek_step", "loudness", "music",
        "output", "pause_disconnect", "playback_history", "playback_interruptions",
        "playback_seeking", "playback_transitions", "playback_volume", "playlist_create",
        "playlist_create_and_add", "playlist_export_m3u", "playlist_import_m3u", "playlists",
        "previous_threshold", "queue", "queue_view", "queue_clear", "queue_clear_up_next", "queue_clear_remaining",
        "queue_management", "recent", "repeat", "replay_gain",
        "rescan", "reset", "reset_library", "reset_queue",
        "reset_safe_mode", "resume_position", "screen_off_keys", "seek_step",
        "settings", "shuffle", "shuffle_all", "sleep_timer",
        "songs", "sort", "sound_effects", "storage",
        "system", "theme", "timeout", "ui_sounds",
        "volume_mode"
    )

    private val parameterisedActionKeys = listOf(
        "balance:-2", "brightness:50", "bt_device:AA:BB", "bt_device_activate:AA:BB",
        "bt_device_forget:AA:BB", "eq_band:3", "np_favorite:1", "np_playlist:1",
        "np_track_options:1", "np_track_details:1", "playlist:5", "playlist_add:5", "playlist_delete:5",
        "queue_move:0", "queue_next:0", "queue_play:0", "queue_remove:0",
        "sort:title", "storage:sdcard", "timeout:15000", "track_album:1",
        "track_artist:1", "track_browse:1", "track_details:1", "track_favorite:1",
        "track_next:1", "track_playlist:1", "track_queue:1", "track_multi:1", "track_remove_playlist:5:1",
        "audiobook:sdcard|AUDIOBOOKS/Dune", "audiobook_chapters:sdcard|AUDIOBOOKS/Dune",
        "audiobook_restart:sdcard|AUDIOBOOKS/Dune", "audiobook_clear:sdcard|AUDIOBOOKS/Dune"
    )

    @Test
    fun `every stable action key resolves to a specific icon`() {
        stableActionKeys.forEach { key ->
            assertNotEquals("$key fell through to the generic icon", Y2Icon.ACTION, Y2RowIcons.forActionKey(key))
        }
    }

    @Test
    fun `every parameterised action key resolves to a specific icon`() {
        parameterisedActionKeys.forEach { key ->
            assertNotEquals("$key fell through to the generic icon", Y2Icon.ACTION, Y2RowIcons.forActionKey(key))
        }
    }

    @Test
    fun `an unknown key falls back to the generic icon`() {
        assertEquals(Y2Icon.ACTION, Y2RowIcons.forActionKey("something_nobody_mapped"))
    }

    @Test
    fun `a longer key never steals a shorter key's prefix`() {
        assertEquals(Y2Icon.BLUETOOTH, Y2RowIcons.forActionKey("bt_device:AA:BB"))
        assertEquals(Y2Icon.BLUETOOTH, Y2RowIcons.forActionKey("bt_device_activate:AA:BB"))
        assertEquals(Y2Icon.REMOVE, Y2RowIcons.forActionKey("bt_device_forget:AA:BB"))
    }

    @Test
    fun `resetting the queue does not borrow the queue icon`() {
        assertEquals(Y2Icon.REMOVE, Y2RowIcons.forActionKey("reset_queue"))
        assertEquals(Y2Icon.QUEUE, Y2RowIcons.forActionKey("queue"))
    }

    @Test
    fun `library settings no longer borrow the playlist icon`() {
        assertEquals(Y2Icon.LIBRARY, Y2RowIcons.forActionKey("library_settings"))
        assertEquals(Y2Icon.PLAYLIST, Y2RowIcons.forActionKey("playlists"))
    }

    @Test
    fun `a parent never shares its child's icon`() {
        val pairs = listOf(
            "interface" to "display",
            "music" to "songs",
            "system" to "diagnostics",
            "library_settings" to "storage",
            "settings" to "interface"
        )
        pairs.forEach { (parent, child) ->
            assertNotEquals(
                "$parent and $child share an icon",
                Y2RowIcons.forActionKey(parent),
                Y2RowIcons.forActionKey(child)
            )
        }
    }

    @Test
    fun `every destructive row carries the remove icon`() {
        listOf(
            "queue_clear", "queue_clear_up_next", "queue_clear_remaining", "history_clear",
            "reset", "reset_queue", "reset_library",
            "queue_remove:0", "playlist_delete:5", "track_remove_playlist:5:1",
            "bt_device_forget:AA:BB", "audiobook_clear:sdcard|AUDIOBOOKS/Dune"
        ).forEach { key ->
            assertEquals("$key is destructive", Y2Icon.REMOVE, Y2RowIcons.forActionKey(key))
        }
    }

    @Test
    fun `album and artist groups take their icon from the screen`() {
        assertEquals(Y2Icon.ALBUM, Y2RowIcons.forGroupKey("Kind of Blue", Screen.Albums))
        assertEquals(Y2Icon.ARTIST, Y2RowIcons.forGroupKey("Miles Davis", Screen.Artists))
        assertEquals(Y2Icon.ALBUM, Y2RowIcons.forGroupKey("Kind of Blue", Screen.ArtistAlbums("Miles Davis")))
    }

    @Test
    fun `unsupported and error groups warn`() {
        listOf(
            "eq_unsupported", "bass_unsupported", "loudness_unsupported",
            "effects_unavailable", "effects_error", "dac_limit",
            "crossfade_mode_unavailable", "bt_empty", "bt_device_missing",
            "queue_missing:2", "missing", "playlist_empty"
        ).forEach { key ->
            assertEquals("$key should warn", Y2Icon.WARNING, Y2RowIcons.forGroupKey(key, Screen.Diagnostics))
        }
    }

    @Test
    fun `diagnostic capability and log groups share the diagnostics icon`() {
        assertEquals(Y2Icon.DIAGNOSTICS, Y2RowIcons.forGroupKey("capability:Decoders", Screen.Diagnostics))
        assertEquals(Y2Icon.DIAGNOSTICS, Y2RowIcons.forGroupKey("log:0", Screen.Diagnostics))
    }

    @Test
    fun `playing is visually distinct from play`() {
        assertNotEquals(Y2Icon.PLAY, Y2Icon.PLAYING)
    }

    @Test
    fun `favorite actions use a filled heart while active`() {
        val row = com.schulzcode.y2player.core.state.ScreenRow.Action(
            "Favorite",
            "On",
            "track_favorite:1"
        )
        assertEquals(Y2Icon.FAVORITE, Y2RowIcons.forRow(row, Screen.TrackOptions(1), null, active = false))
        assertEquals(Y2Icon.FAVORITE_FILLED, Y2RowIcons.forRow(row, Screen.TrackOptions(1), null, active = true))
    }

    @Test
    fun `main menu rows are all visually distinct`() {
        val icons = listOf("music", "audiobooks", "shuffle_all", "settings")
            .map(Y2RowIcons::forActionKey)
        assertEquals("main menu icons must be unique", icons.size, icons.toSet().size)
    }

    @Test
    fun `music section rows are all visually distinct`() {
        val icons = listOf(
            "shuffle_all", "songs", "albums", "artists", "playlists", "favorites", "recent", "folders"
        ).map(Y2RowIcons::forActionKey)
        assertEquals("music icons must be unique", icons.size, icons.toSet().size)
    }

    @Test
    fun `settings first level rows are all visually distinct`() {
        val icons = listOf("audio", "bluetooth", "interface", "library_settings", "system").map(Y2RowIcons::forActionKey)
        assertEquals("settings icons must be unique", icons.size, icons.toSet().size)
    }

    @Test
    fun `audio settings rows do not collapse onto one icon`() {
        val icons = listOf(
            "output", "playback_transitions", "playback_volume",
            "sound_effects", "playback_interruptions"
        ).map(Y2RowIcons::forActionKey)
        assertEquals("every Audio row must be distinct", icons.size, icons.toSet().size)
    }
}
