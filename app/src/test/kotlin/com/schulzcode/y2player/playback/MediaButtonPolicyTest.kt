package com.schulzcode.y2player.playback

import android.view.KeyEvent
import com.schulzcode.y2player.input.HardwareKeyGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaButtonPolicyTest {
    @After fun resetPressGate() = MediaButtonPressGate.reset()

    @Test fun frameworkCenterAndEnterMapToPlayPause() {
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            MediaButtonPolicy.playbackKeyCode(KeyEvent.KEYCODE_DPAD_CENTER, HardwareKeyGate.Source.MEDIA_BROADCAST)
        )
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            MediaButtonPolicy.playbackKeyCode(KeyEvent.KEYCODE_ENTER, HardwareKeyGate.Source.MEDIA_BROADCAST)
        )
    }

    @Test fun standardFrameworkMediaCommandsPassThrough() {
        for (keyCode in intArrayOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS
        )) {
            assertEquals(keyCode, MediaButtonPolicy.playbackKeyCode(keyCode, HardwareKeyGate.Source.MEDIA_BROADCAST))
        }
    }

    @Test fun playPauseAndHeadsetHookMappingsArePreserved() {
        val keys = intArrayOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK
        )
        keys.forEach { keyCode ->
            assertEquals(
                keyCode,
                MediaButtonPolicy.serviceRequest(keyCode, HardwareKeyGate.Source.MEDIA_BROADCAST)?.keyCode
            )
        }
    }

    @Test fun coldStartRequestUsesExplicitServiceActionAndNormalizedKey() {
        val request = MediaButtonPolicy.serviceRequest(
            KeyEvent.KEYCODE_DPAD_CENTER,
            HardwareKeyGate.Source.MEDIA_BROADCAST
        )
        assertEquals(PlaybackService.ACTION_MEDIA_BUTTON, request?.action)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, request?.keyCode)
    }

    @Test fun frameworkBroadcastCannotDriveWheelNavigation() {
        for (keyCode in intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BACK
        )) {
            assertNull(MediaButtonPolicy.playbackKeyCode(keyCode, HardwareKeyGate.Source.MEDIA_BROADCAST))
        }
    }

    @Test fun y2BroadcastKeepsItsExistingLeftRightMapping() {
        assertEquals(
            KeyEvent.KEYCODE_DPAD_LEFT,
            MediaButtonPolicy.playbackKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, HardwareKeyGate.Source.Y2_BROADCAST)
        )
        assertEquals(
            KeyEvent.KEYCODE_DPAD_RIGHT,
            MediaButtonPolicy.playbackKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, HardwareKeyGate.Source.Y2_BROADCAST)
        )
        assertNull(MediaButtonPolicy.playbackKeyCode(KeyEvent.KEYCODE_ENTER, HardwareKeyGate.Source.Y2_BROADCAST))
    }

    @Test fun remappedLocalKeypadVolumeKeysBecomeVolumeRequests() {
        val up = MediaButtonPolicy.serviceRequest(
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            HardwareKeyGate.Source.MEDIA_BROADCAST,
            scanCode = 115,
            fromLocalKeypad = true
        )
        val down = MediaButtonPolicy.serviceRequest(
            KeyEvent.KEYCODE_MEDIA_REWIND,
            HardwareKeyGate.Source.MEDIA_BROADCAST,
            scanCode = 114,
            fromLocalKeypad = true
        )
        assertEquals(PlaybackService.ACTION_ADJUST_VOLUME, up?.action)
        assertEquals(1, up?.volumeDirection)
        assertEquals(PlaybackService.ACTION_ADJUST_VOLUME, down?.action)
        assertEquals(-1, down?.volumeDirection)
    }

    @Test fun remoteAndOrdinaryMediaTransportKeysAreNotClaimedAsVolume() {
        val remote = MediaButtonPolicy.serviceRequest(
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            HardwareKeyGate.Source.MEDIA_BROADCAST,
            scanCode = 115,
            fromLocalKeypad = false
        )
        val localMediaKey = MediaButtonPolicy.serviceRequest(
            KeyEvent.KEYCODE_MEDIA_REWIND,
            HardwareKeyGate.Source.MEDIA_BROADCAST,
            scanCode = 168,
            fromLocalKeypad = true
        )
        assertEquals(PlaybackService.ACTION_MEDIA_BUTTON, remote?.action)
        assertNull(remote?.volumeDirection)
        assertEquals(PlaybackService.ACTION_MEDIA_BUTTON, localMediaKey?.action)
        assertNull(localMediaKey?.volumeDirection)
    }

    @Test fun normalDownAndUpDispatchExactlyOnce() {
        assertTrue(edge(KeyEvent.ACTION_DOWN, eventTime = 100L, downTime = 100L))
        assertFalse(edge(KeyEvent.ACTION_UP, eventTime = 180L, downTime = 100L))
    }

    @Test fun downOnlyDispatchesOnce() {
        assertTrue(edge(KeyEvent.ACTION_DOWN, eventTime = 100L, downTime = 100L))
    }

    @Test fun upOnlyDispatchesOnce() {
        assertTrue(edge(KeyEvent.ACTION_UP, eventTime = 100L, downTime = 20L))
    }

    @Test fun repeatedDownAndBounceEventsAreIgnored() {
        assertTrue(edge(KeyEvent.ACTION_DOWN, eventTime = 100L, downTime = 100L))
        assertFalse(edge(KeyEvent.ACTION_DOWN, eventTime = 120L, downTime = 100L, repeatCount = 1))
        assertFalse(edge(KeyEvent.ACTION_DOWN, eventTime = 140L, downTime = 100L))

        MediaButtonPressGate.reset()
        assertTrue(edge(KeyEvent.ACTION_UP, eventTime = 300L, downTime = 250L))
        assertFalse(edge(KeyEvent.ACTION_UP, eventTime = 340L, downTime = 290L))
    }

    @Test fun volumeHoldDispatchesFrameworkRepeatEvents() {
        assertTrue(edge(KeyEvent.ACTION_DOWN, eventTime = 100L, downTime = 100L, allowRepeats = true))
        assertTrue(edge(KeyEvent.ACTION_DOWN, eventTime = 600L, downTime = 100L, repeatCount = 1, allowRepeats = true))
        assertTrue(edge(KeyEvent.ACTION_DOWN, eventTime = 650L, downTime = 100L, repeatCount = 2, allowRepeats = true))
        assertFalse(edge(KeyEvent.ACTION_UP, eventTime = 700L, downTime = 100L, allowRepeats = true))
    }

    private fun edge(
        action: Int,
        eventTime: Long,
        downTime: Long,
        repeatCount: Int = 0,
        allowRepeats: Boolean = false
    ): Boolean = MediaButtonPressGate.shouldDispatch(
        keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        action = action,
        eventTime = eventTime,
        downTime = downTime,
        deviceId = 7,
        repeatCount = repeatCount,
        source = HardwareKeyGate.Source.MEDIA_BROADCAST,
        allowRepeats = allowRepeats
    )
}
