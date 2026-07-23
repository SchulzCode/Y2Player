package com.schulzcode.y2player.library

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.pow

/** Lightweight technical metadata reader. It only reads bounded file headers. */
class AudioHeaderParser {
    data class Result(
        val codec: String? = null,
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
        val channels: Int? = null,
        val durationMs: Long? = null
    )

    fun read(file: File): Result? = try {
        when (file.extension.lowercase()) {
            "wav", "wave" -> readWave(file)
            "flac" -> readFlac(file)
            "aif", "aiff", "aifc" -> readAiff(file)
            "wv" -> readWavPack(file)
            "dsf" -> readDsf(file)
            "dff" -> readDff(file)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun readWave(file: File): Result? = RandomAccessFile(file, "r").use { input ->
        val container = input.readAscii(4)
        if (container !in setOf("RIFF", "RF64") || input.length() < 12) return null
        val isRf64 = container == "RF64"
        input.skipBytes(4)
        if (input.readAscii(4) != "WAVE") return null
        var channels: Int? = null
        var sampleRate: Int? = null
        var bitDepth: Int? = null
        var byteRate: Long? = null
        var dataSize: Long? = null
        var rf64DataSize: Long? = null
        while (input.filePointer + 8 <= input.length() && input.filePointer < HEADER_SCAN_LIMIT) {
            val id = input.readAscii(4)
            val size = input.readUInt32LE()
            val dataStart = input.filePointer
            when (id) {
                "ds64" -> if (isRf64 && size >= 28) {
                    input.readUInt64LE() // complete RIFF size
                    rf64DataSize = input.readUInt64LE().takeIf { it in 0..input.length() }
                    input.readUInt64LE() // sample count
                    input.readUInt32LE() // optional table length
                }
                "fmt " -> if (size >= 16) {
                    input.readUInt16LE() // format code
                    channels = input.readUInt16LE().validChannels()
                    sampleRate = input.readUInt32LE().validSampleRate()
                    byteRate = input.readUInt32LE().takeIf { it > 0 }
                    input.readUInt16LE() // block align
                    bitDepth = input.readUInt16LE().validBitDepth()
                }
                "data" -> dataSize = if (isRf64 && size == UINT32_MAX) rf64DataSize else size
            }
            input.seek((dataStart + size + (size and 1L)).coerceAtMost(input.length()))
            if (sampleRate != null && dataSize != null) break
        }
        val duration = if (sampleRate != null && channels != null && bitDepth != null && dataSize != null && dataSize!! > 0) {
            safeScaledDuration(dataSize!!, 1_000L, byteRate ?: 0L)
        } else null
        Result("audio/wav", sampleRate, bitDepth, channels, duration)
    }

    private fun readFlac(file: File): Result? = RandomAccessFile(file, "r").use { input ->
        if (input.readAscii(4) != "fLaC") return null
        while (input.filePointer + 4 <= input.length() && input.filePointer < HEADER_SCAN_LIMIT) {
            val header = input.readUnsignedByte()
            val type = header and 0x7f
            val size = input.readUInt24BE()
            if (type == 0 && size >= 34) {
                input.skipBytes(10)
                val packed = ByteArray(8).also(input::readFully)
                val value = packed.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
                val sampleRate = ((value ushr 44) and 0xfffff).validSampleRate()
                val channels = (((value ushr 41) and 0x7) + 1).toInt()
                val bitDepth = (((value ushr 36) and 0x1f) + 1).toInt()
                val totalSamples = value and 0xfffffffffL
                val duration = sampleRate?.let { safeScaledDuration(totalSamples, 1_000L, it.toLong()) }
                return Result("audio/flac", sampleRate, bitDepth, channels, duration)
            }
            input.seek((input.filePointer + size).coerceAtMost(input.length()))
            if (header and 0x80 != 0) break
        }
        null
    }

    private fun readAiff(file: File): Result? = RandomAccessFile(file, "r").use { input ->
        if (input.readAscii(4) != "FORM") return null
        input.skipBytes(4)
        val form = input.readAscii(4)
        if (form != "AIFF" && form != "AIFC") return null
        while (input.filePointer + 8 <= input.length() && input.filePointer < HEADER_SCAN_LIMIT) {
            val id = input.readAscii(4)
            val size = input.readUInt32BE()
            val start = input.filePointer
            if (id == "COMM" && size >= 18) {
                val channels = input.readUInt16BE().validChannels()
                val sampleFrames = input.readUInt32BE()
                val bitDepth = input.readUInt16BE().validBitDepth()
                val extendedRate = input.readExtended80()
                val sampleRate = extendedRate.takeIf { it.isFinite() && it >= 1.0 && it <= MAX_SAMPLE_RATE.toDouble() }?.toInt()
                val duration = sampleRate?.let { safeScaledDuration(sampleFrames, 1_000L, it.toLong()) }
                return Result(if (form == "AIFC") "audio/aiff-c" else "audio/aiff", sampleRate, bitDepth, channels, duration)
            }
            input.seek((start + size + (size and 1L)).coerceAtMost(input.length()))
        }
        null
    }

    private fun readWavPack(file: File): Result? = RandomAccessFile(file, "r").use { input ->
        if (input.readAscii(4) != "wvpk") return null
        input.skipBytes(4)
        input.readUInt16LE() // version
        input.skipBytes(2)
        val totalSamples = input.readUInt32LE()
        input.skipBytes(8)
        val flags = input.readUInt32LE()
        val rateIndex = ((flags ushr 23) and 0x0f).toInt()
        val sampleRate = WAVPACK_RATES.getOrNull(rateIndex)?.takeIf { it > 0 }
        val bytesPerSample = ((flags and 0x3) + 1).toInt()
        val bitDepth = (bytesPerSample * 8 - ((flags ushr 13) and 0x1f).toInt()).coerceAtLeast(1)
        val channels = if (flags and 0x4L != 0L) 1 else 2
        val duration = if (sampleRate != null && totalSamples != 0xffffffffL) {
            safeScaledDuration(totalSamples, 1_000L, sampleRate.toLong())
        } else null
        Result("audio/wavpack", sampleRate, bitDepth, channels, duration)
    }

    private fun readDsf(file: File): Result? = RandomAccessFile(file, "r").use { input ->
        if (input.readAscii(4) != "DSD ") return null
        input.seek(28)
        while (input.filePointer + 12 <= input.length() && input.filePointer < HEADER_SCAN_LIMIT) {
            val id = input.readAscii(4)
            val size = input.readUInt64LE()
            if (size < 0) return null
            val start = input.filePointer
            if (id == "fmt " && size >= 52) {
                input.readUInt32LE() // format version
                input.readUInt32LE() // format id
                input.readUInt32LE() // channel type
                val channels = input.readUInt32LE().validPositiveInt(MAX_CHANNELS)
                val sampleRate = input.readUInt32LE().validSampleRate()
                val bitDepth = input.readUInt32LE().validPositiveInt(MAX_BIT_DEPTH)
                val samples = input.readUInt64LE()
                val duration = if (sampleRate != null) safeScaledDuration(samples, 1_000L, sampleRate.toLong()) else null
                return Result("audio/dsf", sampleRate, bitDepth, channels, duration)
            }
            if (size < 12) break
            input.seek((start + size - 12).coerceAtMost(input.length()))
        }
        null
    }

    private fun readDff(file: File): Result? = RandomAccessFile(file, "r").use { input ->
        if (input.readAscii(4) != "FRM8") return null
        input.skipBytes(8)
        if (input.readAscii(4) != "DSD ") return null
        var sampleRate: Int? = null
        var channels: Int? = null
        var audioBytes: Long? = null
        while (input.filePointer + 12 <= input.length() && input.filePointer < HEADER_SCAN_LIMIT) {
            val id = input.readAscii(4)
            val size = input.readUInt64BE()
            if (size < 0) return null
            val start = input.filePointer
            val end = boundedChunkEnd(start, size, input.length()) ?: return null
            when (id) {
                "FS  " -> if (size >= 4) sampleRate = input.readUInt32BE().validSampleRate()
                "CHNL" -> if (size >= 2) channels = input.readUInt16BE().validChannels()
                "DSD " -> audioBytes = size
                "PROP" -> readDffSoundProperties(input, end)?.let { properties ->
                    properties.sampleRate?.let { sampleRate = it }
                    properties.channels?.let { channels = it }
                }
            }
            input.seek((end + (size and 1L)).coerceAtMost(input.length()))
        }
        val rate = sampleRate
        val channelCount = channels
        val duration = if (rate != null && rate > 0 && channelCount != null && channelCount > 0 && audioBytes != null) {
            safeScaledDuration(audioBytes!!, 8_000L, rate.toLong() * channelCount)
        } else null
        if (rate == null && channelCount == null) null else Result("audio/dff", rate, 1, channelCount, duration)
    }

    private data class DffSoundProperties(val sampleRate: Int?, val channels: Int?)

    private fun readDffSoundProperties(input: RandomAccessFile, end: Long): DffSoundProperties? {
        if (input.filePointer + 4 > end || input.readAscii(4) != "SND ") return null
        var sampleRate: Int? = null
        var channels: Int? = null
        while (input.filePointer + 12 <= end && input.filePointer < HEADER_SCAN_LIMIT) {
            val id = input.readAscii(4)
            val size = input.readUInt64BE()
            if (size < 0) return null
            val start = input.filePointer
            val chunkEnd = boundedChunkEnd(start, size, end) ?: return null
            when (id) {
                "FS  " -> if (size >= 4) sampleRate = input.readUInt32BE().validSampleRate()
                "CHNL" -> if (size >= 2) channels = input.readUInt16BE().validChannels()
            }
            input.seek((chunkEnd + (size and 1L)).coerceAtMost(end))
        }
        return DffSoundProperties(sampleRate, channels)
    }

    private fun RandomAccessFile.readAscii(length: Int): String = ByteArray(length).also(::readFully).toString(Charsets.US_ASCII)
    private fun RandomAccessFile.readUInt16LE(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)
    private fun RandomAccessFile.readUInt16BE(): Int = (readUnsignedByte() shl 8) or readUnsignedByte()
    private fun RandomAccessFile.readUInt24BE(): Int = (readUnsignedByte() shl 16) or (readUnsignedByte() shl 8) or readUnsignedByte()
    private fun RandomAccessFile.readUInt32LE(): Long = (readUnsignedByte().toLong()) or
        (readUnsignedByte().toLong() shl 8) or (readUnsignedByte().toLong() shl 16) or (readUnsignedByte().toLong() shl 24)
    private fun RandomAccessFile.readUInt32BE(): Long = (readUnsignedByte().toLong() shl 24) or
        (readUnsignedByte().toLong() shl 16) or (readUnsignedByte().toLong() shl 8) or readUnsignedByte().toLong()
    private fun RandomAccessFile.readUInt64LE(): Long = readLongBytes(littleEndian = true)
    private fun RandomAccessFile.readUInt64BE(): Long = readLongBytes(littleEndian = false)

    private fun RandomAccessFile.readLongBytes(littleEndian: Boolean): Long {
        var result = 0L
        if (littleEndian) repeat(8) { index -> result = result or (readUnsignedByte().toLong() shl (index * 8)) }
        else repeat(8) { result = (result shl 8) or readUnsignedByte().toLong() }
        return result
    }

    private fun RandomAccessFile.readExtended80(): Double {
        val exponent = readUInt16BE()
        val mantissa = readUInt64BE()
        if (exponent == 0 && mantissa == 0L) return 0.0
        if (exponent == 0x7fff) return Double.NaN
        val sign = if (exponent and 0x8000 != 0) -1.0 else 1.0
        val unbiased = (exponent and 0x7fff) - 16383
        val unsignedMantissa = (mantissa ushr 1).toDouble() * 2.0 + (mantissa and 1L)
        return sign * unsignedMantissa * 2.0.pow(unbiased - 63)
    }

    private fun Int.validChannels(): Int? = takeIf { it in 1..MAX_CHANNELS }
    private fun Int.validBitDepth(): Int? = takeIf { it in 1..MAX_BIT_DEPTH }
    private fun Long.validSampleRate(): Int? = validPositiveInt(MAX_SAMPLE_RATE)
    private fun Long.validPositiveInt(maximum: Int): Int? = takeIf { it in 1..maximum.toLong() }?.toInt()

    private fun boundedChunkEnd(start: Long, size: Long, limit: Long): Long? {
        if (start < 0 || start > limit || size < 0 || size > limit - start) return null
        return start + size
    }

    private fun safeScaledDuration(units: Long, scale: Long, divisor: Long): Long? {
        if (units < 0 || scale <= 0 || divisor <= 0) return null
        val whole = units / divisor
        val remainder = units % divisor
        if (whole > Long.MAX_VALUE / scale) return null
        if (remainder > Long.MAX_VALUE / scale) return null
        val scaledWhole = whole * scale
        val scaledRemainder = remainder * scale / divisor
        return if (scaledWhole <= Long.MAX_VALUE - scaledRemainder) scaledWhole + scaledRemainder else null
    }

    companion object {
        private const val HEADER_SCAN_LIMIT = 4L * 1024L * 1024L
        private const val MAX_SAMPLE_RATE = 50_000_000
        private const val MAX_CHANNELS = 64
        private const val MAX_BIT_DEPTH = 64
        private const val UINT32_MAX = 0xffff_ffffL
        private val WAVPACK_RATES = intArrayOf(
            6_000, 8_000, 9_600, 11_025, 12_000, 16_000, 22_050, 24_000,
            32_000, 44_100, 48_000, 64_000, 88_200, 96_000, 192_000, 0
        )
    }
}
