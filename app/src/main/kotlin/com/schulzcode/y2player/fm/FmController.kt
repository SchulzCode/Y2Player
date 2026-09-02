package com.schulzcode.y2player.fm

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.mediatek.FMRadio.FMRadioNative

/**
 * Drives the stock MediaTek tuner.
 *
 * Everything here is best-effort. The vendor shipped the driver and libraries
 * but removed the FM application, so any call can fail on a device where the
 * parts were never wired together. Y2Player is the launcher, so a missing
 * library has to degrade to "FM unavailable" rather than take the app down;
 * every native call is guarded, including against UnsatisfiedLinkError.
 *
 * Call these from a single background thread. PlaybackService owns that thread
 * and the audio output, so FM and the decoder cannot both hold the output.
 */
class FmController(
    private val context: Context,
    private val onChanged: (FmState) -> Unit
) {
    private var state = FmState()
    private var opened = false
    private var player: MediaPlayer? = null

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun snapshot(): FmState = state

    /** Opens the device and reports whether the vendor stack is usable at all. */
    fun probe(): FmState {
        if (FMRadioNative.LOAD_ERROR != null) {
            return publish(state.copy(available = false, message = "FM library unavailable"))
        }
        if (!openDevice()) {
            return publish(state.copy(available = false, message = "FM hardware unavailable"))
        }
        val chip = native("getchipid", -1) { FMRadioNative.getchipid() }
        Log.i(TAG, "tuner chip id 0x${Integer.toHexString(chip)}")
        return publish(state.copy(available = chip > 0, message = null))
    }

    fun start(frequencyKhz: Int) {
        if (!openDevice()) {
            publish(state.copy(available = false, message = "FM hardware unavailable"))
            return
        }
        val khz = FmBand.clamp(frequencyKhz)
        if (!native("powerup", false) { FMRadioNative.powerup(khz.toMhz()) }) {
            publish(state.copy(powered = false, message = "Could not power up the tuner"))
            return
        }
        native("setmute", 0) { FMRadioNative.setmute(false) }
        startAudio()
        publish(state.copy(available = true, powered = true, frequencyKhz = khz, message = null))
        applyTune(khz)
    }

    fun stop() {
        stopAudio()
        if (state.powered) native("powerdown", false) { FMRadioNative.powerdown(0) }
        publish(state.copy(powered = false, seeking = false, rssi = FmState.RSSI_UNKNOWN))
    }

    /** Releases the character device as well; used when the screen is left. */
    fun release() {
        stop()
        if (opened) {
            native("closedev", false) { FMRadioNative.closedev() }
            opened = false
        }
    }

    fun tuneBy(steps: Int) = applyTune(FmBand.step(state.frequencyKhz, steps))

    fun tuneTo(frequencyKhz: Int) = applyTune(FmBand.clamp(frequencyKhz))

    /**
     * Hands the search to the tuner, which uses its own signal threshold and so
     * finds stations the meter alone would miss.
     */
    fun seek(up: Boolean) {
        if (!state.powered) return
        publish(state.copy(seeking = true))
        val found = native("seek", -1f) { FMRadioNative.seek(state.frequencyKhz.toMhz(), up) }
        val khz = if (found > 0f) Math.round(found * 1000f) else state.frequencyKhz
        publish(state.copy(seeking = false))
        applyTune(FmBand.clamp(khz))
    }

    private fun applyTune(khz: Int) {
        if (!state.powered) {
            publish(state.copy(frequencyKhz = khz))
            return
        }
        native("tune", false) { FMRadioNative.tune(khz.toMhz()) }
        // Sampled once per tuning change rather than on a timer, so an idle FM
        // screen costs nothing.
        val rssi = native("readRssi", FmState.RSSI_UNKNOWN) { FMRadioNative.readRssi() }
        val stereo = native("stereoMono", false) { FMRadioNative.stereoMono() }
        publish(state.copy(frequencyKhz = khz, rssi = rssi, stereo = stereo))
    }

    private fun openDevice(): Boolean {
        if (opened) return true
        opened = native("opendev", false) { FMRadioNative.opendev() }
        return opened
    }

    /**
     * The vendor routes FM through a dedicated MediaPlayer source rather than
     * an AudioTrack. The v3.2.0 FM audio HAL keeps the second I2S output and AFE
     * path active while direct FM is connected, so no synthetic PCM keep-alive
     * is needed (and one would unnecessarily mix into the output).
     */
    private fun startAudio() {
        if (player != null) return
        runCatching {
            val manager = audioManager
            manager.setStreamVolume(STREAM_FM, manager.getStreamMaxVolume(STREAM_FM), 0)
        }.onFailure { Log.w(TAG, "FM stream volume: $it") }

        runCatching {
            MediaPlayer().also {
                player = it
                it.setDataSource(FM_DATA_SOURCE)
                it.setAudioStreamType(STREAM_FM)
                it.prepare()
                it.start()
            }
        }.onFailure {
            Log.w(TAG, "FM audio path: $it")
            player = null
        }
    }

    private fun stopAudio() {
        player?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        player = null
    }

    private fun publish(next: FmState): FmState {
        state = next
        onChanged(next)
        return next
    }

    private fun <T> native(name: String, fallback: T, body: () -> T): T =
        try {
            body()
        } catch (error: Throwable) {
            Log.w(TAG, "FMRadioNative.$name failed: $error")
            fallback
        }

    private fun Int.toMhz(): Float = this / 1000f

    private companion object {
        const val TAG = "Y2Fm"
        const val FM_DATA_SOURCE = "MEDIATEK://MEDIAPLAYER_PLAYERTYPE_FM"

        // MediaTek appends STREAM_FM after the AOSP stream types.
        const val STREAM_FM = 10
    }
}
