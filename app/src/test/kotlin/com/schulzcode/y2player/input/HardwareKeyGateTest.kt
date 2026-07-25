package com.schulzcode.y2player.input

import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareKeyGateTest {
    @After fun reset() = HardwareKeyGate.reset()

    @Test fun duplicateFromDifferentSourcesIsRejected() {
        assertTrue(accept(100, HardwareKeyGate.Source.ACTIVITY))
        assertFalse(accept(120, HardwareKeyGate.Source.Y2_BROADCAST))
    }

    @Test fun sameSourceBounceIsSuppressed() {
        assertTrue(accept(100, HardwareKeyGate.Source.ACTIVITY, KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue("Rapid wheel movement must remain legitimate", accept(120, HardwareKeyGate.Source.ACTIVITY, KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(accept(300, HardwareKeyGate.Source.ACTIVITY, KeyEvent.KEYCODE_MEDIA_NEXT))
        assertFalse(accept(320, HardwareKeyGate.Source.ACTIVITY, KeyEvent.KEYCODE_MEDIA_NEXT))
    }

    @Test fun rapidWheelEventsFromDifferentSourcesAreNotSuppressed() {
        assertTrue(accept(100, HardwareKeyGate.Source.ACTIVITY, KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(accept(110, HardwareKeyGate.Source.Y2_BROADCAST, KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test fun laterEventFromAnotherSourceIsAccepted() {
        assertTrue(accept(100, HardwareKeyGate.Source.ACTIVITY))
        assertTrue(accept(400, HardwareKeyGate.Source.Y2_BROADCAST))
    }

    @Test fun activityWheelMediaAndNavigationKeysRequireAnOnAndUnlockedDisplay() {
        val blockedKeys = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_HEADSETHOOK
        )
        blockedKeys.forEach { keyCode ->
            assertFalse(HardwareKeyGate.isInputAllowed(keyCode, screenOn = false, keyguardLocked = false))
            assertFalse(HardwareKeyGate.isInputAllowed(keyCode, screenOn = true, keyguardLocked = true))
            assertTrue(HardwareKeyGate.isInputAllowed(keyCode, screenOn = true, keyguardLocked = false))
        }
    }

    @Test fun screenOffRemoteTransportKeysAreAllowed() {
        val remoteKeys = intArrayOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_HEADSETHOOK
        )
        remoteKeys.forEach { keyCode ->
            assertTrue(
                HardwareKeyGate.isInputAllowed(
                    keyCode,
                    screenOn = false,
                    keyguardLocked = true,
                    source = HardwareKeyGate.Source.MEDIA_BROADCAST
                )
            )
        }
    }

    @Test fun screenOffWheelAndNavigationBroadcastsRemainBlocked() {
        val physicalKeys = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME
        )
        physicalKeys.forEach { keyCode ->
            assertFalse(
                HardwareKeyGate.isInputAllowed(
                    keyCode,
                    screenOn = false,
                    keyguardLocked = true,
                    source = HardwareKeyGate.Source.Y2_BROADCAST
                )
            )
        }
    }

    @Test fun frameworkCenterCanRepresentRemotePlayPauseButActivityCenterCannot() {
        assertTrue(
            HardwareKeyGate.isInputAllowed(
                KeyEvent.KEYCODE_DPAD_CENTER, false, true,
                HardwareKeyGate.Source.MEDIA_BROADCAST
            )
        )
        assertFalse(
            HardwareKeyGate.isInputAllowed(
                KeyEvent.KEYCODE_DPAD_CENTER, false, true,
                HardwareKeyGate.Source.ACTIVITY
            )
        )
    }

    private val transportKeys = intArrayOf(
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_HEADSETHOOK
    )

    /**
     * The Y2's own play button emits KEYCODE_MEDIA_PLAY_PAUSE. Enabling headset
     * stem control previously enabled it too, so the player could be started
     * from a pocket.
     */
    @Test fun screenOffLocalKeypadTransportIsBlocked() {
        listOf(HardwareKeyGate.Source.Y2_BROADCAST, HardwareKeyGate.Source.MEDIA_BROADCAST).forEach { source ->
            transportKeys.forEach { keyCode ->
                assertFalse(
                    "keypad key $keyCode on $source must not act while the screen is off",
                    HardwareKeyGate.isInputAllowed(
                        keyCode,
                        screenOn = false,
                        keyguardLocked = true,
                        source = source,
                        fromLocalKeypad = true
                    )
                )
            }
        }
    }

    /** The same button must still work normally with the UI up. */
    @Test fun screenOnLocalKeypadTransportStillWorks() {
        assertTrue(
            HardwareKeyGate.isInputAllowed(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                screenOn = true,
                keyguardLocked = false,
                source = HardwareKeyGate.Source.Y2_BROADCAST,
                fromLocalKeypad = true
            )
        )
    }

    /**
     * The counterpart: a headset must keep working while the screen is off, on
     * *either* channel. The vendor rebroadcasts AVRCP on its own action as well
     * as ACTION_MEDIA_BUTTON, so keying the rule on the source blocked the
     * headset — the regression this pair of tests exists to prevent.
     */
    @Test fun screenOffHeadsetTransportIsAllowedOnBothChannels() {
        listOf(HardwareKeyGate.Source.MEDIA_BROADCAST, HardwareKeyGate.Source.Y2_BROADCAST).forEach { source ->
            transportKeys.forEach { keyCode ->
                assertTrue(
                    "headset key $keyCode on $source must survive the screen-off gate",
                    HardwareKeyGate.isInputAllowed(
                        keyCode,
                        screenOn = false,
                        keyguardLocked = true,
                        source = source,
                        fromLocalKeypad = false
                    )
                )
            }
        }
    }

    @Test fun powerAndVolumeRemainAllowedRegardlessOfDisplayState() {
        val systemKeys = intArrayOf(
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE
        )
        systemKeys.forEach { keyCode ->
            assertTrue(HardwareKeyGate.isInputAllowed(keyCode, screenOn = false, keyguardLocked = true))
        }
    }

    private fun accept(
        time: Long,
        source: HardwareKeyGate.Source,
        keyCode: Int = KeyEvent.KEYCODE_MEDIA_NEXT
    ): Boolean = HardwareKeyGate.accept(keyCode, KeyEvent.ACTION_UP, time, source)
}
