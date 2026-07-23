package com.schulzcode.y2player.playback

import android.view.KeyEvent
import com.schulzcode.y2player.input.HardwareKeyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaButtonPolicyTest {
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
}
