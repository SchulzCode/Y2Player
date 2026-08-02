package com.schulzcode.y2player.debug

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.mediatek.FMRadio.FMRadioNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Throwaway hardware probe for the stock MediaTek FM tuner.
 *
 * Two modes. "info" opens the device and reports what the driver says about
 * itself. "tune" powers up on a frequency, sweeps the antenna selection,
 * samples signal and RDS, and optionally opens the vendor audio path so a
 * human can confirm sound actually arrives.
 *
 * Every step is logged before and after it runs, so a call that blocks in an
 * ioctl still leaves evidence of where it stopped. The tuner is always powered
 * down and closed on the way out, including on failure.
 */
class FmProbeService : Service() {
    /** Read by the silence-writing thread, cleared when the probe tears down. */
    @Volatile
    private var silenceRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    /** Stereo 44.1 kHz PCM16 track used only to keep the output out of standby. */
    private fun newSilenceTrack(stream: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SILENCE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "no buffer size for stream $stream" }
        return AudioTrack(
            stream, SILENCE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2, AudioTrack.MODE_STREAM
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val outputPath = intent?.getStringExtra(EXTRA_OUTPUT) ?: DEFAULT_OUTPUT
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_INFO
        val freq = intent?.getFloatExtra(EXTRA_FREQ, DEFAULT_FREQ) ?: DEFAULT_FREQ
        val seconds = intent?.getIntExtra(EXTRA_SECONDS, DEFAULT_SECONDS) ?: DEFAULT_SECONDS
        val audio = intent?.getBooleanExtra(EXTRA_AUDIO, false) ?: false
        val speaker = intent?.getBooleanExtra(EXTRA_SPEAKER, false) ?: false
        val keepAlive = intent?.getBooleanExtra(EXTRA_KEEPALIVE, true) ?: true
        val restartAt = intent?.getIntExtra(EXTRA_RESTART_AT, 0) ?: 0
        Thread({
            val report = runCatching {
                probe(mode, freq, seconds, audio, speaker, keepAlive, restartAt)
            }
                .getOrElse { error -> JSONObject().put("fatalError", error.stackTraceToString()) }
            val text = report.toString(2)
            text.lineSequence().forEach { Log.i(TAG, it) }
            runCatching {
                val output = File(outputPath)
                output.parentFile?.mkdirs()
                output.writeText(text, Charsets.UTF_8)
                Log.i(TAG, "wrote $outputPath")
            }.onFailure { Log.w(TAG, "could not write $outputPath: $it") }
            stopSelf(startId)
        }, "y2-fm-probe").start()
        return START_NOT_STICKY
    }

    private fun probe(
        mode: String,
        freq: Float,
        seconds: Int,
        audio: Boolean,
        speaker: Boolean,
        keepAlive: Boolean,
        restartAt: Int
    ): JSONObject {
        val report = JSONObject()
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        report.put("timestamp", stamp)
        report.put("mode", mode)
        report.put("uid", Process.myUid())

        // /dev/fm is a character device owned by system:media, so the process
        // needs gid 1013 before any of this can work.
        val groups = runCatching {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("Groups:") }
                ?.removePrefix("Groups:")?.trim()
        }.getOrElse { "unreadable: $it" }
        Log.i(TAG, "uid=${Process.myUid()} groups=$groups")
        report.put("groups", groups ?: "")
        report.put("hasMediaGid", groups?.split(Regex("\\s+"))?.contains(MEDIA_GID) == true)

        val devFm = JSONObject()
        val node = File("/dev/fm")
        devFm.put("exists", node.exists())
        devFm.put("canRead", node.canRead())
        devFm.put(
            "openError",
            runCatching { FileInputStream(node).use { "none" } }.getOrElse { it.toString() }
        )
        report.put("devFm", devFm)

        val load = JSONObject()
        val loadError = runCatching { FMRadioNative.LOAD_ERROR }.getOrElse { it }
        load.put("ok", loadError == null)
        load.put("error", loadError?.toString() ?: JSONObject.NULL)
        report.put("loadLibrary", load)

