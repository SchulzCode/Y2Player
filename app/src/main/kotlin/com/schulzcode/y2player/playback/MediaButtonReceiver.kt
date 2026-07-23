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
        val source = if (intent.action == ACTION_Y2_KEY) HardwareKeyGate.Source.Y2_BROADCAST else HardwareKeyGate.Source.MEDIA_BROADCAST
        val playbackKeyCode = MediaButtonPolicy.playbackKeyCode(event.keyCode, source) ?: return
        if (source == HardwareKeyGate.Source.Y2_BROADCAST && !HardwareKeyGate.isInputAllowed(context, event.keyCode)) return
        if (event.action != KeyEvent.ACTION_UP) return
        if (!HardwareKeyGate.accept(event, source)) return
        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_MEDIA_BUTTON
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, playbackKeyCode))
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
    }
}

/** Source-scoped key mapping; it contains no screen, timing or playback state. */
internal object MediaButtonPolicy {
    fun playbackKeyCode(keyCode: Int, source: HardwareKeyGate.Source): Int? {
        if (source == HardwareKeyGate.Source.MEDIA_BROADCAST &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

        val mediaKey = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> source == HardwareKeyGate.Source.Y2_BROADCAST
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_HEADSETHOOK -> true
            else -> false
        }
        return if (mediaKey) keyCode else null
    }
}
