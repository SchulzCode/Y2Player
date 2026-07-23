package com.schulzcode.y2player.playback

import android.view.KeyEvent
import com.schulzcode.y2player.input.HardwareKeyGate
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaButtonPressGateTest {
    @After fun reset() = MediaButtonPressGate.reset()

    @Test fun downOnlyBluetoothPressDispatchesImmediately() {
        assertTrue(dispatch(KeyEvent.ACTION_DOWN, eventTime = 100, downTime = 100))
    }

    @Test fun normalDownUpPairDispatchesExactlyOnce() {
        assertTrue(dispatch(KeyEvent.ACTION_DOWN, eventTime = 100, downTime = 100))
        assertFalse(dispatch(KeyEvent.ACTION_UP, eventTime = 180, downTime = 100))
    }

    @Test fun upOnlyBroadcastStillDispatches() {
        assertTrue(dispatch(KeyEvent.ACTION_UP, eventTime = 100, downTime = 80))
    }

    @Test fun repeatedDownFromLongPressDoesNotToggleAgain() {
        assertTrue(dispatch(KeyEvent.ACTION_DOWN, eventTime = 100, downTime = 100))
        assertFalse(dispatch(KeyEvent.ACTION_DOWN, eventTime = 500, downTime = 100, repeatCount = 1))
    }

    @Test fun releaseFromAnotherKeyIsNotMistakenForThePendingPress() {
        assertTrue(dispatch(KeyEvent.ACTION_DOWN, eventTime = 100, downTime = 100))
        assertTrue(
            dispatch(
                action = KeyEvent.ACTION_UP,
                eventTime = 180,
                downTime = 120,
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
    }

    @Test fun releaseFromAnotherSourceIsNotSuppressed() {
        assertTrue(dispatch(KeyEvent.ACTION_DOWN, eventTime = 100, downTime = 100))
        assertTrue(
            dispatch(
                action = KeyEvent.ACTION_UP,
                eventTime = 180,
                downTime = 100,
                source = HardwareKeyGate.Source.Y2_BROADCAST
            )
        )
    }

    private fun dispatch(
        action: Int,
        eventTime: Long,
        downTime: Long,
        repeatCount: Int = 0,
        keyCode: Int = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        source: HardwareKeyGate.Source = HardwareKeyGate.Source.MEDIA_BROADCAST
    ): Boolean = MediaButtonPressGate.shouldDispatch(
        keyCode = keyCode,
        action = action,
        eventTime = eventTime,
        downTime = downTime,
        repeatCount = repeatCount,
        source = source
    )
}
