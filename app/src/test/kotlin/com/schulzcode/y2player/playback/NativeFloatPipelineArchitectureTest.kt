package com.schulzcode.y2player.playback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFloatPipelineArchitectureTest {
    @Test fun swresampleWritesPackedFloatDirectlyIntoTheJniBuffer() {
        val source = nativeSource()

        assertTrue(source.contains("AV_SAMPLE_FMT_FLT"))
        assertFalse(source.contains("AV_SAMPLE_FMT_S16"))
        assertTrue(source.contains("swr_convert(\n        decoder->resampler,\n        &output,"))
        assertFalse(source.contains("av_samples_alloc"))
    }

    @Test fun nativeCapacityUsesFramesChannelsAndFloatBytesWithoutOverflow() {
        val source = nativeSource()

        assertTrue(source.contains("decoder_output_capacity_bytes"))
        assertTrue(
            source.contains(
                "(int64_t) capacity_frames * decoder->output_channels * sizeof(float)"
            )
        )
        assertTrue(source.contains("capacity_bytes < (jlong) required_capacity_bytes"))
    }

    @Test fun expectedMetadataDecoderRejectionsDoNotPolluteAndroidErrorLogs() {
        val source = nativeSource()

        assertTrue(source.contains("static _Thread_local int metadata_decoder_rejection_expected"))
        assertTrue(source.contains("metadata_decoder_rejection_expected = 1;\n    result = avformat_find_stream_info"))
        assertTrue(source.contains("metadata_decoder_rejection_expected = 0;"))
        assertTrue(source.contains("strcmp(format, \"Codec (%s) not on whitelist '%s'\\n\") == 0"))
    }

    @Test fun boundedInvalidPacketsDoNotDiscardAlreadyDecodedAudio() {
        val source = nativeSource()

        assertTrue(source.contains("#define Y2_MAX_CONSECUTIVE_INVALID_PACKETS 8"))
        assertTrue(source.contains("if (result == AVERROR_INVALIDDATA)"))
        assertTrue(source.contains("decoder->consecutive_invalid_packets <=\n                Y2_MAX_CONSECUTIVE_INVALID_PACKETS"))
        assertTrue(source.contains("decoder->decoded_output_frames == 0 && decoder->invalid_packets_seen > 0"))
    }

    private fun nativeSource(): String =
        File(repositoryRoot(), "app/src/main/c/y2audio.c").readText()

    private fun repositoryRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "app/src/main/c/y2audio.c").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("repository root not found")
    }
}
