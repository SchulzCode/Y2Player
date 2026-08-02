package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AudiobookProgress
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.diagnostics.DiagnosticsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ScreenCatalogueTest` proves that an irreversible action cannot run without a
 * confirmation. It cannot prove the action is reachable at all, and Reset Library
 * was unreachable for a whole release while that test passed vacuously.
 */
class IrreversibleActionReachabilityTest {

    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

    private val track = Track(
        id = 1,
        volumeId = "sdcard",
        absolutePath = "/storage/sdcard1/AUDIOBOOKS/Dune/01.mp3",
        relativePath = "AUDIOBOOKS/Dune/01.mp3",
        title = "01.mp3",
        artist = "Narrator",
        album = null,
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        durationMs = 600_000,
        fileSize = 1,
        modifiedAt = 1
    )

    private val song = track.copy(
        id = 2,
        absolutePath = "/storage/sdcard1/Music/song.mp3",
        relativePath = "Music/song.mp3",
        title = "Song",
        album = "Album"
    )

    private val dune = "sdcard|AUDIOBOOKS/Dune"
    private val address = "AA:BB:CC:DD:EE:FF"

    private fun populated(screen: Screen) = AppState(
        screenStack = listOf(ScreenEntry(screen)),
        library = LibraryState(
            tracks = listOf(track, song),
            playlists = listOf(PlaylistSummary(5, "Road Trip", 1))
        ).copy(audiobookProgress = mapOf(dune to AudiobookProgress(dune, track.id, 60_000, 99))),
        bluetooth = BluetoothUiState(
            adapterMode = BluetoothAdapterMode.ON,
            devices = listOf(BluetoothDeviceEntry(address, "Headset", bonded = true, bonding = false))
        ),
        diagnostics = DiagnosticsState(historySessions = 4, historyBytes = 2_048)
    )

    private fun promptsReachableFromEveryScreen(): Set<String> {
        val prompts = HashSet<String>()
        ScreenCatalogue.all().forEach { screen ->
            val base = populated(screen)
            ScreenContent.rows(base).indices.forEach { index ->
                val at = base.copy(screenStack = listOf(ScreenEntry(screen, index)))
                val next = AppReducer.reduce(at, AppAction.Confirm).state.currentScreen
                if (next is Screen.ConfirmAction) prompts += next.key
            }
        }
        return prompts
    }

    @Test fun `every irreversible action has a row that reaches its confirmation`() {
        val reachable = promptsReachableFromEveryScreen()
        listOf(
            ConfirmPrompts.CLEAR_QUEUE,
            ConfirmPrompts.RESET_LIBRARY,
            ConfirmPrompts.CLEAR_HISTORY,
            ConfirmPrompts.DELETE_PLAYLIST,
            ConfirmPrompts.CLEAR_AUDIOBOOK,
            ConfirmPrompts.FORGET_DEVICE
        ).forEach { prompt ->
            assertTrue(
                "no screen offers a row that reaches $prompt, so the action is dead code",
                reachable.any { it.startsWith(prompt) }
            )
        }
    }

    // ---- Reset Library, the one that was missing ------------------------------------

    @Test fun `Reset offers Clear Queue, Reset Library and Safe Mode`() {
        val rows = ScreenContent.rows(populated(Screen.Reset))
        assertEquals(
            listOf("reset_queue", "reset_library", "reset_safe_mode"),
            rows.map { (it as ScreenRow.Action).key }
        )
    }

    @Test fun `Reset Library asks before it deletes`() {
        val reset = populated(Screen.Reset)
        val index = ScreenContent.rows(reset).indexOfFirst { (it as ScreenRow.Action).key == "reset_library" }
        val asked = AppReducer.reduce(reset.copy(screenStack = listOf(ScreenEntry(Screen.Reset, index))), AppAction.Confirm)

        assertEquals(Screen.ConfirmAction(ConfirmPrompts.RESET_LIBRARY), asked.state.currentScreen)
        assertTrue("the press itself must not reset anything", asked.effects.isEmpty())

        val selected = ScreenContent.rows(asked.state)[asked.state.selectedIndex] as ScreenRow.Action
        assertEquals("Cancel must be preselected", ScreenContent.CONFIRM_CANCEL_KEY, selected.key)
    }

    @Test fun `confirming Reset Library emits the effect once`() {
        val onConfirm = AppState(
            screenStack = listOf(
                ScreenEntry(Screen.Reset),
                ScreenEntry(Screen.ConfirmAction(ConfirmPrompts.RESET_LIBRARY))
            )
        )
        val okIndex = ScreenContent.rows(onConfirm)
            .indexOfFirst { (it as? ScreenRow.Action)?.key == ScreenContent.CONFIRM_OK_KEY }
        val confirmed = AppReducer.reduce(
            onConfirm.copy(screenStack = onConfirm.screenStack.dropLast(1) + ScreenEntry(onConfirm.currentScreen, okIndex)),
            AppAction.Confirm
        )
        assertEquals(listOf(AppEffect.ResetLibrary), confirmed.effects)
    }

    @Test fun `the Reset Library prompt says the index is rebuilt, not the playlists`() {
        val prompt = ScreenContent.rows(
            AppState(screenStack = listOf(ScreenEntry(Screen.ConfirmAction(ConfirmPrompts.RESET_LIBRARY))))
        ).first() as ScreenRow.Group
        assertTrue(prompt.subtitle!!.contains("deleted"))
        assertTrue("the music files must be reassured about", prompt.subtitle!!.contains("Music files are kept"))
    }

    // ---- Listening History count before it has been read ------------------------------

    @Test fun `Listening History has no count until the file has been read`() {
        val unread = AppState(screenStack = listOf(ScreenEntry(Screen.LibrarySettings)))
        val row = ScreenContent.rows(unread)
            .filterIsInstance<ScreenRow.Action>()
            .single { it.key == "playback_history" }
        assertNull("an unread history must not claim to be empty", row.subtitle)
    }

    @Test fun `Listening History reports the count once it is known`() {
        val row = ScreenContent.rows(populated(Screen.LibrarySettings))
            .filterIsInstance<ScreenRow.Action>()
            .single { it.key == "playback_history" }
        assertTrue(row.subtitle!!.startsWith("4 sessions"))
    }

    @Test fun `an empty history says so rather than showing nothing`() {
        val empty = AppState(
            screenStack = listOf(ScreenEntry(Screen.LibrarySettings)),
            diagnostics = DiagnosticsState(historySessions = 0)
        )
        val row = ScreenContent.rows(empty)
            .filterIsInstance<ScreenRow.Action>()
            .single { it.key == "playback_history" }
        assertEquals("No sessions recorded yet", row.subtitle)
    }

    @Test fun `opening Library settings reads the history count`() {
        val settings = AppState(screenStack = listOf(ScreenEntry(Screen.Settings)))
        val index = ScreenContent.rows(settings)
            .indexOfFirst { (it as? ScreenRow.Action)?.key == "library_settings" }
        val result = AppReducer.reduce(
            settings.copy(screenStack = listOf(ScreenEntry(Screen.Settings, index))),
            AppAction.Confirm
        )
        assertEquals(Screen.LibrarySettings, result.state.currentScreen)
        assertTrue(
            "the subtitle is read on this screen, so the count must be fetched on the way in",
            AppEffect.RefreshPlaybackHistory in result.effects
        )
    }
}
