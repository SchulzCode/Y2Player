package com.schulzcode.y2player.diagnostics

import com.schulzcode.y2player.BuildConfig
import com.schulzcode.y2player.core.model.AudioCodecSupport
import com.schulzcode.y2player.playback.NativeDecoder
import com.schulzcode.y2player.playback.PcmFormat

/**
 * What this build can play, read from the build itself.
 *
 * This replaces the format probe, which opened a sample of real files on a
 * dedicated thread and reported which ones worked. That question was worth
 * asking under MediaPlayer, where codec support was whatever the vendor firmware
 * happened to ship and could only be discovered by trying. It is not worth
 * asking now: FFmpeg is statically linked from a SHA-pinned source with an
 * explicit `--enable-decoder` / `--enable-demuxer` allowlist, so the answer is
 * fixed at build time and the probe could only ever rediscover it.
 *
 * What it cost to keep asking: a third HandlerThread, a second decoder lifecycle
 * with its own abort, timeout and generation handling, a database table, a
 * migration to clear that table, and a screen that could tell the user their
 * files were untested.
 */
object PlaybackCapabilities {

    data class Line(val label: String, val value: String)

    fun lines(): List<Line> = listOf(
        Line("Engine", runtimeInformation()),
        Line(
            "Output",
            "${PcmFormat.SAMPLE_RATE} Hz · ${PcmFormat.CHANNELS} ch · PCM16 · " +
                "${PcmFormat.BLOCK_FRAMES}-frame blocks"
        ),
        Line("Containers", AudioCodecSupport.DEMUXERS.sorted().joinToString(", ")),
        Line("Decoders", AudioCodecSupport.DECODERS.sorted().joinToString(", ")),
        Line("Build", "${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_ID}")
    )

    /**
     * Reported by the native library, so this doubles as a load check: if
     * `liby2audio.so` is missing or the wrong ABI, the failure shows up here
     * instead of only on the first attempt to play something.
     */
    private fun runtimeInformation(): String =
        runCatching { NativeDecoder.buildInformation() }
            .getOrElse { "native audio unavailable · ${it.javaClass.simpleName}" }
}
