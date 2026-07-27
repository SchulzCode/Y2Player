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
     * Name on disk.
     *
     * Album files are conventionally named with their position — `01. Foreword.flac`
     * — which makes this the best available recovery of the intended order when the
     * tags do not carry a track number.
     */
    val fileName: String get() = relativePath.substringAfterLast('/')

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
 * Whether the pinned FFmpeg build can decode a track.
 *
 * The verdict is taken from the **codec**, not the file extension, because the
 * two disagree in the case that matters: an ALAC and an AAC file are both
 * `.m4a`. `AudioHeaderParser` reads the real codec out of the container, so this
 * can answer honestly where an extension list could not.
 *
 * ### What changed with the FFmpeg migration
 *
 * This used to describe what AOSP shipped at API 19 - "ALAC arrived in API 31,
 * Opus in API 21". That stopped being the constraint the moment playback left
 * MediaPlayer: nothing in the playback path consults the platform any more. The
 * real constraint is the `--enable-decoder` / `--enable-demuxer` allowlist in
 * `tools/native/build-ffmpeg.sh`, and the two had already drifted: `aiff` was
 * reported unsupported while its big-endian PCM decoders were being built, only
 * the demuxer was missing.
 *
 * So [UNSUPPORTED] no longer means "this device cannot". It means "this build
 * carries no decoder for it" - a decision, reversible by one token on the
 * configure line.
 *
 * [DECODERS] and [DEMUXERS] mirror that configure line and are checked against
 * it by `FfmpegBuildCapabilitiesTest`, so they cannot drift again in silence.
 *
 * Still three-valued: [UNKNOWN] covers a codec this build has no opinion about,
 * where either answer would be a guess. The label stays advisory - nothing here
 * prevents an attempt.
 */
enum class CodecSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

object AudioCodecSupport {

    /** Mirrors `--enable-decoder` in tools/native/build-ffmpeg.sh. */
    val DECODERS: Set<String> = setOf(
        "aac", "alac", "flac", "mp3", "opus", "vorbis",
        "pcm_f32le", "pcm_f64le", "pcm_s8", "pcm_s16be", "pcm_s16le",
        "pcm_s24be", "pcm_s24le", "pcm_s32be", "pcm_s32le", "pcm_u8"
    )

    /**
     * Mirrors `--enable-demuxer`.
     *
     * A decoder is only reachable through a demuxer, so this is half of the
     * capability answer and was previously not modelled at all.
     */
    val DEMUXERS: Set<String> = setOf("aac", "aiff", "flac", "mov", "mp3", "ogg", "wav")

    /** Stored codec identifiers that map onto an enabled decoder. */
    private val SUPPORTED_CODECS = setOf(
        "mpeg", "mp3",
        "mp4a-latm", "aac",
        "alac",
        "flac",
        "vorbis", "opus",
        "pcm", "wav", "wave",
        "aiff", "aiff-c"
    )

    /** Stored codec identifiers this build deliberately carries no decoder for. */
    private val UNSUPPORTED_CODECS = setOf(
        "amr", "amr-wb",
        "mp2",
        "wma", "ms-wma",
        "ape",
        "wavpack",
        "dsf", "dff", "dsd",
        "ac3", "eac3"
    )

    /**
     * Container extensions, consulted only when the stored codec is unknown.
     *
     * Kept apart from the codec sets on purpose: `m4a` and `mp4` are containers,
     * not codecs, and listing them among codecs is what let a container name
     * stand in for a decoding verdict.
     */
    /**
     * Container extensions this build can play, used only when the stored codec
     * is unknown.
     *
     * `internal` so `FfmpegBuildCapabilitiesTest` can assert the invariant that
     * matters: anything labelled playable here must also be indexed by
     * `LibraryScanner`, or the label describes a file the user can never see.
     *
     * `mp4` is absent on purpose — it is a video container, and the scanner does
     * not index it. Audio in an MP4 wrapper arrives as `m4a`/`m4r`.
     */
    internal val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "m4a", "m4r", "alac", "aac", "flac",
        "wav", "wave", "ogg", "oga", "opus", "aif", "aiff", "aifc"
    )

    private val UNSUPPORTED_EXTENSIONS = setOf(
        "amr", "mp2", "wma", "ape", "wv",
        "dsf", "dff", "ac3", "mka", "mkv"
    )

    fun of(codec: String?, extension: String): CodecSupport {
        val cleanedCodec = codec?.trim()
            ?.lowercase()
            ?.substringAfter('/')
            ?.removePrefix("x-")
            ?.takeIf { it.isNotEmpty() }
        if (cleanedCodec != null) {
            return when (cleanedCodec) {
                in UNSUPPORTED_CODECS -> CodecSupport.UNSUPPORTED
                in SUPPORTED_CODECS -> CodecSupport.SUPPORTED
                else -> CodecSupport.UNKNOWN
            }
        }
        return when (extension.trim().lowercase()) {
            in UNSUPPORTED_EXTENSIONS -> CodecSupport.UNSUPPORTED
            in SUPPORTED_EXTENSIONS -> CodecSupport.SUPPORTED
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
