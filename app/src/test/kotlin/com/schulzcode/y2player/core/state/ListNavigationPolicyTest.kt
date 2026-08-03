package com.schulzcode.y2player.core.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListNavigationPolicyTest {
    @Test fun ordinaryListsWrapWhenEnabled() {
        assertEquals(9, ListNavigationPolicy.nextIndex(Screen.Songs, 0, -1, 10, wrapLists = true))
        assertEquals(0, ListNavigationPolicy.nextIndex(Screen.Songs, 9, 1, 10, wrapLists = true))
    }

    @Test fun ordinaryListsStopAtBoundariesWhenWrappingIsDisabled() {
        assertEquals(0, ListNavigationPolicy.nextIndex(Screen.Songs, 0, -5, 10, wrapLists = false))
        assertEquals(9, ListNavigationPolicy.nextIndex(Screen.Songs, 9, 5, 10, wrapLists = false))
    }

    @Test fun emptyAndSingleRowListsRemainSafe() {
        assertEquals(0, ListNavigationPolicy.nextIndex(Screen.Songs, 0, 5, 0, wrapLists = true))
        assertEquals(0, ListNavigationPolicy.nextIndex(Screen.Songs, 0, 5, 1, wrapLists = true))
    }

    @Test fun confirmationPromptIsNeverSelectableAndChoicesNeverWrap() {
        val screen = Screen.ConfirmAction("reset_library")
        assertEquals(1, ListNavigationPolicy.firstSelectableIndex(screen, 3))
        assertEquals(1, ListNavigationPolicy.nextIndex(screen, 1, -1, 3, wrapLists = true))
        assertEquals(2, ListNavigationPolicy.nextIndex(screen, 2, 1, 3, wrapLists = true))
    }

    @Test fun intentionalValueSelectorsKeepCycling() {
        assertEquals(3, ListNavigationPolicy.nextIndex(Screen.Brightness, 0, -1, 4, wrapLists = false))
        assertEquals(0, ListNavigationPolicy.nextIndex(Screen.Brightness, 3, 1, 4, wrapLists = false))
    }

    @Test fun accelerationIsLimitedToLongCollectionLists() {
        assertFalse(ListNavigationPolicy.allowsAcceleration(Screen.Songs, 11))
        assertTrue(ListNavigationPolicy.allowsAcceleration(Screen.Songs, 12))
        assertTrue(ListNavigationPolicy.allowsAcceleration(Screen.AudiobookChapters("book"), 20))
        assertFalse(ListNavigationPolicy.allowsAcceleration(Screen.ConfirmAction("clear"), 20))
        assertFalse(ListNavigationPolicy.allowsAcceleration(Screen.Controls, 20))
        assertFalse(ListNavigationPolicy.allowsAcceleration(Screen.Brightness, 20))
    }
}
