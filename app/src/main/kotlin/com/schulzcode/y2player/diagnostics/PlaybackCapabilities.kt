package com.schulzcode.y2player.diagnostics

import com.schulzcode.y2player.BuildConfig
import com.schulzcode.y2player.core.model.AudioCodecSupport
import com.schulzcode.y2player.playback.NativeDecoder
import com.schulzcode.y2player.playback.PcmFormat

object PlaybackCapabilities {
    data class Line(val label: String, val value: String)

    fun lines(): List<Line> = listOf(
        Line("Engine", runtimeInformation()),
        Line(
            "Output",
            "${PcmFormat.SAMPLE_RATE} Hz · ${PcmFormat.CHANNELS} ch · " +
                "float32 DSP -> PCM16 output · " +
                "${PcmFormat.BLOCK_FRAMES}-frame blocks"
        ),
        Line("Containers", AudioCodecSupport.DEMUXERS.sorted().joinToString(", ")),
        Line("Decoders", AudioCodecSupport.DECODERS.sorted().joinToString(", ")),
        Line("Build", "${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_ID}")
    )

    private fun runtimeInformation(): String =
        runCatching { NativeDecoder.buildInformation() }
            .getOrElse { "native audio unavailable · ${it.javaClass.simpleName}" }
}
