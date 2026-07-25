package com.schulzcode.y2player.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCodecSupportTest {

    /**
     * The case the whole verdict exists for: both files are `.m4a`, and only the
     * AAC one plays on API 19. An extension-based rule cannot tell them apart.
     */
    @Test fun alacAndAacInTheSameContainerGetOppositeVerdicts() {
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of("audio/mp4a-latm", "m4a"))
        assertEquals(CodecSupport.UNSUPPORTED, AudioCodecSupport.of("audio/alac", "m4a"))
    }

    @Test fun vorbisAndOpusInTheSameContainerGetOppositeVerdicts() {
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of("audio/vorbis", "ogg"))
        assertEquals(CodecSupport.UNSUPPORTED, AudioCodecSupport.of("audio/opus", "ogg"))
    }

    @Test fun platformCodecsAreSupported() {
        listOf(
            "audio/mpeg" to "mp3",
            "audio/flac" to "flac",
            "audio/wav" to "wav",
            "audio/aac" to "aac",
            "audio/amr" to "amr",
            "audio/amr-wb" to "amr"
        ).forEach { (codec, extension) ->
            assertEquals(codec, CodecSupport.SUPPORTED, AudioCodecSupport.of(codec, extension))
        }
    }

    @Test fun codecsAbsentFromApi19AreUnsupported() {
        listOf(
            "audio/alac" to "m4a",
            "audio/opus" to "opus",
            "audio/wavpack" to "wv",
            "audio/dsf" to "dsf",
            "audio/dff" to "dff",
            "audio/aiff" to "aiff",
            "audio/ac3" to "ac3"
        ).forEach { (codec, extension) ->
            assertEquals(codec, CodecSupport.UNSUPPORTED, AudioCodecSupport.of(codec, extension))
        }
    }

    /**
     * MediaTek builds add codecs stock Android never had. Reporting these as
     * unsupported would put "not playable" on a file the device plays, so they
     * stay unknown and unlabelled.
     */
    @Test fun vendorDependentCodecsStayUnknown() {
        listOf("audio/x-ms-wma" to "wma", "audio/ape" to "ape", "audio/mp2" to "mp2").forEach { (codec, extension) ->
            assertEquals(codec, CodecSupport.UNKNOWN, AudioCodecSupport.of(codec, extension))
        }
    }

    @Test fun theExtensionIsUsedOnlyWhenTheCodecIsUnknown() {
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of(null, "mp3"))
        assertEquals(CodecSupport.UNSUPPORTED, AudioCodecSupport.of(null, "dsf"))
        assertEquals(CodecSupport.UNKNOWN, AudioCodecSupport.of(null, "xyz"))
        assertEquals(CodecSupport.UNKNOWN, AudioCodecSupport.of("", ""))
    }

    @Test fun mimePrefixesAndVendorPrefixesAreNormalised() {
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of("AUDIO/MPEG", "mp3"))
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of("  audio/x-wav  ", "wav"))
        assertEquals(CodecSupport.UNSUPPORTED, AudioCodecSupport.of("audio/X-ALAC", "m4a"))
    }
}
