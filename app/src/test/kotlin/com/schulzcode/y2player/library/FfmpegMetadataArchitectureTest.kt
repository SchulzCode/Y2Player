package com.schulzcode.y2player.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FfmpegMetadataArchitectureTest {
    @Test fun metadataPipelineHasNoFrameworkOrFormatSpecificParser() {
        val root = repositoryRoot()
        val reader = File(root, "app/src/main/kotlin/com/schulzcode/y2player/library/MetadataReader.kt").readText()
        val artwork = File(root, "app/src/main/kotlin/com/schulzcode/y2player/artwork/AlbumArtworkLoader.kt").readText()

        assertFalse(reader.contains("MediaMetadataRetriever"))
        assertFalse(artwork.contains("MediaMetadataRetriever"))
        assertFalse(File(root, "app/src/main/kotlin/com/schulzcode/y2player/library/AudioHeaderParser.kt").exists())
        assertFalse(File(root, "app/src/main/kotlin/com/schulzcode/y2player/library/OggOpusMetadataReader.kt").exists())
    }

    @Test fun scanProbePreventsDecoderOpening() {
        val root = repositoryRoot()
        val source = File(root, "app/src/main/c/y2audio.c").readText()
        val probe = source.substring(
            source.indexOf("static int metadata_probe_open"),
            source.indexOf("static void metadata_probe_close")
        )

        assertTrue(probe.contains("avformat_open_input"))
        assertTrue(probe.contains("avformat_find_stream_info"))
        assertTrue(probe.contains("__y2_metadata_no_decoder__"))
        assertTrue(probe.contains("protocol_whitelist = av_strdup(\"file\")"))
        assertTrue(probe.contains("skip_attached_picture_payloads = maximum_artwork_bytes <= 0"))
        assertTrue(probe.contains("max_attached_picture_payload_size = maximum_artwork_bytes"))
        assertTrue(probe.contains("metadata_interrupt_callback"))
        assertFalse(probe.contains("avcodec_alloc_context3"))
        assertFalse(probe.contains("avcodec_open2"))

        val ffmpegPatch = File(
            root,
            "tools/native/patches/0002-skip-attached-picture-payloads.patch"
        ).readText()
        assertTrue(ffmpegPatch.contains("av_buffer_unref(buf)"))
        assertTrue(ffmpegPatch.contains("METADATA_BLOCK_PICTURE"))
        assertTrue(ffmpegPatch.contains("s->skip_attached_picture_payloads"))
        assertTrue(ffmpegPatch.contains("(!buf && !pb)"))
        assertTrue(ffmpegPatch.contains("Do not pass it to av_get_packet()"))
    }

    @Test fun artworkJniRejectsInvalidSizesAndClearsAllocationFailures() {
        val source = File(repositoryRoot(), "app/src/main/c/y2audio.c").readText()
        val artworkReader = source.substring(
            source.indexOf("static jbyteArray native_read_artwork"),
            source.indexOf("static jint native_error_category")
        )

        assertTrue(artworkReader.contains("artwork->attached_pic.size > 0"))
        assertTrue(artworkReader.contains("artwork->attached_pic.size <= maximum_bytes"))
        assertTrue(artworkReader.contains("ExceptionCheck"))
        assertTrue(artworkReader.contains("ExceptionClear"))
        assertTrue(artworkReader.contains("result = NULL"))
    }

    @Test fun id3ArtworkProbeCarriesAFormatContextAndGuardsItsUse() {
        val ffmpegPatch = File(
            repositoryRoot(),
            "tools/native/patches/0002-skip-attached-picture-payloads.patch"
        ).readText()

        assertTrue(ffmpegPatch.contains("ff_id3v2_read_dict(AVFormatContext *s"))
        assertTrue(ffmpegPatch.contains("ff_id3v2_read_dict(s, s->pb"))
        assertTrue(ffmpegPatch.contains("ff_id3v2_read_dict(s, &pb.pub"))
        assertTrue(ffmpegPatch.contains("ff_id3v2_read_dict(s, pb"))
        assertTrue(ffmpegPatch.contains("if (s && (s->skip_attached_picture_payloads"))
        assertTrue(ffmpegPatch.contains("+    id3v2_read_internal(pb, metadata, s"))
    }

    @Test fun registeredJniConstructorMatchesTheKotlinRecord() {
        val constructor = FfmpegMetadata::class.java.declaredConstructors.single {
            it.parameterTypes.size == 29
        }
        val descriptor = constructor.parameterTypes.joinToString(
            prefix = "(", postfix = ")V", separator = ""
        ) { type ->
            when (type) {
                java.lang.Boolean.TYPE -> "Z"
                java.lang.Integer.TYPE -> "I"
                java.lang.Long.TYPE -> "J"
                String::class.java -> "Ljava/lang/String;"
                else -> throw AssertionError("unexpected JNI parameter $type")
            }
        }
        val source = File(repositoryRoot(), "app/src/main/c/y2audio.c").readText()
        assertTrue("native constructor descriptor must match Kotlin", source.contains("\"$descriptor\""))
    }

    @Test fun fatalNativeMetadataFaultsArePersistedAndExported() {
        val root = repositoryRoot()
        val native = File(root, "app/src/main/c/y2audio.c").readText()
        val application = File(
            root,
            "app/src/main/kotlin/com/schulzcode/y2player/Y2Application.kt"
        ).readText()
        val logger = File(
            root,
            "app/src/main/kotlin/com/schulzcode/y2player/diagnostics/DiagnosticLogger.kt"
        ).readText()
        val nativeBuild = File(root, "tools/native/build-ffmpeg.sh").readText()

        assertTrue(native.contains("Y2_NATIVE_CRASH v1"))
        assertTrue(native.contains("context->uc_mcontext.arm_pc"))
        assertTrue(native.contains("Y2_CRASH_STAGE_FIND_STREAM_INFO"))
        assertTrue(native.contains("y2_crash_set_path(native_path)"))
        assertTrue(application.contains("nativeConfigureCrashReporter"))
        assertTrue(logger.contains("y2-native-crash.log"))
        assertTrue(nativeBuild.contains("liby2audio.so.debug"))
    }

    private fun repositoryRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "app/src/main/c/y2audio.c").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("repository root not found")
    }
}