        val calls = JSONArray()
        report.put("calls", calls)
        if (loadError != null) return report

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val headset = runCatching { audioManager.isWiredHeadsetOn }.getOrElse { false }
        Log.i(TAG, "wiredHeadsetOn=$headset")
        report.put("wiredHeadsetOn", headset)

        call(calls, "opendev") { FMRadioNative.opendev().toString() }
        call(calls, "getchipid") {
            val id = FMRadioNative.getchipid()
            "$id (0x${Integer.toHexString(id)})"
        }

        if (mode == MODE_INFO) {
            call(calls, "isFMPoweredUp") { FMRadioNative.isFMPoweredUp().toString() }
            call(calls, "getHardwareVersion") {
                FMRadioNative.getHardwareVersion()?.contentToString() ?: "null"
            }
            call(calls, "isRDSsupport") { FMRadioNative.isRDSsupport().toString() }
            call(calls, "readCapArray") { FMRadioNative.readCapArray().toString() }
            call(calls, "closedev") { FMRadioNative.closedev().toString() }
            return report
        }

        report.put("freq", freq.toDouble())
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
        var player: MediaPlayer? = null
        var silence: AudioTrack? = null
        var focusGranted = false
        try {
            focusGranted = audioManager.requestAudioFocus(
                null, STREAM_FM, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            report.put("audioFocus", if (focusGranted) "granted" else "denied")
            wakeLock.acquire()

            call(calls, "powerup($freq)") { FMRadioNative.powerup(freq).toString() }
            call(calls, "isFMPoweredUp") { FMRadioNative.isFMPoweredUp().toString() }
            call(calls, "tune($freq)") { FMRadioNative.tune(freq).toString() }
            call(calls, "setmute(false)") { FMRadioNative.setmute(false).toString() }

            // 0 is the long (headphone cable) aerial, 1 the short internal one.
            val sweep = JSONArray()
            for (antenna in intArrayOf(0, 1)) {
                val entry = JSONObject().put("antenna", antenna)
                entry.put(
                    "switchResult",
                    runCatching { FMRadioNative.switchAntenna(antenna) }
                        .getOrElse { -999 }
                )
                runCatching { FMRadioNative.tune(freq) }
                Thread.sleep(SETTLE_MS)
                entry.put("rssi", runCatching { FMRadioNative.readRssi() }.getOrElse { -999 })
                entry.put(
                    "stereo",
                    runCatching { if (FMRadioNative.stereoMono()) "stereo" else "mono" }
                        .getOrElse { "error: $it" }
                )
                Log.i(TAG, "antenna sweep: $entry")
                sweep.put(entry)
            }
            report.put("antennaSweep", sweep)

            // Leave the aerial on whichever the cable state implies, then retune.
            call(calls, "switchAntenna(final)") {
                FMRadioNative.switchAntenna(if (headset) 0 else 1).toString()
            }
            call(calls, "tune(final)") { FMRadioNative.tune(freq).toString() }
            call(calls, "rdsset(true)") { FMRadioNative.rdsset(true).toString() }

            if (speaker) {
                report.put("forceSpeaker", runCatching {
                    val system = Class.forName("android.media.AudioSystem")
                    val method = system.getDeclaredMethod(
                        "setForceUse", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                    )
                    method.invoke(null, FORCE_USE_FM, 1)
                    "ok"
                }.getOrElse { it.toString() })
            }

            if (audio) {
                // FM has its own MediaTek stream type and it sits at zero until
                // something raises it, which is silence rather than the hiss an
                // unlocked frequency would otherwise produce.
                report.put("fmVolume", runCatching {
                    val max = audioManager.getStreamMaxVolume(STREAM_FM)
                    audioManager.setStreamVolume(STREAM_FM, max, 0)
                    JSONObject()
                        .put("max", max)
                        .put("index", audioManager.getStreamVolume(STREAM_FM))
                }.getOrElse { JSONObject().put("error", it.toString()) })
                Log.i(TAG, "fmVolume=${report.opt("fmVolume")}")

                report.put("audioPath", runCatching {
                    val mp = MediaPlayer()
                    player = mp
                    mp.setDataSource(FM_DATA_SOURCE)
                    mp.setAudioStreamType(STREAM_FM)
                    mp.prepare()
                    mp.start()
                    "started"
                }.getOrElse { it.toString() })
                Log.i(TAG, "audioPath=${report.opt("audioPath")}")
            }

            if (mode == MODE_SCAN) {
                call(calls, "autoscan") {
                    FMRadioNative.autoscan()?.contentToString() ?: "null"
                }
                // Walk the EU band so a dead front end is distinguishable from
                // a merely quiet frequency.
                val band = JSONArray()
                var f = BAND_MIN
                while (f <= BAND_MAX) {
                    runCatching { FMRadioNative.tune(f) }
                    Thread.sleep(SWEEP_MS)
                    val rssi = runCatching { FMRadioNative.readRssi() }.getOrElse { -999 }
                    Log.i(TAG, "band $f -> $rssi")
                    band.put(JSONObject().put("freq", f.toDouble()).put("rssi", rssi))
                    f += BAND_STEP
                }
                report.put("bandSweep", band)
                return report
            }

            // FM reaches the jack only while the output is actually open. With
            // nothing writing PCM the MediaTek output drops into standby and
            // powers the path down, which is why FM is audible for about a
            // second after a screen unlock and silent otherwise. Streaming
            // silence holds the output open; FM bypasses the mixer in direct
            // connection mode, so the zeros do not overwrite it.
            if (keepAlive) {
                report.put("keepAlive", runCatching {
                    val stream = intArrayOf(STREAM_FM, AudioManager.STREAM_MUSIC)
                        .first { candidate ->
                            runCatching { newSilenceTrack(candidate) }
                                .onSuccess { silence = it }
                                .isSuccess
                        }
                    val track = silence!!
                    track.play()
                    silenceRunning = true
                    Thread({
                        val buffer = ShortArray(SILENCE_FRAMES)
                        while (silenceRunning) {
                            track.write(buffer, 0, buffer.size)
                        }
                    }, "y2-fm-keepalive").start()
                    "started (stream $stream)"
                }.getOrElse { it.toString() })
                Log.i(TAG, "keepAlive=${report.opt("keepAlive")}")
            }

            val samples = JSONArray()
            for (t in 1..seconds) {
                Thread.sleep(1000L)
                // Plugging headphones reprograms the analog mux to MUX_AUDIO and
                // nothing re-applies the FM path for the new device. Rebuilding
                // the FM player re-runs SetFmEnable against whatever output is
                // current, which is what the vendor app does on HEADSET_PLUG.
                if (restartAt > 0 && t == restartAt) {
                    report.put("audioRestart", runCatching {
                        player?.let { old ->
                            runCatching { old.stop() }
                            runCatching { old.release() }
                        }
                        val mp = MediaPlayer()
                        player = mp
                        mp.setDataSource(FM_DATA_SOURCE)
                        mp.setAudioStreamType(STREAM_FM)
                        mp.prepare()
                        mp.start()
                        "restarted"
                    }.getOrElse { it.toString() })
                    Log.i(TAG, "audioRestart=${report.opt("audioRestart")}")

                    // The FM player only ever sets AudioSetFmDigitalEnable. These
                    // are the analog-path keys the HAL also accepts, and nothing
                    // has exercised them yet.
                    val halKeys = JSONArray()
                    for (key in HAL_KEYS) {
                        val outcome = runCatching {
                            audioManager.setParameters(key)
                            "ok"
                        }.getOrElse { it.toString() }
                        Log.i(TAG, "setParameters($key) -> $outcome")
                        halKeys.put("$key $outcome")
                    }
                    report.put("halKeys", halKeys)
                }

                val sample = JSONObject().put("t", t)
                sample.put("rssi", runCatching { FMRadioNative.readRssi() }.getOrElse { -999 })
                sample.put(
                    "stereo",
                    runCatching { if (FMRadioNative.stereoMono()) "stereo" else "mono" }
                        .getOrElse { "error" }
                )
                runCatching { FMRadioNative.readrds() }
                sample.put("ps", runCatching { FMRadioNative.getPS().toText() }.getOrElse { "" })
                sample.put(
                    "pi",
                    runCatching { "0x${Integer.toHexString(FMRadioNative.getPI().toInt() and 0xFFFF)}" }
                        .getOrElse { "" }
                )
                Log.i(TAG, "sample $sample")
                samples.put(sample)
            }
            report.put("samples", samples)
        } finally {
            silenceRunning = false
            runCatching { silence?.stop() }
            runCatching { silence?.release() }
            runCatching { player?.stop() }
            runCatching { player?.release() }
            call(calls, "powerdown(0)") { FMRadioNative.powerdown(0).toString() }
            call(calls, "closedev") { FMRadioNative.closedev().toString() }
            if (focusGranted) runCatching { audioManager.abandonAudioFocus(null) }
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
        }
        return report
    }

