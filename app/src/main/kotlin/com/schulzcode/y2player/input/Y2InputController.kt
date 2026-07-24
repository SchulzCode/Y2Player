package com.schulzcode.y2player.input

import android.view.KeyEvent
import com.schulzcode.y2player.core.state.AppAction

class Y2InputController(private val dispatch: (AppAction) -> Unit) {
    private val longPressedKeys = HashSet<Int>()
    private val pressedKeys = HashSet<Int>()

    fun handle(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (!isHandledKey(keyCode)) return false

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                longPressedKeys.remove(keyCode)
                pressedKeys.add(keyCode)
            }
            val heldFor = (event.eventTime - event.downTime).coerceAtLeast(0)
            val longPress = InputPressClassifier.isLongPress(event.isLongPress, event.repeatCount, heldFor)
            if (longPress && longPressedKeys.add(keyCode)) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> dispatch(AppAction.SeekBackwardLong)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> dispatch(AppAction.SeekForwardLong)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> dispatch(AppAction.ConfirmLong)
                }
            } else if (longPress && event.repeatCount > 0 &&
                event.repeatCount % InputPressClassifier.SCRUB_REPEAT_PERIOD == 0
            ) {
                // Continuous scrubbing: while left/right stays held past the long-press
                // threshold, repeat the seek roughly every SCRUB_REPEAT_PERIOD key
                // repeats (~400 ms) instead of seeking only once per hold. The reducer
                // still gates seeks to the Now Playing screen.
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
        val action = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> AppAction.WheelCounterClockwise
            KeyEvent.KEYCODE_DPAD_DOWN -> AppAction.WheelClockwise
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

    /**
     * Key-repeat events between successive scrub seeks while a seek key stays held.
     * Android repeats roughly every 50 ms after the initial delay, so 8 repeats is
     * ~400 ms between seek steps — fast enough to feel continuous, slow enough not
     * to flood MediaPlayer with overlapping seekTo calls on the MT6582.
     */
    const val SCRUB_REPEAT_PERIOD = 8

    fun isLongPress(frameworkLongPress: Boolean, repeatCount: Int, heldForMs: Long): Boolean =
        frameworkLongPress || repeatCount >= LONG_PRESS_REPEAT || heldForMs.coerceAtLeast(0) >= LONG_PRESS_MS
}
