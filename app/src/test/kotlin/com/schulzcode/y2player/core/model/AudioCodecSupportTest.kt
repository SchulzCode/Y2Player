package com.schulzcode.y2player.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCodecSupportTest {
    @Test fun alacAndAacInTheSameContainerAreSupported() {
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of("audio/mp4a-latm", "m4a"))
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of("audio/alac", "m4a"))
    }

    @Test fun vorbisAndOpusAreBothPlayableFromAnOggContainer() {
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of("audio/vorbis", "ogg"))
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of("audio/opus", "ogg"))
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of("audio/opus", "opus"))
    }

    @Test fun aiffIsSupportedNowThatItsDemuxerIsEnabled() {
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of("audio/aiff", "aiff"))
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of(null, "aiff"))
    }

    @Test fun containerNamesAreNotTreatedAsCodecs() {
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of(null, "m4a"))
        assertEquals(CodecSupport.UNKNOWN, AudioCodecSupport.of("audio/m4a", "m4a"))
    }

    @Test fun buildCodecsAreSupported() {
        listOf(
            "audio/mpeg" to "mp3",
            "audio/flac" to "flac",
            "audio/wav" to "wav",
            "audio/aac" to "aac",
            "audio/alac" to "m4a"
        ).forEach { (codec, extension) ->
            assertEquals(codec, CodecSupport.SUPPORTED, AudioCodecSupport.of(codec, extension))
        }
    }

    @Test fun codecsThisBuildCarriesNoDecoderForAreUnsupported() {
        listOf(
            "audio/amr" to "amr",
            "audio/mp2" to "mp2",
            "audio/x-ms-wma" to "wma",
            "audio/ape" to "ape",
            "audio/wavpack" to "wv",
            "audio/dsf" to "dsf",
            "audio/dff" to "dff",
            "audio/ac3" to "ac3"
        ).forEach { (codec, extension) ->
            assertEquals(codec, CodecSupport.UNSUPPORTED, AudioCodecSupport.of(codec, extension))
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
        assertEquals(CodecSupport.SUPPORTED, AudioCodecSupport.of("audio/X-ALAC", "m4a"))
    }
}
