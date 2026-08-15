package com.schulzcode.y2player.playback

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

internal object PcmFormat {
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2
    const val BLOCK_FRAMES = 4_096
    const val BLOCK_SAMPLES = BLOCK_FRAMES * CHANNELS

    const val FLOAT_BYTES_PER_SAMPLE = 4
    const val FLOAT_BYTES_PER_FRAME = CHANNELS * FLOAT_BYTES_PER_SAMPLE
    const val FLOAT_BLOCK_BYTES = BLOCK_FRAMES * FLOAT_BYTES_PER_FRAME

    const val PCM16_BYTES_PER_SAMPLE = 2
    const val PCM16_BYTES_PER_FRAME = CHANNELS * PCM16_BYTES_PER_SAMPLE
    const val PCM16_BLOCK_BYTES = BLOCK_FRAMES * PCM16_BYTES_PER_FRAME
}

internal interface AudioOutput {
    val audioSessionId: Int
    val isPlaying: Boolean
    val bufferFrameCount: Int
    val playedFrames: Long
    val submittedFrames: Long

    val configuration: String

    fun write(pcm: FloatArray, offsetSamples: Int, sampleCount: Int): Int
    fun setOutputGain(gain: Float)
    fun pause()
    fun resume()
    fun flush()
    fun stop()
    fun release()
}

internal class AudioOutputException(message: String) : Exception(message)

internal interface PcmSink {
    fun writeSome(pcm: ShortArray, offsetShorts: Int, shortCount: Int): Int
}

internal object PcmWriteLoop {
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

internal class Pcm16StagingBuffer(sampleCapacity: Int) {
    val samples = ShortArray(sampleCapacity)

    fun stage(source: FloatArray, offsetSamples: Int, sampleCount: Int): ShortArray {
        require(offsetSamples >= 0) { "offsetSamples must not be negative" }
        require(sampleCount >= 0) { "sampleCount must not be negative" }
        require(offsetSamples + sampleCount <= source.size) {
            "source range exceeds the float PCM array"
        }
        require(offsetSamples + sampleCount <= samples.size) {
            "source range exceeds the PCM16 staging array"
        }

        val end = offsetSamples + sampleCount
        var index = offsetSamples
        while (index < end) {
            val sample = source[index]
            samples[index] = when {
                !sample.isFinite() -> 0
                sample > 1f -> Short.MAX_VALUE
                sample < -1f -> Short.MIN_VALUE
                else -> quantizeFinite(sample)
            }
            index += 1
        }
        return samples
    }

    private fun quantizeFinite(sample: Float): Short = when {
        sample >= 1f -> Short.MAX_VALUE
        sample <= -1f -> Short.MIN_VALUE
        else -> Math.round(sample * PCM16_SCALE)
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    companion object {
        private const val PCM16_SCALE = 32_768f
    }
}

internal class PlaybackHeadAccumulator {
    private var initialized = false
    private var rebaseOnNextUpdate = false
    private var previousRaw = 0L
    private var accumulated = 0L

    fun update(rawHead: Int): Long {
        val raw = rawHead.toLong() and UINT32_MASK
        if (!initialized) {
            initialized = true
            previousRaw = raw
            accumulated = if (rebaseOnNextUpdate) 0L else raw
            rebaseOnNextUpdate = false
            return accumulated
        }

        val delta = (raw - previousRaw) and UINT32_MASK
        previousRaw = raw
        if (delta <= MAX_FORWARD_DELTA) accumulated += delta
        return accumulated
    }

    fun reset() {
        initialized = false
        rebaseOnNextUpdate = true
        previousRaw = 0L
        accumulated = 0L
    }

    companion object {
        private const val UINT32_MASK = 0xffff_ffffL
        private const val MAX_FORWARD_DELTA = 0x7fff_ffffL
    }
}

@Suppress("DEPRECATION")
internal class AudioTrackOutput : AudioOutput, PcmSink {
    private var track: AudioTrack?
    private val head = PlaybackHeadAccumulator()
    private val pcm16Staging = Pcm16StagingBuffer(PcmFormat.BLOCK_SAMPLES)
    private var writtenFrames = 0L

    override val bufferFrameCount: Int

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

        val frameBytes = PcmFormat.PCM16_BYTES_PER_FRAME
        val bufferBytes = maxOf(minimumBytes * 2, PcmFormat.PCM16_BLOCK_BYTES * 2)
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
        bufferFrameCount = bufferFrames
        configuration = "sampleRate=${PcmFormat.SAMPLE_RATE} channels=${PcmFormat.CHANNELS} " +
            "encoding=PCM16 minBufferBytes=$minimumBytes bufferBytes=$bufferBytes " +
            "bufferFrames=$bufferFrames bufferMs=${bufferFrames * 1_000L / PcmFormat.SAMPLE_RATE} " +
            "blockFrames=${PcmFormat.BLOCK_FRAMES} session=${created.audioSessionId} " +
            "state=${created.state}"
    }

    // The track is created once and never reconfigured. Effects attach to this
    // session id, so recreating it would silently detach every one.
    override val audioSessionId: Int
        get() = track?.audioSessionId ?: 0

    override val isPlaying: Boolean
        get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    override val playedFrames: Long
        get() = track?.let { head.update(it.playbackHeadPosition) } ?: 0L

    override val submittedFrames: Long
        get() = writtenFrames

    override fun writeSome(pcm: ShortArray, offsetShorts: Int, shortCount: Int): Int =
        requireTrack().write(pcm, offsetShorts, shortCount)

    override fun write(pcm: FloatArray, offsetSamples: Int, sampleCount: Int): Int {
        require(offsetSamples >= 0) { "offsetSamples must not be negative" }
        require(sampleCount >= 0) { "sampleCount must not be negative" }
        require(offsetSamples + sampleCount <= pcm.size) {
            "write range exceeds the float PCM array"
        }
        require(offsetSamples % PcmFormat.CHANNELS == 0) {
            "offsetSamples must start on a PCM frame boundary"
        }
        require(sampleCount % PcmFormat.CHANNELS == 0) {
            "sampleCount must contain complete PCM frames"
        }
        val staged = pcm16Staging.stage(pcm, offsetSamples, sampleCount)
        PcmWriteLoop.writeFully(staged, offsetSamples, sampleCount, this)
        val decodedFrameCount = sampleCount / PcmFormat.CHANNELS
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
