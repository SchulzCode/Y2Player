package com.schulzcode.y2player.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputPressClassifierTest {
    @Test fun classifiesFrameworkRepeatAndElapsedLongPress() {
        assertTrue(InputPressClassifier.isLongPress(true, 0, 10))
        assertTrue(InputPressClassifier.isLongPress(false, 3, 100))
        assertTrue(InputPressClassifier.isLongPress(false, 0, 650))
        assertFalse(InputPressClassifier.isLongPress(false, 2, 649))
    }

    @Test
    fun `holding a play key from the local keypad navigates`() {
        assertEquals(
            true,
            InputPressClassifier.holdOpensNowPlaying(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { true }
        )
    }

    @Test
    fun `holding a play key from a remote keeps its ordinary meaning`() {
        assertEquals(
            false,
            InputPressClassifier.holdOpensNowPlaying(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { false }
        )
    }

    @Test
    fun `keys that are not play keys are not part of the question`() {
        for (key in listOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK
        )) {
            assertNull(InputPressClassifier.holdOpensNowPlaying(key) { true })
        }
    }

    @Test
    fun `the device lookup is skipped for non-play keys`() {
        var consulted = false
        InputPressClassifier.holdOpensNowPlaying(KeyEvent.KEYCODE_DPAD_UP) { consulted = true; true }
        assertFalse(consulted)
    }

    @Test
    fun `a release below the threshold still toggles playback`() {
        assertFalse(
            InputPressClassifier.releaseOpensNowPlaying(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                InputPressClassifier.LONG_PRESS_MS - 1
            )
        )
    }

    @Test
    fun `a release at or above the threshold navigates`() {
        assertTrue(
            InputPressClassifier.releaseOpensNowPlaying(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                InputPressClassifier.LONG_PRESS_MS
            )
        )
        assertTrue(
            InputPressClassifier.releaseOpensNowPlaying(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 2_000)
        )
    }

    @Test
    fun `a long release on an unrelated key never navigates`() {
        assertFalse(InputPressClassifier.releaseOpensNowPlaying(KeyEvent.KEYCODE_DPAD_CENTER, 5_000))
        assertFalse(InputPressClassifier.releaseOpensNowPlaying(KeyEvent.KEYCODE_BACK, 5_000))
        assertFalse(InputPressClassifier.releaseNavigatesHome(KeyEvent.KEYCODE_DPAD_CENTER, 5_000))
    }

    @Test
    fun `holding the top back button navigates home on release`() {
        assertFalse(
            InputPressClassifier.releaseNavigatesHome(
                KeyEvent.KEYCODE_BACK,
                InputPressClassifier.LONG_PRESS_MS - 1
            )
        )
        assertTrue(
            InputPressClassifier.releaseNavigatesHome(
                KeyEvent.KEYCODE_BACK,
                InputPressClassifier.LONG_PRESS_MS
            )
        )
        assertFalse(InputPressClassifier.releaseNavigatesHome(KeyEvent.KEYCODE_DPAD_LEFT, 5_000))
    }

    @Test
    fun `a negative held time cannot navigate`() {
        assertFalse(InputPressClassifier.releaseOpensNowPlaying(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, -5_000))
        assertFalse(InputPressClassifier.releaseNavigatesHome(KeyEvent.KEYCODE_BACK, -5_000))
    }

    @Test
    fun `repeat-based and release-based detection are mutually exclusive`() {
        val repeating = InputPressClassifier.isLongPress(
            frameworkLongPress = false,
            repeatCount = InputPressClassifier.LONG_PRESS_REPEAT,
            heldForMs = 0
        )
        assertTrue("repeats classify on ACTION_DOWN", repeating)

        val firstDown = InputPressClassifier.isLongPress(
            frameworkLongPress = false,
            repeatCount = 0,
            heldForMs = 0
        )
        assertFalse("a first DOWN has no held time yet", firstDown)
        assertTrue(
            "so the hold can only be seen at release",
            InputPressClassifier.releaseOpensNowPlaying(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 2_000)
        )
    }
}