    private fun ByteArray?.toText(): String =
        this?.takeWhile { it.toInt() != 0 }
            ?.toByteArray()
            ?.toString(Charsets.US_ASCII)
            ?.trim()
            .orEmpty()

    /** Records one native call verbatim, including Errors such as UnsatisfiedLinkError. */
    private fun call(sink: JSONArray, name: String, body: () -> String) {
        Log.i(TAG, "-> $name")
        val entry = JSONObject().put("name", name)
        runCatching(body).fold(
            onSuccess = { value ->
                Log.i(TAG, "<- $name = $value")
                entry.put("result", value).put("error", JSONObject.NULL)
            },
            onFailure = { error ->
                Log.w(TAG, "<- $name threw $error")
                entry.put("result", JSONObject.NULL)
                    .put("error", error.toString())
                    .put("stack", error.stackTraceToString())
            }
        )
        sink.put(entry)
    }

    private companion object {
        const val TAG = "Y2FmProbe"
        const val WAKE_LOCK_TAG = "y2player:fm-probe"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_MODE = "mode"
        const val EXTRA_FREQ = "freq"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_SPEAKER = "speaker"
        const val EXTRA_KEEPALIVE = "keepalive"
        const val EXTRA_RESTART_AT = "restartAt"
        const val SILENCE_RATE = 44100
        const val SILENCE_FRAMES = 1024
        const val MODE_INFO = "info"
        const val MODE_SCAN = "scan"
        const val BAND_MIN = 87.5f
        const val BAND_MAX = 108.0f
        const val BAND_STEP = 0.5f
        const val SWEEP_MS = 120L
        const val DEFAULT_OUTPUT = "/sdcard/y2-fm-probe.json"
        const val DEFAULT_FREQ = 102.8f
        const val DEFAULT_SECONDS = 10
        const val MEDIA_GID = "1013"
        const val SETTLE_MS = 700L

        // The vendor launcher drives FM audio through this MediaPlayer source
        // rather than through AudioManager.setParameters.
        const val FM_DATA_SOURCE = "MEDIATEK://MEDIAPLAYER_PLAYERTYPE_FM"

        // MediaTek's extra AudioSystem force-use slot, as used by the stock app.
        const val FORCE_USE_FM = 5

        // Analog-path keys the primary HAL accepts, in the order the vendor
        // AudioFMController applies them.
        val HAL_KEYS = arrayOf(
            "AudioFmIsWiredHeadsetOn=1",
            "AudioSetFmEnable=1"
        )

        // MediaTek appends STREAM_FM and STREAM_MATV after the AOSP stream
        // types, confirmed against the device's own dumpsys audio ordering.
        const val STREAM_FM = 10
    }
}
