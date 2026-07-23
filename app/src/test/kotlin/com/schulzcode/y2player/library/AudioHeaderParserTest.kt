package com.schulzcode.y2player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class AudioHeaderParserTest {
    @Test fun readsPcmWaveTechnicalMetadata() {
        val file = File.createTempFile("y2-header", ".wav")
        try {
            writeWave(file, sampleRate = 96_000, bitDepth = 24, channels = 2, frames = 960)
            val result = AudioHeaderParser().read(file)
            assertEquals("audio/wav", result?.codec)
            assertEquals(96_000, result?.sampleRate)
            assertEquals(24, result?.bitDepth)
            assertEquals(2, result?.channels)
            assertEquals(10L, result?.durationMs)
        } finally { file.delete() }
    }

    @Test fun unknownFormatReturnsNull() {
        val file = File.createTempFile("y2-header", ".bin")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertNull(AudioHeaderParser().read(file))
        } finally { file.delete() }
    }

    @Test fun rejectsUnsignedWaveSampleRateThatOverflowsInt() {
        val file = File.createTempFile("y2-header-overflow", ".wav")
        try {
            FileOutputStream(file).use { out ->
                fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
                fun le16(value: Int) { out.write(value and 0xff); out.write((value ushr 8) and 0xff) }
                fun le32(value: Long) { repeat(4) { out.write(((value ushr (it * 8)) and 0xff).toInt()) } }
                ascii("RIFF"); le32(36); ascii("WAVE")
                ascii("fmt "); le32(16); le16(1); le16(2); le32(0xffff_ffffL)
                le32(192_000); le16(4); le16(16)
                ascii("data"); le32(0)
            }

            val result = AudioHeaderParser().read(file)

            assertEquals(null, result?.sampleRate)
            assertEquals(null, result?.durationMs)
        } finally { file.delete() }
    }

    @Test fun readsRf64DataSizeFromDs64Chunk() {
        val file = File.createTempFile("y2-header-rf64", ".wav")
        try {
            val dataSize = 1_920L
            FileOutputStream(file).use { out ->
                fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
                fun le16(value: Int) { out.write(value and 0xff); out.write((value ushr 8) and 0xff) }
                fun le32(value: Long) { repeat(4) { out.write(((value ushr (it * 8)) and 0xff).toInt()) } }
                fun le64(value: Long) { repeat(8) { out.write(((value ushr (it * 8)) and 0xff).toInt()) } }
                ascii("RF64"); le32(0xffff_ffffL); ascii("WAVE")
                ascii("ds64"); le32(28); le64(dataSize + 72); le64(dataSize); le64(480); le32(0)
                ascii("fmt "); le32(16); le16(1); le16(2); le32(48_000)
                le32(192_000); le16(4); le16(16)
                ascii("data"); le32(0xffff_ffffL); out.write(ByteArray(dataSize.toInt()))
            }

            val result = AudioHeaderParser().read(file)

            assertEquals(10L, result?.durationMs)
        } finally { file.delete() }
    }

    @Test fun readsDffTechnicalMetadataFromNestedSoundProperties() {
        val file = File.createTempFile("y2-header-dff", ".dff")
        try {
            val audioBytes = 7_056
            FileOutputStream(file).use { out ->
                fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
                fun be16(value: Int) { out.write((value ushr 8) and 0xff); out.write(value and 0xff) }
                fun be32(value: Long) { for (shift in 24 downTo 0 step 8) out.write(((value ushr shift) and 0xff).toInt()) }
                fun be64(value: Long) { for (shift in 56 downTo 0 step 8) out.write(((value ushr shift) and 0xff).toInt()) }
                ascii("FRM8"); be64(audioBytes + 66L); ascii("DSD ")
                ascii("PROP"); be64(34); ascii("SND ")
                ascii("FS  "); be64(4); be32(2_822_400)
                ascii("CHNL"); be64(2); be16(2)
                ascii("DSD "); be64(audioBytes.toLong()); out.write(ByteArray(audioBytes))
            }

            val result = AudioHeaderParser().read(file)

            assertEquals(2_822_400, result?.sampleRate)
            assertEquals(2, result?.channels)
            assertEquals(10L, result?.durationMs)
        } finally { file.delete() }
    }

    private fun writeWave(file: File, sampleRate: Int, bitDepth: Int, channels: Int, frames: Int) {
        val bytesPerFrame = channels * bitDepth / 8
        val dataSize = frames * bytesPerFrame
        FileOutputStream(file).use { out ->
            fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
            fun le16(value: Int) { out.write(value and 0xff); out.write((value ushr 8) and 0xff) }
            fun le32(value: Int) { repeat(4) { out.write((value ushr (it * 8)) and 0xff) } }
            ascii("RIFF"); le32(36 + dataSize); ascii("WAVE")
            ascii("fmt "); le32(16); le16(1); le16(channels); le32(sampleRate)
            le32(sampleRate * bytesPerFrame); le16(bytesPerFrame); le16(bitDepth)
            ascii("data"); le32(dataSize); out.write(ByteArray(dataSize))
        }
    }
}
