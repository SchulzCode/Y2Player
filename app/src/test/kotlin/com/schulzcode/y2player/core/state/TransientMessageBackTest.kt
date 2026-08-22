package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientMessageBackTest {
    @Test fun backDismissesTransientMessageBeforeNavigating() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.Music)),
            transientMessage = "Temporary alert"
        )

        val dismissed = AppReducer.reduce(state, AppAction.Back)

        assertNull(dismissed.state.transientMessage)
        assertEquals(state.screenStack, dismissed.state.screenStack)
        assertTrue(dismissed.effects.isEmpty())

        val navigated = AppReducer.reduce(dismissed.state, AppAction.Back)

        assertEquals(Screen.MainMenu, navigated.state.currentScreen)
        assertEquals(1, navigated.state.screenStack.size)
    }

    @Test fun backWithoutTransientMessageKeepsExistingNavigationBehavior() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.Music))
        )

        val result = AppReducer.reduce(state, AppAction.Back)

        assertEquals(Screen.MainMenu, result.state.currentScreen)
        assertEquals(1, result.state.screenStack.size)
    }

    @Test fun dismissingOneMessageDoesNotBlockLaterMessages() {
        val dismissed = AppReducer.reduce(
            AppState(transientMessage = "First alert"),
            AppAction.Back
        ).state

        val next = AppReducer.reduce(dismissed, AppAction.ShowMessage("Next alert"))

        assertEquals("Next alert", next.state.transientMessage)
    }

    @Test fun persistentStatusErrorsDoNotInterceptBack() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.Music)),
            library = LibraryState(errorMessage = "Library scan failed")
        )

        val result = AppReducer.reduce(state, AppAction.Back)

        assertEquals(Screen.MainMenu, result.state.currentScreen)
        assertEquals("Library scan failed", result.state.library.errorMessage)
    }

    @Test fun rootBackWithoutTransientMessageRemainsANoOp() {
        val state = AppState()

        val result = AppReducer.reduce(state, AppAction.Back)

        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
    }
}
