package com.schulzcode.y2player.playback

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * The one description of Y2Player's PCM geometry.
 *
 * Every buffer size, frame count and byte count in the playback path derives
 * from these five values. They were previously restated in three places
 * (`FfmpegPlaybackEngine`, `PcmMixer`, `PcmOutputFormat`), which meant a change
 * to the block size had to be made correctly in all of them.
 *
 * These are compile-time constants rather than a negotiated format because the
 * engine resamples everything to 44.1 kHz stereo PCM16 in native code. Nothing
 * downstream is ever asked to handle another layout, so there is no format to
 * configure and no configuration that can fail.
 */
internal object PcmFormat {
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2
    const val BYTES_PER_SAMPLE = 2
    const val BLOCK_FRAMES = 4_096
    const val BLOCK_SHORTS = BLOCK_FRAMES * CHANNELS
    const val BLOCK_BYTES = BLOCK_SHORTS * BYTES_PER_SAMPLE
}

/** Strict PCM boundary shared by standard and future verified hardware output. */
internal interface AudioOutput {
    val audioSessionId: Int
    val playedFrames: Long
    val submittedFrames: Long

    /** One-shot description of the negotiated track, logged once at start-up. */
    val configuration: String

    /**
     * Copies [decodedShortCount] PCM16 shorts starting at [offsetShorts] and
     * returns the submitted frame count.
     *
     * The offset exists because a decoded block is not always consumed in one
     * write: a crossfade mixes only as many frames as both sides can supply, so
     * the longer block is written from where the previous turn stopped.
     */
    fun write(pcm: ShortArray, offsetShorts: Int, decodedShortCount: Int): Int
    /** Live post-buffer gain, applied equally to both output channels. */
    fun setOutputGain(gain: Float)
    fun pause()
    fun resume()
    fun flush()
    fun stop()
    fun release()
}

internal class AudioOutputException(message: String) : Exception(message)

/**
 * The API-19 `AudioTrack.write(short[], int, int)` contract, isolated so the
 * partial-write loop can be exercised without an AudioTrack.
 *
 * [AudioTrackOutput] implements this itself and passes `this` to the loop, so
 * unlike the previous `ShortArrayWriter` lambda nothing is allocated per write.
 */
internal interface PcmSink {
    /** Offset, requested count and return value are all measured in PCM16 shorts. */
    fun writeSome(pcm: ShortArray, offsetShorts: Int, shortCount: Int): Int
}

/** Pure partial-write loop, kept outside AudioTrack so host tests cover it. */
internal object PcmWriteLoop {

    /**
     * Writes [decodedShortCount] shorts, retrying short writes.
     *
     * A zero return is not back-pressure: MODE_STREAM `write` blocks until the
     * track has room. It is tolerated a bounded number of times to absorb a
     * transient report and then treated as a stalled track, so this can neither
     * spin nor hang.
     */
    fun writeFully(
        pcm: ShortArray,
        offsetShorts: Int,
        decodedShortCount: Int,
        sink: PcmSink
    ): Int {
        require(offsetShorts >= 0)
        require(decodedShortCount >= 0)
        require(offsetShorts + decodedShortCount <= pcm.size)
        var writtenShortCount = 0
        var zeroProgressWrites = 0
        while (writtenShortCount < decodedShortCount) {
            val remainingShortCount = decodedShortCount - writtenShortCount
            val result = sink.writeSome(
                pcm,
                offsetShorts + writtenShortCount,
                remainingShortCount
            )
            when {
                result > remainingShortCount -> throw AudioOutputException(
                    "Audio output returned an invalid short count: $result"
                )
                result > 0 -> {
                    writtenShortCount += result
                    zeroProgressWrites = 0
                }
                result == 0 -> if (++zeroProgressWrites >= MAX_ZERO_PROGRESS_WRITES) {
                    throw AudioOutputException(
                        "Audio output made no write progress in $zeroProgressWrites attempts"
                    )
                }
                else -> throw AudioOutputException("Audio output write failed: $result")
            }
        }
        return writtenShortCount
    }

    private const val MAX_ZERO_PROGRESS_WRITES = 3
}

/** Converts AudioTrack's wrapping unsigned 32-bit head into a monotonic count. */
internal class PlaybackHeadAccumulator {
    private var initialized = false
    private var previousRaw = 0L
    private var accumulated = 0L

    fun update(rawHead: Int): Long {
        val raw = rawHead.toLong() and UINT32_MASK
        if (!initialized) {
            initialized = true
            previousRaw = raw
            accumulated = raw
            return accumulated
        }

        val delta = (raw - previousRaw) and UINT32_MASK
        previousRaw = raw
        // A backwards reset is a huge unsigned delta; flush/recreation starts a
        // new position base instead of pretending billions of frames played.
        if (delta <= MAX_FORWARD_DELTA) accumulated += delta
        return accumulated
    }

    fun reset() {
        initialized = false
        previousRaw = 0L
        accumulated = 0L
    }

    companion object {
        private const val UINT32_MASK = 0xffff_ffffL
        private const val MAX_FORWARD_DELTA = 0x7fff_ffffL
    }
}

