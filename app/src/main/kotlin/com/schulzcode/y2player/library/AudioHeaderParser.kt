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
            "mp3", "mp2" -> readMpegAudio(file)
            "m4a", "m4r", "mp4" -> readMp4(file)
            "aac" -> readAdts(file)
            "ogg", "oga", "opus" -> readOgg(file)
            "amr" -> readAmr(file)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- MP4 family

    private data class Atom(val type: String, val dataStart: Long, val end: Long)

    /**
     * MP4 / M4A. Reads the sample entry's four-character code, which is the only
     * place the actual codec is written down.
     *
     * This is what separates AAC from ALAC in an identical `.m4a`. Without it the
     * codec fell back to the retriever's container MIME type, which
     * `AudioCodecLabels` maps to "AAC" — so an ALAC file was reported as AAC and
     * then failed at prepare, because API 19 has no ALAC decoder (that arrived in
     * API 31).
     */
    private fun readMp4(file: File): Result? = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        if (length < 16) return null
        val moov = findAtom(input, 0, length, "moov") ?: return null
        // A file may carry several tracks; only a sound track describes the audio.
        var cursor = moov.dataStart
        var guard = 0
        while (cursor + 8 <= moov.end && guard++ < MAX_ATOMS) {
            val trak = readAtomHeader(input, cursor, moov.end) ?: break
            if (trak.type == "trak") {
                readMp4SoundTrack(input, trak.dataStart, trak.end)?.let { return it }
            }
            cursor = trak.end
        }
        null
    }

    private fun readMp4SoundTrack(input: RandomAccessFile, start: Long, limit: Long): Result? {
        val mdia = findAtom(input, start, limit, "mdia") ?: return null
        val hdlr = findAtom(input, mdia.dataStart, mdia.end, "hdlr")
        if (hdlr != null && hdlr.dataStart + 12 <= hdlr.end) {
            input.seek(hdlr.dataStart + 8)
            if (input.readAscii(4) != "soun") return null
        }

        var timescale = 0L
        var units = 0L
        findAtom(input, mdia.dataStart, mdia.end, "mdhd")?.let { mdhd ->
            if (mdhd.dataStart + 20 <= mdhd.end) {
                input.seek(mdhd.dataStart)
                val version = input.readUnsignedByte()
                input.skipBytes(3) // flags
                if (version == 1 && mdhd.dataStart + 32 <= mdhd.end) {
                    input.skipBytes(16) // creation + modification
                    timescale = input.readUInt32BE()
                    units = input.readUInt64BE()
                } else {
                    input.skipBytes(8) // creation + modification
                    timescale = input.readUInt32BE()
                    units = input.readUInt32BE()
                }
            }
        }

        val stsd = findPath(input, mdia.dataStart, mdia.end, "minf", "stbl", "stsd") ?: return null
        // stsd carries version/flags and an entry count before the first entry.
        val entry = readAtomHeader(input, stsd.dataStart + 8, stsd.end) ?: return null
        if (entry.dataStart + AUDIO_SAMPLE_ENTRY_BYTES > entry.end) return null

        input.seek(entry.dataStart + 16)
        var channels = input.readUInt16BE().validChannels()
        var bitDepth = input.readUInt16BE().validBitDepth()
        input.skipBytes(4) // pre_defined + reserved
        // 16.16 fixed point: the integer part cannot express rates above 65535,
        // which is why the ALAC config below is preferred when present.
        var sampleRate = (input.readUInt32BE() ushr 16).validSampleRate()

        val codec = when (val type = entry.type.trim().lowercase()) {
            "mp4a" -> "audio/mp4a-latm"
            "alac" -> "audio/alac"
            ".mp3", "mp3" -> "audio/mpeg"
            "ac-3", "ec-3" -> "audio/ac3"
            else -> "audio/$type"
        }

        if (codec == "audio/alac") {
            readAlacConfig(input, entry.dataStart + AUDIO_SAMPLE_ENTRY_BYTES, entry.end)?.let { config ->
                config.bitDepth?.let { bitDepth = it }
                config.channels?.let { channels = it }
                config.sampleRate?.let { sampleRate = it }
            }
        }

        val duration = if (timescale > 0) safeScaledDuration(units, 1_000L, timescale) else null
        return Result(codec, sampleRate, bitDepth, channels, duration)
    }

    private data class AlacConfig(val sampleRate: Int?, val bitDepth: Int?, val channels: Int?)

    /** ALACSpecificConfig, which holds the true depth and rate for an ALAC track. */
    private fun readAlacConfig(input: RandomAccessFile, start: Long, limit: Long): AlacConfig? {
        val box = findAtom(input, start, limit, "alac") ?: return null
        if (box.dataStart + 28 > box.end) return null
        input.seek(box.dataStart + 4) // version + flags
        input.skipBytes(4) // frameLength
        input.skipBytes(1) // compatibleVersion
        val bitDepth = input.readUnsignedByte().validBitDepth()
        input.skipBytes(3) // pb, mb, kb
        val channels = input.readUnsignedByte().validChannels()
        input.skipBytes(2) // maxRun
        input.skipBytes(8) // maxFrameBytes + avgBitRate
        val sampleRate = input.readUInt32BE().validSampleRate()
        return AlacConfig(sampleRate, bitDepth, channels)
    }

    private fun readAtomHeader(input: RandomAccessFile, position: Long, limit: Long): Atom? {
        if (position < 0 || position + 8 > limit) return null
        input.seek(position)
        var size = input.readUInt32BE()
        val type = input.readAscii(4)
        var dataStart = position + 8
        when {
            // 1 means the real size is a 64-bit value that follows the header.
            size == 1L -> {
                if (dataStart + 8 > limit) return null
                size = input.readUInt64BE()
                dataStart += 8
                if (size < 16) return null
            }
            // 0 means "to the end of the enclosing box".
            size == 0L -> size = limit - position
            size < 8L -> return null
        }
        val end = position + size
        if (end <= position || end > limit || dataStart > end) return null
        return Atom(type, dataStart, end)
    }

    private fun findAtom(input: RandomAccessFile, start: Long, limit: Long, type: String): Atom? {
        var cursor = start
        var guard = 0
        while (cursor + 8 <= limit && guard++ < MAX_ATOMS) {
            val atom = readAtomHeader(input, cursor, limit) ?: return null
            if (atom.type == type) return atom
            cursor = atom.end
        }
        return null
    }

    private fun findPath(input: RandomAccessFile, start: Long, limit: Long, vararg path: String): Atom? {
        var from = start
        var to = limit
        var found: Atom? = null
        for (type in path) {
            found = findAtom(input, from, to, type) ?: return null
            from = found.dataStart
            to = found.end
        }
        return found
    }

    // ---------------------------------------------------------------- MPEG audio

    /**
     * MP3 / MP2. Reads the first valid frame header after any ID3v2 tag.
     *
     * Sample rate is not otherwise obtainable on this platform: API 19's
     * `MediaMetadataRetriever` has no sample-rate key (added in API 31), so
     * without this the most common format in any library displayed no
     * resolution at all. Bit depth is deliberately absent — the format is lossy
     * and has none.
     */
    private fun readMpegAudio(file: File): Result? = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        var position = skipId3v2(input, length)
        val scanLimit = minOf(length, position + FRAME_SCAN_BYTES)
        while (position + 4 <= scanLimit) {
            input.seek(position)
            val first = input.readUnsignedByte()
            if (first != 0xFF) {
                position += 1
                continue
            }
            val second = input.readUnsignedByte()
            if (second and 0xE0 != 0xE0) {
                position += 1
                continue
            }
            val versionBits = (second shr 3) and 0x03
            val layerBits = (second shr 1) and 0x03
            if (versionBits == 1 || layerBits == 0) {
                position += 1
                continue
            }
            val third = input.readUnsignedByte()
            val bitrateIndex = (third shr 4) and 0x0F
            val rateIndex = (third shr 2) and 0x03
            if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) {
                position += 1
                continue
            }
            val fourth = input.readUnsignedByte()
            val rates = when (versionBits) {
                3 -> MPEG1_RATES
                2 -> MPEG2_RATES
                else -> MPEG25_RATES
            }
            val sampleRate = rates.getOrNull(rateIndex)?.takeIf { it > 0 }
            val channels = if ((fourth shr 6) and 0x03 == 3) 1 else 2
            // Layer II and Layer III are distinguished so the UI can label them
            // differently; both are carried in the same container.
            val codec = if (layerBits == 2) "audio/mp2" else "audio/mpeg"
            return Result(codec, sampleRate, null, channels, null)
        }
        null
    }

    /** ADTS-framed AAC. */
    private fun readAdts(file: File): Result? = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        var position = skipId3v2(input, length)
        val scanLimit = minOf(length, position + FRAME_SCAN_BYTES)
        while (position + 4 <= scanLimit) {
            input.seek(position)
            val first = input.readUnsignedByte()
            val second = input.readUnsignedByte()
            // 12-bit sync word, then a layer field that must be zero for ADTS.
            if (first != 0xFF || (second and 0xF6) != 0xF0) {
                position += 1
                continue
            }
            val third = input.readUnsignedByte()
            val fourth = input.readUnsignedByte()
            val rateIndex = (third shr 2) and 0x0F
            val sampleRate = ADTS_RATES.getOrNull(rateIndex)?.takeIf { it > 0 }
            val channelConfig = ((third and 0x01) shl 2) or ((fourth shr 6) and 0x03)
            val channels = ADTS_CHANNELS.getOrNull(channelConfig)?.takeIf { it > 0 }
            if (sampleRate == null) {
                position += 1
                continue
            }
            return Result("audio/aac", sampleRate, null, channels, null)
        }
        null
    }

    /**
     * Total length of an ID3v2 tag at the start of the file, or 0.
     *
     * MP3 and AAC files routinely begin with one, and a tag can contain byte
     * pairs that look like a frame sync — so the scan must start past it.
     */
    private fun skipId3v2(input: RandomAccessFile, length: Long): Long {
        if (length < 10) return 0
        input.seek(0)
        if (input.readAscii(3) != "ID3") return 0
        input.skipBytes(2) // version
        val flags = input.readUnsignedByte()
        // Syncsafe integer: seven bits per byte.
        var size = 0L
        repeat(4) { size = (size shl 7) or (input.readUnsignedByte() and 0x7F).toLong() }
        val footer = if (flags and 0x10 != 0) 10L else 0L
        val total = 10L + size + footer
        return if (total in 0..length) total else 0
    }

    // ---------------------------------------------------------------- Ogg family

    /** Ogg: Vorbis and Opus identification headers live in the first page. */
    private fun readOgg(file: File): Result? = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        if (length < 32) return null
        input.seek(0)
        if (input.readAscii(4) != "OggS") return null
        input.skipBytes(1) // stream structure version
        input.skipBytes(1) // header type
        input.skipBytes(8) // granule position
        input.skipBytes(4) // serial
        input.skipBytes(4) // sequence
        input.skipBytes(4) // checksum
        val segments = input.readUnsignedByte()
        if (segments <= 0) return null
        input.skipBytes(segments) // segment table
        val packet = input.filePointer
        if (packet + 16 > length) return null

        input.seek(packet)
        val magic = input.readAscii(8)
        return when {
            magic == "OpusHead" -> {
                input.skipBytes(1) // version
                val channels = input.readUnsignedByte().validChannels()
                input.skipBytes(2) // pre-skip
                // Opus always decodes at 48 kHz; this records the original rate.
                val sampleRate = input.readUInt32LE().validSampleRate()
                Result("audio/opus", sampleRate, null, channels, null)
            }
            magic.startsWith("\u0001vorbis") -> {
                input.seek(packet + 7)
                input.skipBytes(4) // vorbis version
                val channels = input.readUnsignedByte().validChannels()
                val sampleRate = input.readUInt32LE().validSampleRate()
                Result("audio/vorbis", sampleRate, null, channels, null)
            }
            // FLAC-in-Ogg: 0x7F "FLAC", then a native STREAMINFO block.
            magic.startsWith("\u007FFLAC") -> Result("audio/flac", null, null, null, null)
            else -> null
        }
    }

    // ---------------------------------------------------------------------- AMR

    /** AMR narrow- and wide-band, which have fixed rates and are always mono. */
    private fun readAmr(file: File): Result? = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 6) return null
        input.seek(0)
        val header = input.readAscii(minOf(9L, input.length()).toInt())
        return when {
            header.startsWith("#!AMR-WB\n") -> Result("audio/amr-wb", 16_000, null, 1, null)
            header.startsWith("#!AMR\n") -> Result("audio/amr", 8_000, null, 1, null)
            else -> null
        }
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

        /** Bounds the atom walk so a malformed file cannot spin. */
        private const val MAX_ATOMS = 512

        /** SampleEntry (8) + AudioSampleEntry (20) before any child box. */
        private const val AUDIO_SAMPLE_ENTRY_BYTES = 28L

        /**
         * How far past any ID3v2 tag to look for a frame sync. A valid stream
         * starts immediately; this only tolerates a little junk or padding.
         */
        private const val FRAME_SCAN_BYTES = 64L * 1024L

        private val MPEG1_RATES = intArrayOf(44_100, 48_000, 32_000)
        private val MPEG2_RATES = intArrayOf(22_050, 24_000, 16_000)
        private val MPEG25_RATES = intArrayOf(11_025, 12_000, 8_000)
        private val ADTS_RATES = intArrayOf(
            96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000,
            22_050, 16_000, 12_000, 11_025, 8_000, 7_350
        )
        /** Indexed by ADTS channel configuration; 0 means "described elsewhere". */
        private val ADTS_CHANNELS = intArrayOf(0, 1, 2, 3, 4, 5, 6, 8)
        private val WAVPACK_RATES = intArrayOf(
            6_000, 8_000, 9_600, 11_025, 12_000, 16_000, 22_050, 24_000,
            32_000, 44_100, 48_000, 64_000, 88_200, 96_000, 192_000, 0
        )
    }
}
