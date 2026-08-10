package com.schulzcode.y2player.playback

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeyRepeatControllerTest {
    @Test fun `short press changes exactly one step`() {
        val controller = controller()
        val down = controller.onDown(-1, KEY_DOWN, 100, 7, 0, 100, 100)
        val up = controller.onRelease(KEY_DOWN, 100, 7)

        assertEquals(-1, down.adjustment)
        assertNull(down.nextRunAtMs)
        assertNull(up.adjustment)
        assertNull(controller.onTimer(2_000).adjustment)
        assertFalse(controller.isRepeating)
    }

    @Test fun `initial down first repeat and release use bounded fallback repeats`() {
        val controller = controller()
        assertEquals(1, controller.onDown(1, KEY_UP, 100, 7, 0, 100, 100).adjustment)
        val frameworkRepeat = controller.onDown(1, KEY_UP, 100, 7, 1, 600, 600)
        assertEquals(1, frameworkRepeat.adjustment)
        assertEquals(780L, frameworkRepeat.nextRunAtMs)

        assertEquals(1, controller.onTimer(780).adjustment)
        assertEquals(1, controller.onTimer(880).adjustment)
        val release = controller.onRelease(KEY_UP, 100, 7)
        assertNull(release.nextRunAtMs)
        assertNull(controller.onTimer(980).adjustment)
    }

    @Test fun `continuous Android repeats suppress fallback without losing framework steps`() {
        val controller = controller()
        var adjustments = 0
        adjustments += controller.onDown(1, KEY_UP, 100, 7, 0, 100, 100).adjustment ?: 0
        adjustments += controller.onDown(1, KEY_UP, 100, 7, 1, 600, 600).adjustment ?: 0
        adjustments += controller.onDown(1, KEY_UP, 100, 7, 2, 650, 650).adjustment ?: 0

        assertNull("an obsolete scheduled callback must not add a step", controller.onTimer(780).adjustment)
        adjustments += controller.onDown(1, KEY_UP, 100, 7, 3, 800, 800).adjustment ?: 0
        assertNull(controller.onTimer(829).adjustment)
        assertEquals(4, adjustments)
        assertEquals(1, controller.onTimer(980).adjustment)
    }

    @Test fun `release cancellation and opposite key all stop the old repeat`() {
        val controller = controller()
        controller.onDown(1, KEY_UP, 100, 7, 0, 100, 100)
        controller.onDown(1, KEY_UP, 100, 7, 1, 600, 600)
        controller.onRelease(KEY_UP, 100, 7)
        assertNull(controller.onTimer(780).adjustment)

        controller.onDown(1, KEY_UP, 900, 7, 0, 900, 900)
        controller.onDown(1, KEY_UP, 900, 7, 1, 1_400, 1_400)
        controller.cancel()
        assertNull(controller.onTimer(1_580).adjustment)

        controller.onDown(1, KEY_UP, 2_000, 7, 0, 2_000, 2_000)
        controller.onDown(1, KEY_UP, 2_000, 7, 1, 2_500, 2_500)
        val opposite = controller.onDown(-1, KEY_DOWN, 2_600, 7, 0, 2_600, 2_600)
        assertEquals(-1, opposite.adjustment)
        assertNull(opposite.nextRunAtMs)
    }

    @Test fun `missing or delayed key up cannot repeat beyond safety limit`() {
        val controller = controller(maxHoldMs = 1_000)
        controller.onDown(-1, KEY_DOWN, 100, 7, 0, 100, 100)
        controller.onDown(-1, KEY_DOWN, 100, 7, 1, 600, 600)

        val stopped = controller.onTimer(1_100)
        assertTrue(stopped.stoppedByLimit)
        assertNull(stopped.adjustment)
        assertFalse(controller.isRepeating)
        assertNull(controller.onRelease(KEY_DOWN, 100, 7).adjustment)
        assertNull(controller.onTimer(2_000).adjustment)
    }

    @Test fun `duplicate deliveries from multiple paths do not add volume steps`() {
        val controller = controller()
        assertEquals(-1, controller.onDown(-1, KEY_DOWN, 100, 7, 0, 100, 100).adjustment)
        assertNull(controller.onDown(-1, KEY_DOWN, 100, 7, 0, 100, 101).adjustment)
        assertEquals(-1, controller.onDown(-1, KEY_DOWN, 100, 7, 1, 600, 600).adjustment)
        assertNull(controller.onDown(-1, KEY_DOWN, 100, 7, 1, 600, 601).adjustment)
    }

    @Test fun `switching screen state hands repetition between fallback and framework`() {
        val controller = controller()
        controller.onDown(1, KEY_UP, 100, 7, 0, 100, 100)
        controller.onDown(1, KEY_UP, 100, 7, 1, 600, 600)
        assertTrue(controller.isRepeating)

        controller.cancel() // ACTION_SCREEN_ON / foreground input
        assertNull(controller.onTimer(780).adjustment)
        assertFalse(controller.isRepeating)

        // A repeat arriving after ACTION_SCREEN_OFF can establish a new bounded
        // fallback even when the activity handled the original down.
        val screenOffRepeat = controller.onDown(1, KEY_UP, 100, 7, 4, 900, 900)
        assertEquals(1, screenOffRepeat.adjustment)
        assertTrue(controller.isRepeating)
    }

    @Test fun `same repeat schedule drives in app and system volume modes`() {
        listOf(TestVolumeMode.IN_APP, TestVolumeMode.SYSTEM).forEach { mode ->
            val controller = controller()
            var level = 5
            fun apply(result: VolumeKeyRepeatController.Result) {
                result.adjustment?.let { level += it }
            }

            apply(controller.onDown(1, KEY_UP, 100, 7, 0, 100, 100))
            apply(controller.onDown(1, KEY_UP, 100, 7, 1, 600, 600))
            apply(controller.onTimer(780))
            controller.onRelease(KEY_UP, 100, 7)
            apply(controller.onTimer(880))

            assertEquals("mode=$mode", 8, level)
        }
    }

    private fun controller(maxHoldMs: Long = 10_000) = VolumeKeyRepeatController(
        frameworkSilenceMs = 180,
        repeatIntervalMs = 100,
        maxHoldMs = maxHoldMs
    )

    private enum class TestVolumeMode { IN_APP, SYSTEM }

    private companion object {
        const val KEY_UP = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        const val KEY_DOWN = KeyEvent.KEYCODE_MEDIA_REWIND
    }
}
