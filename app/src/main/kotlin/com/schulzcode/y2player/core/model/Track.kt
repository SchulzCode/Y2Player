package com.schulzcode.y2player.core.model

data class Track(
    val id: Long,
    val volumeId: String,
    val absolutePath: String,
    val relativePath: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val fileSize: Long,
    val modifiedAt: Long,
    val available: Boolean = true,
    val scanError: String? = null,
    val codec: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val addedAt: Long = modifiedAt,
    val favorite: Boolean = false,
    /**
     * Why this device failed to decode the file, or null if it never has.
     *
     * Set only from failures the framework blamed on the media itself, never
     * from a transient fault. Cleared when the file changes on disk, and when it
     * does eventually play — so a firmware that turns out to support the codec
     * corrects the record itself.
     */
    val playbackError: String? = null
) {
    val displayArtist: String get() = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
    val displayAlbum: String get() = album?.takeIf { it.isNotBlank() } ?: "Unknown album"
    val extension: String get() = absolutePath.substringAfterLast('.', "").lowercase()

    /**
     * True when this device has already proven it cannot decode the file.
     *
     * Stronger evidence than [AudioCodecSupport], which only reasons about what
     * the platform is documented to support: this is what actually happened here.
     */
    val decodeFailed: Boolean get() = playbackError != null
}

data class TrackDraft(
    val volumeId: String,
    val absolutePath: String,
    val relativePath: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val fileSize: Long,
    val modifiedAt: Long,
    val scanError: String? = null,
    val codec: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    /**
     * Set when the scan could prove the file is not the format its name claims,
     * so the row is labelled unplayable without waiting for a failed press.
     */
    val playbackError: String? = null
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val trackCount: Int
)

/**
 * Turns stored codec identifiers (raw MIME types like "audio/mpeg" from
 * MediaMetadataRetriever or "audio/flac" from the header parser) into the names
 * a listener actually recognizes. Falls back to the uppercased file extension.
 */
object AudioCodecLabels {
    fun label(codec: String?, extension: String): String {
        val cleaned = codec?.trim()?.lowercase()?.substringAfter('/')?.removePrefix("x-")
        val fallback = extension.trim().uppercase().ifEmpty { "AUDIO" }
        return when (cleaned) {
            null, "" -> fallback
            "mpeg", "mp3" -> "MP3"
            "mp2" -> "MP2"
            "mp4", "m4a", "aac", "mp4a-latm" -> "AAC"
            // Distinguished from AAC now that the MP4 reader can see the sample
            // entry: both live in a .m4a, only one of them plays here.
            "alac" -> "ALAC"
            "flac" -> "FLAC"
            "wav", "wave" -> "WAV"
            "aiff", "aiff-c" -> "AIFF"
            "ogg", "vorbis" -> "OGG"
            "opus" -> "OPUS"
            "wavpack" -> "WavPack"
            "dsf" -> "DSF"
            "dff" -> "DFF"
            "ms-wma", "wma" -> "WMA"
            "ape" -> "APE"
            "amr" -> "AMR"
            "amr-wb" -> "AMR-WB"
            "ac3" -> "AC3"
            "matroska", "mka" -> "MKA"
            else -> if (cleaned.length in 2..8 && cleaned.all { it.isLetterOrDigit() }) cleaned.uppercase() else fallback
        }
    }
}

/**
 * Whether this device's media framework can decode a track.
 *
 * The verdict is taken from the **codec**, not the file extension, because the
 * two disagree in the case that matters: an ALAC and an AAC file are both
 * `.m4a`, and only one of them plays on API 19. `AudioHeaderParser` reads the
 * real codec out of the container, so this can answer honestly where an
 * extension list could not.
 *
 * Deliberately three-valued. [UNKNOWN] exists because MediaTek builds add codecs
 * that stock Android never had — WMA and APE most commonly — so claiming a file
 * is unplayable when the firmware would happily play it is a real risk. Only
 * codecs that AOSP definitively lacks at API 19 are reported as [UNSUPPORTED],
 * and the label is advisory: nothing here prevents an attempt.
 */
enum class CodecSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

object AudioCodecSupport {

    /** Codecs the platform decodes at API 19. */
    private val SUPPORTED = setOf(
        "mpeg", "mp3",
        "mp4a-latm", "aac", "mp4", "m4a",
        "flac",
        "wav", "wave", "pcm",
        "vorbis", "ogg",
        "amr", "amr-wb"
    )

    /**
     * Codecs AOSP cannot decode at API 19, with the reason each one is certain:
     * ALAC arrived in API 31, Opus in API 21, and the rest were never in AOSP at
     * all. DSD is doubly impossible here — the output path is 44.1 kHz/16-bit
     * PCM, so there is nothing for a decoder to hand it to.
     */
    private val UNSUPPORTED = setOf(
        "alac",
        "opus",
        "wavpack",
        "dsf", "dff", "dsd",
        "aiff", "aiff-c",
        "ac3", "eac3"
    )

    fun of(codec: String?, extension: String): CodecSupport {
        val cleaned = codec?.trim()?.lowercase()?.substringAfter('/')?.removePrefix("x-")
        // The container extension is only consulted when the codec is unknown,
        // which happens for formats without a header reader yet.
        val key = cleaned?.takeIf { it.isNotEmpty() } ?: extension.trim().lowercase()
        return when {
            key in UNSUPPORTED -> CodecSupport.UNSUPPORTED
            key in SUPPORTED -> CodecSupport.SUPPORTED
            else -> CodecSupport.UNKNOWN
        }
    }
}

enum class TrackSortOrder(val storageId: String) {
    TITLE("title"),
    ARTIST("artist"),
    ALBUM("album"),
    ADDED("added"),
    RECENT("recent");

    fun next(): TrackSortOrder = values()[(ordinal + 1) % values().size]

    companion object {
        fun fromStorage(value: String?): TrackSortOrder = values().firstOrNull {
            it.storageId == value || it.name == value
        } ?: TITLE
    }
}
