package com.schulzcode.y2player.input

import android.view.KeyEvent
import com.schulzcode.y2player.core.state.AppAction

class Y2InputController(
    private val dispatch: (AppAction) -> Unit,
    private val wheelAccelerationAllowed: () -> Boolean = { false }
) {
    private val longPressedKeys = HashSet<Int>()
    private val pressedKeys = HashSet<Int>()
    private val wheelAcceleration = WheelAcceleration()

    fun handle(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (!isHandledKey(keyCode)) return false

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                longPressedKeys.remove(keyCode)
                pressedKeys.add(keyCode)
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                    wheelAcceleration.reset()
                }
            }
            val heldFor = (event.eventTime - event.downTime).coerceAtLeast(0)
            val longPress = InputPressClassifier.isLongPress(event.isLongPress, event.repeatCount, heldFor)
            val holdIsNavigable = InputPressClassifier.holdOpensNowPlaying(
                keyCode,
                fromLocalKeypad = { HardwareKeyGate.isLocalKeypad(event.deviceId) }
            )
            if (longPress && holdIsNavigable != false && longPressedKeys.add(keyCode)) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> dispatch(AppAction.SeekBackwardLong)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> dispatch(AppAction.SeekForwardLong)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> dispatch(AppAction.ConfirmLong)
                    in InputPressClassifier.PLAY_PAUSE_KEYS -> dispatch(AppAction.ShowNowPlaying)
                }
            } else if (longPress && event.repeatCount > 0 &&
                event.repeatCount % InputPressClassifier.SCRUB_REPEAT_PERIOD == 0
            ) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> dispatch(AppAction.SeekBackwardLong)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> dispatch(AppAction.SeekForwardLong)
                }
            }
            return true
        }

        if (event.action != KeyEvent.ACTION_UP) return true
        if (!pressedKeys.remove(keyCode)) return true
        if (longPressedKeys.remove(keyCode)) return true

        val heldForMs = (event.eventTime - event.downTime).coerceAtLeast(0)
        if (InputPressClassifier.releaseOpensNowPlaying(keyCode, heldForMs) &&
            HardwareKeyGate.isLocalKeypad(event.deviceId)
        ) {
            dispatch(AppAction.ShowNowPlaying)
            return true
        }

        val action = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> AppAction.WheelMoved(
                wheelAcceleration.delta(-1, event.eventTime, wheelAccelerationAllowed())
            )
            KeyEvent.KEYCODE_DPAD_DOWN -> AppAction.WheelMoved(
                wheelAcceleration.delta(1, event.eventTime, wheelAccelerationAllowed())
            )
            KeyEvent.KEYCODE_DPAD_LEFT -> AppAction.Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> AppAction.Right
            KeyEvent.KEYCODE_BACK -> AppAction.Back
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> AppAction.PlayPause
            KeyEvent.KEYCODE_MEDIA_NEXT -> AppAction.MediaNext
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> AppAction.MediaPrevious
            KeyEvent.KEYCODE_MEDIA_STOP -> AppAction.MediaStop
            KeyEvent.KEYCODE_MEDIA_REWIND -> AppAction.SeekBackward
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> AppAction.SeekForward
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> AppAction.Confirm
            else -> return false
        }
        dispatch(action)
        return true
    }

    fun resetHeldKeys() {
        longPressedKeys.clear()
        pressedKeys.clear()
        wheelAcceleration.reset()
    }

    private fun isHandledKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER -> true
        else -> false
    }
}

internal object InputPressClassifier {
    const val LONG_PRESS_REPEAT = 3
    const val LONG_PRESS_MS = 650L

    val PLAY_PAUSE_KEYS = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK
    )

    // ~400 ms per seek step at Android's repeat rate: continuous to the thumb,
    // without flooding the decoder with superseded seeks.
    const val SCRUB_REPEAT_PERIOD = 8

    fun isLongPress(frameworkLongPress: Boolean, repeatCount: Int, heldForMs: Long): Boolean =
        frameworkLongPress || repeatCount >= LONG_PRESS_REPEAT || heldForMs.coerceAtLeast(0) >= LONG_PRESS_MS

    fun holdOpensNowPlaying(keyCode: Int, fromLocalKeypad: () -> Boolean): Boolean? =
        if (keyCode in PLAY_PAUSE_KEYS) fromLocalKeypad() else null

    fun releaseOpensNowPlaying(keyCode: Int, heldForMs: Long): Boolean =
        keyCode in PLAY_PAUSE_KEYS && heldForMs.coerceAtLeast(0) >= LONG_PRESS_MS
}
