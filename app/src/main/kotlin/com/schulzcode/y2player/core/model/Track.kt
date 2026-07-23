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
    val favorite: Boolean = false
) {
    val displayArtist: String get() = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
    val displayAlbum: String get() = album?.takeIf { it.isNotBlank() } ?: "Unknown album"
    val extension: String get() = absolutePath.substringAfterLast('.', "").lowercase()
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
    val channels: Int? = null
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
            "mp4", "m4a", "aac", "mp4a-latm" -> "AAC"
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
            "ac3" -> "AC3"
            "matroska", "mka" -> "MKA"
            else -> if (cleaned.length in 2..8 && cleaned.all { it.isLetterOrDigit() }) cleaned.uppercase() else fallback
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
