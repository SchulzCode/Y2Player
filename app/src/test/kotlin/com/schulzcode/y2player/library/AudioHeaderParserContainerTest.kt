package com.schulzcode.y2player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Covers the container readers added for the formats the Y2 can actually play,
 * plus the ALAC case they exist for.
 *
 * The fixtures are assembled byte by byte rather than checked in as binaries so
 * that what each field means stays visible, and so a malformed-input test can be
 * written by deleting a few bytes.
 */
class AudioHeaderParserContainerTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val parser = AudioHeaderParser()

    private fun file(name: String, bytes: ByteArray): File =
        temporaryFolder.newFile(name).apply { writeBytes(bytes) }

    // ------------------------------------------------------------------ helpers

    private fun ByteArrayOutputStream.ascii(value: String) = write(value.toByteArray(Charsets.US_ASCII))
    private fun ByteArrayOutputStream.u8(value: Int) = write(value and 0xFF)
    private fun ByteArrayOutputStream.be16(value: Int) { u8(value shr 8); u8(value) }
    private fun ByteArrayOutputStream.be32(value: Long) {
        u8((value shr 24).toInt()); u8((value shr 16).toInt()); u8((value shr 8).toInt()); u8(value.toInt())
    }
    private fun ByteArrayOutputStream.le32(value: Long) {
        u8(value.toInt()); u8((value shr 8).toInt()); u8((value shr 16).toInt()); u8((value shr 24).toInt())
    }

    /** An MP4 box: 4-byte big-endian size covering the whole box, then the type. */
    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.be32((payload.size + 8).toLong())
        out.ascii(type)
        out.write(payload)
        return out.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach(out::write)
        return out.toByteArray()
    }

    /** `mdhd` version 0: timescale then duration in those units. */
    private fun mdhd(timescale: Long, units: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.u8(0); out.be16(0); out.u8(0)   // version + flags
        out.be32(0); out.be32(0)            // creation, modification
        out.be32(timescale)
        out.be32(units)
        return box("mdhd", out.toByteArray())
    }

    private fun hdlr(handler: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.be32(0)          // version + flags
        out.be32(0)          // pre_defined
        out.ascii(handler)
        out.be32(0); out.be32(0); out.be32(0)
        return box("hdlr", out.toByteArray())
    }

    /** AudioSampleEntry: 28 bytes, then any codec-specific child boxes. */
    private fun sampleEntry(type: String, channels: Int, sampleSize: Int, rate: Int, children: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(6) { out.u8(0) }   // reserved
        out.be16(1)               // data_reference_index
        out.be16(0); out.be16(0)  // version, revision
        out.be32(0)               // vendor
        out.be16(channels)
        out.be16(sampleSize)
        out.be16(0); out.be16(0)  // pre_defined, reserved
        out.be32(rate.toLong() shl 16) // 16.16 fixed point
        out.write(children)
        return box(type, out.toByteArray())
    }

    private fun stsd(entry: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.be32(0) // version + flags
        out.be32(1) // entry count
        out.write(entry)
        return box("stsd", out.toByteArray())
    }

    private fun mp4(entry: ByteArray, timescale: Long = 44_100, units: Long = 44_100, handler: String = "soun"): ByteArray =
        concat(
            box("ftyp", "M4A isom".toByteArray(Charsets.US_ASCII)),
            box("moov", concat(
                box("trak", concat(
                    box("mdia", concat(
                        mdhd(timescale, units),
                        hdlr(handler),
                        box("minf", box("stbl", stsd(entry)))
                    ))
                ))
            ))
        )

    // --------------------------------------------------------------------- MP4

    @Test fun aacInMp4IsIdentifiedWithItsResolution() {
        val result = parser.read(file("aac.m4a", mp4(sampleEntry("mp4a", channels = 2, sampleSize = 16, rate = 44_100))))
        assertEquals("audio/mp4a-latm", result?.codec)
        assertEquals(44_100, result?.sampleRate)
        assertEquals(2, result?.channels)
        assertEquals(1_000L, result?.durationMs)
    }

    /**
     * The regression this reader was written for: identical container, and the
     * only difference is the sample entry's four-character code.
     */
    @Test fun alacInMp4IsNotReportedAsAac() {
        val alacConfig = ByteArrayOutputStream().apply {
            be32(0)          // version + flags
            be32(4096)       // frameLength
            u8(0)            // compatibleVersion
            u8(24)           // bitDepth
            u8(40); u8(10); u8(14) // pb, mb, kb
            u8(2)            // numChannels
            be16(255)        // maxRun
            be32(0)          // maxFrameBytes
            be32(0)          // avgBitRate
            be32(96_000)     // sampleRate
        }.toByteArray()

        val entry = sampleEntry("alac", channels = 2, sampleSize = 16, rate = 44_100, children = box("alac", alacConfig))
        val result = parser.read(file("lossless.m4a", mp4(entry)))

        assertEquals("audio/alac", result?.codec)
        // The nested config wins: the sample entry could not express 96 kHz in
        // 16.16 fixed point, and reported 16-bit for a 24-bit file.
        assertEquals(96_000, result?.sampleRate)
        assertEquals(24, result?.bitDepth)
        assertEquals(2, result?.channels)
    }

    @Test fun m4rIsTreatedAsMp4() {
        val result = parser.read(file("tone.m4r", mp4(sampleEntry("mp4a", 1, 16, 22_050))))
        assertEquals("audio/mp4a-latm", result?.codec)
        assertEquals(22_050, result?.sampleRate)
    }

    @Test fun aVideoTrackIsSkippedRatherThanDescribed() {
        val result = parser.read(file("video.m4a", mp4(sampleEntry("mp4a", 2, 16, 44_100), handler = "vide")))
        assertNull(result)
    }

    @Test fun aTruncatedMp4YieldsNullRatherThanThrowing() {
        val full = mp4(sampleEntry("mp4a", 2, 16, 44_100))
        assertNull(parser.read(file("truncated.m4a", full.copyOf(full.size / 2))))
        assertNull(parser.read(file("empty.m4a", ByteArray(0))))
        assertNull(parser.read(file("garbage.m4a", ByteArray(64) { 0xFF.toByte() })))
    }

    // --------------------------------------------------------------------- MP3

    /** MPEG-1 Layer III, 44.1 kHz, joint stereo. */
    private fun mp3Frame(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte()
    )

    @Test fun mp3FrameHeaderGivesRateAndChannels() {
        val result = parser.read(file("track.mp3", concat(mp3Frame(), ByteArray(256))))
        assertEquals("audio/mpeg", result?.codec)
        assertEquals(44_100, result?.sampleRate)
        assertEquals(2, result?.channels)
        // Lossy: there is no bit depth to report.
        assertNull(result?.bitDepth)
    }

    @Test fun mp3SyncIsFoundPastAnId3v2Tag() {
        val tag = ByteArrayOutputStream().apply {
            ascii("ID3"); u8(3); u8(0); u8(0)
            // Syncsafe size of 200 bytes.
            u8(0); u8(0); u8(1); u8(0x48)
            // Padding that deliberately contains a byte pair resembling a sync.
            write(ByteArray(200) { if (it % 8 == 0) 0xFF.toByte() else 0x00 })
        }.toByteArray()
        val result = parser.read(file("tagged.mp3", concat(tag, mp3Frame(), ByteArray(64))))
        assertEquals("audio/mpeg", result?.codec)
        assertEquals(44_100, result?.sampleRate)
    }

    @Test fun layerIIIsLabelledSeparatelyFromLayerIII() {
        // Same header with the layer field set to II.
        val frame = byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0x90.toByte(), 0x64.toByte())
        val result = parser.read(file("track.mp2", concat(frame, ByteArray(256))))
        assertEquals("audio/mp2", result?.codec)
        assertEquals(44_100, result?.sampleRate)
    }

    @Test fun anMp3WithNoValidFrameYieldsNull() {
        assertNull(parser.read(file("noise.mp3", ByteArray(4096) { 0x00 })))
    }

    // -------------------------------------------------------------------- ADTS

    @Test fun adtsAacGivesRateAndChannels() {
        // syncword, MPEG-4, layer 0, no CRC; profile LC, rate index 4 (44.1 kHz),
        // channel configuration 2.
        val header = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50.toByte(), 0x80.toByte(), 0x00, 0x1F, 0xFC.toByte())
        val result = parser.read(file("stream.aac", concat(header, ByteArray(128))))
        assertEquals("audio/aac", result?.codec)
        assertEquals(44_100, result?.sampleRate)
        assertEquals(2, result?.channels)
    }

    // --------------------------------------------------------------------- Ogg

    private fun oggPage(packet: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.ascii("OggS")
        out.u8(0)                 // version
        out.u8(2)                 // header type: first page
        repeat(8) { out.u8(0) }   // granule position
        out.le32(1)               // serial
        out.le32(0)               // sequence
        out.le32(0)               // checksum
        out.u8(1)                 // one segment
        out.u8(packet.size)       // segment table
        out.write(packet)
        return out.toByteArray()
    }

    @Test fun vorbisIdentificationHeaderIsRead() {
        val packet = ByteArrayOutputStream().apply {
            u8(1); ascii("vorbis")
            le32(0)          // vorbis version
            u8(2)            // channels
            le32(44_100)     // sample rate
            repeat(16) { u8(0) }
        }.toByteArray()
        val result = parser.read(file("track.ogg", oggPage(packet)))
        assertEquals("audio/vorbis", result?.codec)
        assertEquals(44_100, result?.sampleRate)
        assertEquals(2, result?.channels)
    }

    @Test fun opusHeaderIsReadAndDistinguishedFromVorbis() {
        val packet = ByteArrayOutputStream().apply {
            ascii("OpusHead")
            u8(1)                // version
            u8(2)                // channels
            u8(0x38); u8(0x01)   // pre-skip, little endian
            le32(48_000)         // original input sample rate
            u8(0); u8(0)         // output gain
            u8(0)                // channel mapping family
        }.toByteArray()
        val result = parser.read(file("track.oga", oggPage(packet)))
        assertEquals("audio/opus", result?.codec)
        assertEquals(48_000, result?.sampleRate)
        assertEquals(2, result?.channels)
    }

    // --------------------------------------------------------------------- AMR

    @Test fun amrNarrowAndWideBandAreDistinguished() {
        val narrow = parser.read(file("speech.amr", "#!AMR\n".toByteArray(Charsets.US_ASCII) + ByteArray(32)))
        assertEquals("audio/amr", narrow?.codec)
        assertEquals(8_000, narrow?.sampleRate)
        assertEquals(1, narrow?.channels)

        val wide = parser.read(file("wide.amr", "#!AMR-WB\n".toByteArray(Charsets.US_ASCII) + ByteArray(32)))
        assertEquals("audio/amr-wb", wide?.codec)
        assertEquals(16_000, wide?.sampleRate)
    }

    @Test fun anUnknownExtensionIsNotParsed() {
        assertNull(parser.read(file("track.xyz", ByteArray(256))))
    }
}
