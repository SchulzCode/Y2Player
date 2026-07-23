package com.schulzcode.y2player.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCodecLabelsTest {
    @Test fun rawMimeTypesBecomeListenerFacingNames() {
        assertEquals("MP3", AudioCodecLabels.label("audio/mpeg", "mp3"))
        assertEquals("AAC", AudioCodecLabels.label("audio/mp4a-latm", "m4a"))
        assertEquals("FLAC", AudioCodecLabels.label("audio/flac", "flac"))
        assertEquals("WAV", AudioCodecLabels.label("audio/x-wav", "wav"))
        assertEquals("AIFF", AudioCodecLabels.label("audio/aiff-c", "aifc"))
        assertEquals("OGG", AudioCodecLabels.label("audio/vorbis", "ogg"))
        assertEquals("WavPack", AudioCodecLabels.label("audio/wavpack", "wv"))
        assertEquals("WMA", AudioCodecLabels.label("audio/x-ms-wma", "wma"))
        assertEquals("MKA", AudioCodecLabels.label("audio/x-matroska", "mka"))
    }

    @Test fun unknownOrMissingCodecsFallBackToTheExtension() {
        assertEquals("MP3", AudioCodecLabels.label(null, "mp3"))
        assertEquals("DSF", AudioCodecLabels.label("", "dsf"))
        assertEquals("OPUS", AudioCodecLabels.label("audio/opus", "opus"))
        // Nonsensical long identifiers fall back rather than shouting garbage.
        assertEquals("APE", AudioCodecLabels.label("application/octet-stream-strange", "ape"))
        assertEquals("AUDIO", AudioCodecLabels.label(null, ""))
    }
}
