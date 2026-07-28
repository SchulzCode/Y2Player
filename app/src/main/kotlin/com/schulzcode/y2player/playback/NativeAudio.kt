package com.schulzcode.y2player.playback

import java.nio.ByteBuffer

/** Minimal JNI surface. FFmpeg types and low-level operations stay in C. */
internal object NativeAudio {
    init {
        System.loadLibrary("y2audio")
    }

    external fun nativeCreate(): Long
    external fun nativeOpen(handle: Long, path: String, outputRate: Int, outputChannels: Int): Int
    external fun nativeDecode(handle: Long, output: ByteBuffer, capacityFrames: Int): Int
    external fun nativeSeek(handle: Long, positionMs: Long): Int
    external fun nativeRequestAbort(handle: Long)
    external fun nativeDurationMs(handle: Long): Long
    external fun nativeSourceSampleRate(handle: Long): Int
    external fun nativeSourceChannels(handle: Long): Int
    external fun nativeCodecName(handle: Long): String
    external fun nativeReplayGainTrackGain(handle: Long): Int
    external fun nativeReplayGainTrackPeak(handle: Long): Long
    external fun nativeReplayGainAlbumGain(handle: Long): Int
    external fun nativeReplayGainAlbumPeak(handle: Long): Long
    external fun nativeErrorCategory(handle: Long): Int
    external fun nativeErrorDetail(handle: Long): String
    external fun nativeClose(handle: Long)
    external fun nativeBuildInformation(): String
}

internal enum class NativeErrorCategory(val wireValue: Int) {
    NONE(0),
    SOURCE(1),
    UNSUPPORTED(2),
    CORRUPT(3),
    ABORTED(4),
    INTERNAL(5);

    companion object {
        fun fromWireValue(value: Int): NativeErrorCategory =
            values().firstOrNull { it.wireValue == value } ?: INTERNAL
    }
}

internal data class NativeStreamInfo(
    val durationMs: Long,
    val sourceSampleRate: Int,
    val sourceChannels: Int,
    val codecName: String,
    val replayGain: ReplayGainMetadata = ReplayGainMetadata()
)

internal class NativeDecoderException(
    val category: NativeErrorCategory,
    detail: String
) : Exception(detail.ifBlank { category.name })

/**
 * Opaque, single-owner decoder handle. Only [requestAbort] may be called from a
 * non-owner thread; its small lock prevents that signal racing with close.
 */
internal class NativeDecoder : AutoCloseable {
    private val lifecycleLock = Any()

    @Volatile
    private var handle: Long = NativeAudio.nativeCreate().also {
        check(it != 0L) { "Could not allocate native decoder" }
    }

    fun open(path: String, outputRate: Int, outputChannels: Int): NativeStreamInfo {
        val current = requireHandle()
        if (NativeAudio.nativeOpen(current, path, outputRate, outputChannels) < 0) {
            throw failure(current)
        }
        return NativeStreamInfo(
            durationMs = NativeAudio.nativeDurationMs(current).coerceAtLeast(0L),
            sourceSampleRate = NativeAudio.nativeSourceSampleRate(current),
            sourceChannels = NativeAudio.nativeSourceChannels(current),
            codecName = NativeAudio.nativeCodecName(current),
            replayGain = ReplayGainMetadata(
                trackGainDb = NativeAudio.nativeReplayGainTrackGain(current).gainDbOrNull(),
                trackPeak = NativeAudio.nativeReplayGainTrackPeak(current).peakOrNull(),
                albumGainDb = NativeAudio.nativeReplayGainAlbumGain(current).gainDbOrNull(),
                albumPeak = NativeAudio.nativeReplayGainAlbumPeak(current).peakOrNull()
            )
        )
    }

    /** Returns decoded stereo frame count, or zero at EOF; never bytes or shorts. */
    fun decode(output: ByteBuffer, frameCapacity: Int): Int {
        require(output.isDirect) { "Native decode requires a direct ByteBuffer" }
        require(frameCapacity > 0) { "frameCapacity must be positive" }
        val current = requireHandle()
        val decodedFrameCount = NativeAudio.nativeDecode(current, output, frameCapacity)
        if (decodedFrameCount < 0) throw failure(current)
        return decodedFrameCount
    }

    fun seekTo(positionMs: Long) {
        val current = requireHandle()
        if (NativeAudio.nativeSeek(current, positionMs.coerceAtLeast(0L)) < 0) {
            throw failure(current)
        }
    }

    fun requestAbort() {
        synchronized(lifecycleLock) {
            if (handle != 0L) NativeAudio.nativeRequestAbort(handle)
        }
    }

    override fun close() {
        val closing = synchronized(lifecycleLock) {
            val current = handle
            handle = 0L
            current
        }
        if (closing != 0L) NativeAudio.nativeClose(closing)
    }

    private fun requireHandle(): Long = handle.also {
        check(it != 0L) { "Native decoder is closed" }
    }

    private fun failure(current: Long): NativeDecoderException = NativeDecoderException(
        NativeErrorCategory.fromWireValue(NativeAudio.nativeErrorCategory(current)),
        NativeAudio.nativeErrorDetail(current).take(MAX_ERROR_DETAIL)
    )

    companion object {
        private const val MAX_ERROR_DETAIL = 256
        private const val REPLAY_GAIN_SCALE = 100_000f

        fun buildInformation(): String = NativeAudio.nativeBuildInformation()

        private fun Int.gainDbOrNull(): Float? =
            takeUnless { it == Int.MIN_VALUE }?.div(REPLAY_GAIN_SCALE)

        private fun Long.peakOrNull(): Float? =
            takeIf { it > 0L }?.div(REPLAY_GAIN_SCALE)
    }
}
