package com.schulzcode.y2player.core.model

import com.schulzcode.y2player.library.LibraryScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the app's idea of what it can play tied to what is actually compiled in.
 *
 * Before the FFmpeg migration these were independent: a Kotlin table described
 * AOSP's codec support and the FFmpeg configure line described the build, and
 * nothing noticed when they disagreed. This makes disagreement a build failure.
 */
class FfmpegBuildCapabilitiesTest {

    @Test fun decoderListMatchesTheFfmpegConfigureLine() {
        assertEquals(flagValues("enable-decoder"), AudioCodecSupport.DECODERS)
    }

    @Test fun demuxerListMatchesTheFfmpegConfigureLine() {
        assertEquals(flagValues("enable-demuxer"), AudioCodecSupport.DEMUXERS)
    }

    /**
     * The specific drift this test was written after: the big-endian PCM
     * decoders AIFF needs were built, but its demuxer was not enabled, so AIFF
     * was reported unplayable for a reason that was not true.
     */
    @Test fun aiffHasBothADemuxerAndAPcmDecoder() {
        assertTrue("aiff" in AudioCodecSupport.DEMUXERS)
        assertTrue("pcm_s16be" in AudioCodecSupport.DECODERS)
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of("audio/aiff", "aiff"))
    }

    /** Every codec the app claims is playable must have a container to arrive in. */
    @Test fun everyEnabledDecoderIsReachableThroughSomeEnabledDemuxer() {
        assertTrue(AudioCodecSupport.DEMUXERS.isNotEmpty())
        assertTrue(AudioCodecSupport.DECODERS.isNotEmpty())
        listOf("mp3" to "mp3", "flac" to "flac", "aac" to "aac", "alac" to "mov", "vorbis" to "ogg")
            .forEach { (decoder, demuxer) ->
                assertTrue("$decoder decoder", decoder in AudioCodecSupport.DECODERS)
                assertTrue("$demuxer demuxer", demuxer in AudioCodecSupport.DEMUXERS)
            }
    }

    /**
     * The invariant that was actually broken: AIFF was enabled in the FFmpeg
     * build and labelled playable, but `LibraryScanner` never indexed it, so the
     * label described a file the user could never see. A capability list that
     * the scanner does not honour is not a capability.
     */
    @Test fun everyPlayableExtensionIsIndexedByTheScanner() {
        val notIndexed = AudioCodecSupport.SUPPORTED_EXTENSIONS -
            LibraryScanner.SUPPORTED_EXTENSIONS
        assertEquals(
            "labelled playable but never indexed: ${notIndexed.sorted()}",
            emptySet<String>(),
            notIndexed
        )
    }

    /** And the reverse: nothing is indexed that the app declares unplayable. */
    @Test fun nothingIndexedIsDeclaredUnplayable() {
        LibraryScanner.SUPPORTED_EXTENSIONS.forEach { extension ->
            assertNotEquals(
                "scanner indexes .$extension but the codec table rejects it",
                CodecSupport.UNSUPPORTED,
                AudioCodecSupport.of(null, extension)
            )
        }
    }

    /** AIFF is only reachable end to end if all three layers agree. */
    @Test fun aiffIsReachableThroughEveryLayer() {
        listOf("aif", "aiff", "aifc").forEach { extension ->
            assertTrue(
                "scanner must index .$extension",
                extension in LibraryScanner.SUPPORTED_EXTENSIONS
            )
            assertEquals(
                CodecSupport.SUPPORTED,
                AudioCodecSupport.of(null, extension)
            )
        }
        assertTrue("aiff" in AudioCodecSupport.DEMUXERS)
        assertTrue("pcm_s16be" in AudioCodecSupport.DECODERS)
    }

    private fun flagValues(flag: String): Set<String> {
        val script = buildScript().readText()
        val match = Regex("--$flag=([A-Za-z0-9_,]+)").find(script)
            ?: throw AssertionError("--$flag= not found in ${buildScript()}")
        return match.groupValues[1]
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun buildScript(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "tools/native/build-ffmpeg.sh")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        throw AssertionError(
            "tools/native/build-ffmpeg.sh not found above ${System.getProperty("user.dir")}"
        )
    }
}
