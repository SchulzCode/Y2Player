package com.schulzcode.y2player.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.schulzcode.y2player.input.HardwareKeyGate

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON && intent.action != ACTION_Y2_KEY) return
        val event = extractKeyEvent(intent) ?: return
        if (event.keyCode !in ALLOWED_KEY_CODES) return
        val source = if (intent.action == ACTION_Y2_KEY) HardwareKeyGate.Source.Y2_BROADCAST else HardwareKeyGate.Source.MEDIA_BROADCAST
        if (!HardwareKeyGate.isInputAllowed(context, event.keyCode, source)) return
        if (!HardwareKeyGate.accept(event, source)) return
        if (!MediaButtonPressGate.shouldDispatch(
                keyCode = event.keyCode,
                action = event.action,
                eventTime = event.eventTime,
                downTime = event.downTime,
                repeatCount = event.repeatCount,
                source = source
            )
        ) return
        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_MEDIA_BUTTON
            putExtra(Intent.EXTRA_KEY_EVENT, event)
        }
        context.startService(serviceIntent)
    }

    @Suppress("DEPRECATION")
    private fun extractKeyEvent(intent: Intent): KeyEvent? {
        val parcelableKeys = arrayOf(Intent.EXTRA_KEY_EVENT, "keyevent", "key_event", "KeyEvent", "event")
        parcelableKeys.forEach { key ->
            intent.getParcelableExtra<KeyEvent>(key)?.let { return it }
        }
        val integerKeys = arrayOf("keyCode", "key_code", "keycode")
        integerKeys.forEach { key ->
            if (intent.hasExtra(key)) {
                val code = intent.getIntExtra(key, KeyEvent.KEYCODE_UNKNOWN)
                if (code != KeyEvent.KEYCODE_UNKNOWN) return KeyEvent(KeyEvent.ACTION_UP, code)
            }
        }
        return null
    }

    companion object {
        const val ACTION_Y2_KEY = "com.innioasis.y2.key"
        private val ALLOWED_KEY_CODES = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_HEADSETHOOK
        )
    }
}

/**
 * Normalizes inconsistent API-19 Bluetooth key delivery to one command per
 * physical press. Some stacks send DOWN only while others send DOWN+UP or UP
 * only. Dispatch the first usable edge and suppress its matching release.
 */
internal object MediaButtonPressGate {
    private var downKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var downEventTime = Long.MIN_VALUE
    private var downTime = Long.MIN_VALUE
    private var downSource: HardwareKeyGate.Source? = null

    @Synchronized
    fun shouldDispatch(
        keyCode: Int,
        action: Int,
        eventTime: Long,
        downTime: Long,
        repeatCount: Int,
        source: HardwareKeyGate.Source
    ): Boolean = when (action) {
        KeyEvent.ACTION_DOWN -> {
            if (repeatCount > 0) false
            else {
                downKeyCode = keyCode
                downEventTime = eventTime
                this.downTime = downTime
                downSource = source
                true
            }
        }
        KeyEvent.ACTION_UP -> {
            val age = if (downEventTime == Long.MIN_VALUE) Long.MAX_VALUE
            else kotlin.math.abs(eventTime - downEventTime)
            val samePress = keyCode == downKeyCode && source == downSource &&
                ((downTime > 0L && this.downTime > 0L && downTime == this.downTime) || age <= RELEASE_WINDOW_MS)
            clearPendingDown()
            !samePress
        }
        else -> false
    }

    @Synchronized
    fun reset() = clearPendingDown()

    private fun clearPendingDown() {
        downKeyCode = KeyEvent.KEYCODE_UNKNOWN
        downEventTime = Long.MIN_VALUE
        downTime = Long.MIN_VALUE
        downSource = null
    }

    private const val RELEASE_WINDOW_MS = 5_000L
}
