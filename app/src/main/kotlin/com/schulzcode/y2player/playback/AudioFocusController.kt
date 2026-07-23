package com.schulzcode.y2player.playback

import android.content.Context
import android.media.AudioManager

@Suppress("DEPRECATION")
class AudioFocusController(
    context: Context,
    private val callback: Callback
) : AudioManager.OnAudioFocusChangeListener {
    interface Callback {
        fun onPermanentLoss()
        fun onTransientLoss()
        fun onDuck()
        fun onGain()
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var hasFocus = false
    private var focusRequested = false

    fun request(): Boolean {
        if (hasFocus) return true
        val wasRequested = focusRequested
        hasFocus = audioManager.requestAudioFocus(
            this,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequested = hasFocus || wasRequested
        return hasFocus
    }

    fun abandon() {
        if (!focusRequested) return
        audioManager.abandonAudioFocus(this)
        hasFocus = false
        focusRequested = false
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                focusRequested = false
                callback.onPermanentLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                callback.onTransientLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> callback.onDuck()
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (!focusRequested) return
                hasFocus = true
                callback.onGain()
            }
        }
    }
}
