package com.schulzcode.y2player.ui

import com.schulzcode.y2player.core.state.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedTextScrollerTest {
    @Test fun fittingTextDoesNotScheduleAnimation() {
        val scroller = SelectedTextScroller(speedPxPerSecond = 20f)
        scroller.setActive(true, 0)
        assertFalse(scroller.setTarget(SelectedTextScroller.TARGET_LIST, Screen.Songs, 2, "Short", 40f, 50f, 0))
        assertEquals(SelectedTextScroller.NO_CALLBACK, scroller.advance(2_000))
    }

    @Test fun longTextWaitsThenMovesAtConstantSpeed() {
        val scroller = SelectedTextScroller(20f, initialDelayMs = 1_000, endPauseMs = 500)
        scroller.setActive(true, 0)
        scroller.setTarget(SelectedTextScroller.TARGET_LIST, Screen.Songs, 2, "Long title", 100f, 50f, 0)

        assertEquals(500, scroller.advance(500))
        assertEquals(SelectedTextScroller.NEXT_FRAME, scroller.advance(1_000))
        assertEquals(SelectedTextScroller.NEXT_FRAME, scroller.advance(1_500))
        assertEquals(10f, scroller.offsetPx, 0.001f)
    }

    @Test fun selectionOrContentChangeRestartsFromTheBeginning() {
        val scroller = SelectedTextScroller(20f, initialDelayMs = 1_000, endPauseMs = 500)
        scroller.setActive(true, 0)
        scroller.setTarget(SelectedTextScroller.TARGET_LIST, Screen.Songs, 2, "First", 100f, 50f, 0)
        scroller.advance(1_000)
        scroller.advance(1_500)
        assertTrue(scroller.offsetPx > 0f)

        assertTrue(scroller.setTarget(SelectedTextScroller.TARGET_LIST, Screen.Songs, 3, "Second", 100f, 50f, 1_500))
        assertEquals(0f, scroller.offsetPx, 0f)
        assertEquals(1_000, scroller.advance(1_500))
    }

    @Test fun hidingStopsCallbacksAndShowingRestartsTheDelay() {
        val scroller = SelectedTextScroller(20f, initialDelayMs = 1_000, endPauseMs = 500)
        scroller.setActive(true, 0)
        scroller.setTarget(SelectedTextScroller.TARGET_NOW_TITLE, Screen.NowPlaying, 0, "Long", 100f, 50f, 0)
        scroller.advance(1_000)
        scroller.advance(1_500)

        scroller.setActive(false, 1_500)
        assertEquals(SelectedTextScroller.NO_CALLBACK, scroller.advance(5_000))
        assertEquals(0f, scroller.offsetPx, 0f)
        scroller.setActive(true, 5_000)
        assertEquals(1_000, scroller.advance(5_000))
    }

    @Test fun onlyOneTargetExistsAndMetadataReplacementResetsIt() {
        val scroller = SelectedTextScroller(20f)
        scroller.setActive(true, 0)
        scroller.setTarget(SelectedTextScroller.TARGET_NOW_TITLE, Screen.NowPlaying, 0, "Title", 100f, 50f, 0)
        assertTrue(scroller.isTarget(SelectedTextScroller.TARGET_NOW_TITLE, Screen.NowPlaying, 0, "Title"))

        scroller.setTarget(SelectedTextScroller.TARGET_NOW_ARTIST, Screen.NowPlaying, 0, "Artist", 100f, 50f, 10)
        assertFalse(scroller.isTarget(SelectedTextScroller.TARGET_NOW_TITLE, Screen.NowPlaying, 0, "Title"))
        assertTrue(scroller.isTarget(SelectedTextScroller.TARGET_NOW_ARTIST, Screen.NowPlaying, 0, "Artist"))
        assertEquals(0f, scroller.offsetPx, 0f)
    }
}
