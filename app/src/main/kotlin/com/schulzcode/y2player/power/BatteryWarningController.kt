package com.schulzcode.y2player.power

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import com.schulzcode.y2player.playback.VolumeCurve
import com.schulzcode.y2player.playback.VolumeMode
import com.schulzcode.y2player.settings.AppPreferences
import java.io.File

internal enum class BatteryWarning { LOW, CRITICAL }

internal class BatteryWarningGate {
    private var previous = BatteryBucket.NORMAL

    fun update(percent: Int?, charging: Boolean): BatteryWarning? {
        if (percent == null) return null
        val current = when {
            charging || percent > LOW_PERCENT -> BatteryBucket.NORMAL
            percent <= CRITICAL_PERCENT -> BatteryBucket.CRITICAL
            else -> BatteryBucket.LOW
        }
        val warning = when {
            current == BatteryBucket.CRITICAL && previous != BatteryBucket.CRITICAL -> BatteryWarning.CRITICAL
            current == BatteryBucket.LOW && previous == BatteryBucket.NORMAL -> BatteryWarning.LOW
            else -> null
        }
        previous = current
        return warning
    }

    private enum class BatteryBucket { NORMAL, LOW, CRITICAL }

    private companion object {
        const val LOW_PERCENT = 15
        const val CRITICAL_PERCENT = 5
    }
}

internal object BatteryWarningVolume {
    fun localGain(mode: VolumeMode, appLevel: Int, musicStreamIndex: Int): Float = when (mode) {
        VolumeMode.PERCEPTUAL -> VolumeCurve.gainForLevel(appLevel)
        VolumeMode.SYSTEM -> if (musicStreamIndex > 0) 1f else 0f
    }
}

@Suppress("DEPRECATION")
class BatteryWarningController(
    context: Context,
    private val preferences: AppPreferences
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val gate = BatteryWarningGate()
    // The audited firmware marker means PowerUI's fixed-gain tone is suppressed.
    // Without it, leave the platform warning alone so an APK-only install cannot duplicate it.
    private val ownsWarningSound = File(SUPPRESSION_MARKER).isFile
    private var player: MediaPlayer? = null

    fun onBatteryChanged(percent: Int?, charging: Boolean) {
        if (gate.update(percent, charging) == null || !ownsWarningSound || player != null) return
        val value = preferences.snapshot()
        val streamIndex = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(0)
        val gain = BatteryWarningVolume.localGain(value.volumeMode, value.volumeLevel, streamIndex)
        if (gain <= 0f) return

        val candidate = MediaPlayer()
        player = candidate
        runCatching {
            candidate.setAudioStreamType(AudioManager.STREAM_MUSIC)
            candidate.setDataSource(WARNING_SOUND)
            candidate.setVolume(gain, gain)
            candidate.setOnCompletionListener(::release)
            candidate.setOnErrorListener { mediaPlayer, _, _ ->
                release(mediaPlayer)
                true
            }
            candidate.setOnPreparedListener { mediaPlayer ->
                if (player === mediaPlayer) mediaPlayer.start() else mediaPlayer.release()
            }
            candidate.prepareAsync()
        }.onFailure { release(candidate) }
    }

    fun release() {
        player?.let(::release)
    }

    private fun release(mediaPlayer: MediaPlayer) {
        if (player === mediaPlayer) player = null
        runCatching { mediaPlayer.release() }
    }

    private companion object {
        const val WARNING_SOUND = "/system/media/audio/ui/LowBattery.ogg"
        const val SUPPRESSION_MARKER = "/system/media/audio/ui/battery_y2player_suppressed"
    }
}
