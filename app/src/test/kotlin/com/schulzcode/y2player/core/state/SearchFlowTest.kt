package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFlowTest {
    private val track = Track(
        id = 1,
        volumeId = "sdcard",
        absolutePath = "/storage/Music/Bohemian Rhapsody.mp3",
        relativePath = "Music/Bohemian Rhapsody.mp3",
        title = "Bohemian Rhapsody",
        artist = "Queen",
        album = "A Night at the Opera",
        albumArtist = "Queen",
        trackNumber = 1,
        discNumber = 1,
        durationMs = 60_000,
        fileSize = 100,
        modifiedAt = 1
    )

    @Test fun `main menu opens an empty search keyboard`() {
        val home = AppState(library = LibraryState(tracks = listOf(track)))
        val index = ScreenContent.rows(home).indexOfFirst { (it as? ScreenRow.Action)?.key == "search" }
        val result = AppReducer.reduce(
            home.copy(screenStack = listOf(ScreenEntry(Screen.MainMenu, index))),
            AppAction.Confirm
        ).state
        assertEquals(Screen.Search(), result.currentScreen)
        assertTrue(ScreenContent.rows(result).isEmpty())
    }

    @Test fun `keyboard entry results focus and selection form one predictable flow`() {
        var state = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.Search())),
            library = LibraryState(tracks = listOf(track))
        )
        listOf("B", "O", "H").forEach { key -> state = AppReducer.reduce(state, AppAction.PressSearchKey(key)).state }
        assertEquals("BOH", (state.currentScreen as Screen.Search).query)
        assertEquals("Bohemian Rhapsody", ScreenContent.rows(state).single().title)

        state = AppReducer.reduce(state, AppAction.PressSearchKey(SearchKeyboard.RESULTS)).state
        assertTrue((state.currentScreen as Screen.Search).resultsFocused)
        val opened = AppReducer.reduce(state, AppAction.Confirm)
        assertEquals(Screen.NowPlaying, opened.state.currentScreen)
        assertEquals(AppEffect.PlayCollection(listOf(1), 0), opened.effects.single())
    }

    @Test fun `Back returns from results then erases then exits`() {
        val base = AppState(screenStack = listOf(
            ScreenEntry(Screen.MainMenu),
            ScreenEntry(Screen.Search(query = "Q", resultsFocused = true))
        ))
        val keyboard = AppReducer.reduce(base, AppAction.Back).state
        assertFalse((keyboard.currentScreen as Screen.Search).resultsFocused)
        val erased = AppReducer.reduce(keyboard, AppAction.Back).state
        assertEquals("", (erased.currentScreen as Screen.Search).query)
        assertEquals(Screen.MainMenu, AppReducer.reduce(erased, AppAction.Back).state.currentScreen)
    }

    @Test fun `wheel traverses every key while side buttons do nothing`() {
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.Search())))
        assertEquals(state, AppReducer.reduce(state, AppAction.Left).state)
        assertEquals(state, AppReducer.reduce(state, AppAction.Right).state)

        val wheelOne = AppReducer.reduce(state, AppAction.WheelMoved(1)).state.currentScreen as Screen.Search
        assertEquals("W", SearchKeyboard.key(wheelOne))
        val wheelTen = AppReducer.reduce(state, AppAction.WheelMoved(10)).state.currentScreen as Screen.Search
        assertEquals("A", SearchKeyboard.key(wheelTen))
    }

    @Test fun `side and bottom button presses and holds do nothing in search`() {
        val state = AppState(screenStack = listOf(
            ScreenEntry(Screen.MainMenu),
            ScreenEntry(Screen.Search(query = "QUEEN"))
        ))
        listOf(
            AppAction.Left,
            AppAction.Right,
            AppAction.SeekBackward,
            AppAction.SeekForward,
            AppAction.SeekBackwardLong,
            AppAction.SeekForwardLong,
            AppAction.PlayPause,
            AppAction.ShowNowPlaying
        ).forEach { action ->
            val reduction = AppReducer.reduce(state, action)
            assertEquals(action.toString(), state, reduction.state)
            assertTrue(action.toString(), reduction.effects.isEmpty())
        }
    }

    @Test fun `keyboard wheel follows the global wrapping preference`() {
        val bounded = AppState(
            screenStack = listOf(ScreenEntry(Screen.Search())),
            preferences = PlayerPreferencesState(wrapLists = false)
        )
        assertEquals("Q", SearchKeyboard.key(
            AppReducer.reduce(bounded, AppAction.WheelMoved(-1)).state.currentScreen as Screen.Search
        ))

        val wrapped = bounded.copy(preferences = bounded.preferences.copy(wrapLists = true))
        assertEquals(SearchKeyboard.RESULTS, SearchKeyboard.key(
            AppReducer.reduce(wrapped, AppAction.WheelMoved(-1)).state.currentScreen as Screen.Search
        ))
    }
}
