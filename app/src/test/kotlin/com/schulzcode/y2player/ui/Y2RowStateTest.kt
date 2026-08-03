package com.schulzcode.y2player.ui

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.core.state.AppState
import com.schulzcode.y2player.core.state.ScreenRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Y2RowStateTest {
    private val favorite = Track(
        id = 7,
        volumeId = "internal",
        absolutePath = "/music/song.mp3",
        relativePath = "song.mp3",
        title = "Song",
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        trackNumber = 1,
        discNumber = 1,
        durationMs = 60_000,
        fileSize = 1,
        modifiedAt = 1,
        favorite = true
    )

    @Test fun `favorite actions are active only when their track is favorited`() {
        val state = AppState(library = LibraryState(tracks = listOf(favorite)))
        assertTrue(Y2RowState.isActive(action("track_favorite:7"), state))
        assertTrue(Y2RowState.isActive(action("np_favorite:7"), state))
        assertFalse(Y2RowState.isActive(action("track_favorite:8"), state))
        assertFalse(Y2RowState.isActive(action("track_favorite:7"), state.copy(
            library = LibraryState(tracks = listOf(favorite.copy(favorite = false)))
        )))
    }

    @Test fun `boolean options expose their enabled state`() {
        val enabled = AppState(preferences = AppState().preferences.copy(
            extraTrackInfo = true,
            localKeysWhileScreenOff = true,
            uiSoundEffectsEnabled = true,
            verboseDiagnostics = true,
            lightTheme = true
        ))
        listOf("extra_track_info", "screen_off_keys", "ui_sounds", "diag_verbose", "theme").forEach {
            assertTrue("$it should be active", Y2RowState.isActive(action(it), enabled))
        }

        val disabled = AppState()
        listOf("extra_track_info", "screen_off_keys", "ui_sounds", "diag_verbose", "theme").forEach {
            assertFalse("$it should be inactive", Y2RowState.isActive(action(it), disabled))
        }
    }

    private fun action(key: String) = ScreenRow.Action("Test", null, key)
}