/**
 * API-19-compatible MODE_STREAM output.
 *
 * The track is created once, in the constructor, and never reconfigured. That
 * is what makes [audioSessionId] stable for the life of the engine, which the
 * effects backend depends on: an effect is attached to the session, so silently
 * recreating the track would detach every effect the user had configured.
 */
@Suppress("DEPRECATION")
internal class AudioTrackOutput : AudioOutput, PcmSink {
    private var track: AudioTrack?
    private val head = PlaybackHeadAccumulator()
    private var writtenFrames = 0L

    override val configuration: String

    init {
        val minimumBytes = AudioTrack.getMinBufferSize(
            PcmFormat.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBytes <= 0) {
            throw AudioOutputException(
                "AudioTrack rejects ${PcmFormat.SAMPLE_RATE} Hz stereo PCM16: $minimumBytes"
            )
        }

        val frameBytes = PcmFormat.CHANNELS * PcmFormat.BYTES_PER_SAMPLE
        val bufferBytes = maxOf(minimumBytes * 2, PcmFormat.BLOCK_BYTES * 2)
            .coerceAtMost(MAX_AUDIO_TRACK_BUFFER_BYTES)
            .let { it - (it % frameBytes) }

        val created = AudioTrack(
            AudioManager.STREAM_MUSIC,
            PcmFormat.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
            AudioTrack.MODE_STREAM
        )
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            created.release()
            throw AudioOutputException("AudioTrack initialization failed")
        }

        track = created
        val bufferFrames = bufferBytes / frameBytes
        configuration = "sampleRate=${PcmFormat.SAMPLE_RATE} channels=${PcmFormat.CHANNELS} " +
            "encoding=PCM16 minBufferBytes=$minimumBytes bufferBytes=$bufferBytes " +
            "bufferFrames=$bufferFrames bufferMs=${bufferFrames * 1_000L / PcmFormat.SAMPLE_RATE} " +
            "blockFrames=${PcmFormat.BLOCK_FRAMES} session=${created.audioSessionId} " +
            "state=${created.state}"
    }

    override val audioSessionId: Int
        get() = track?.audioSessionId ?: 0

    override val playedFrames: Long
        get() = track?.let { head.update(it.playbackHeadPosition) } ?: 0L

    override val submittedFrames: Long
        get() = writtenFrames

    override fun writeSome(pcm: ShortArray, offsetShorts: Int, shortCount: Int): Int =
        requireTrack().write(pcm, offsetShorts, shortCount)

    override fun write(pcm: ShortArray, offsetShorts: Int, decodedShortCount: Int): Int {
        require(offsetShorts >= 0) { "offsetShorts must not be negative" }
        require(decodedShortCount >= 0) { "decodedShortCount must not be negative" }
        require(offsetShorts + decodedShortCount <= pcm.size) {
            "write range exceeds the PCM array"
        }
        require(offsetShorts % PcmFormat.CHANNELS == 0) {
            "offsetShorts must start on a PCM frame boundary"
        }
        require(decodedShortCount % PcmFormat.CHANNELS == 0) {
            "decodedShortCount must contain complete PCM frames"
        }
        // `this` rather than a lambda: the previous indirection captured a local
        // and so allocated one object per decoded block, ~11 per second.
        PcmWriteLoop.writeFully(pcm, offsetShorts, decodedShortCount, this)
        val decodedFrameCount = decodedShortCount / PcmFormat.CHANNELS
        writtenFrames += decodedFrameCount
        return decodedFrameCount
    }

    override fun setOutputGain(gain: Float) {
        val safeGain = gain.coerceIn(0f, 1f)
        val result = requireTrack().setStereoVolume(safeGain, safeGain)
        if (result != AudioTrack.SUCCESS) {
            throw AudioOutputException("AudioTrack volume update failed: $result")
        }
    }

    override fun pause() {
        val current = track ?: return
        if (current.playState == AudioTrack.PLAYSTATE_PLAYING) current.pause()
    }

    override fun resume() {
        requireTrack().play()
    }

    override fun flush() {
        val current = track ?: return
        if (current.playState == AudioTrack.PLAYSTATE_PLAYING) current.pause()
        current.flush()
        head.reset()
        writtenFrames = 0L
    }

    override fun stop() {
        val current = track ?: return
        if (current.playState != AudioTrack.PLAYSTATE_STOPPED) current.stop()
    }

    override fun release() {
        val releasing = track
        track = null
        head.reset()
        writtenFrames = 0L
        if (releasing != null) {
            try {
                if (releasing.playState != AudioTrack.PLAYSTATE_STOPPED) releasing.stop()
            } catch (_: IllegalStateException) {
                // A failed or already-released track still needs release below.
            }
            releasing.release()
        }
    }

    private fun requireTrack(): AudioTrack = track
        ?: throw AudioOutputException("Audio output has been released")

    companion object {
        private const val MAX_AUDIO_TRACK_BUFFER_BYTES = 256 * 1024
    }
}
