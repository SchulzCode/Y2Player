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
    val container: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val trackTotal: Int? = null,
    val discTotal: Int? = null,
    val comment: String? = null,
    val composer: String? = null,
    val genre: String? = null,
    val date: String? = null,
    val year: Int? = null,
    val bitrate: Long? = null,
    val hasArtwork: Boolean = false,
    val replayGainTrackDb: Float? = null,
    val replayGainTrackPeak: Float? = null,
    val replayGainAlbumDb: Float? = null,
    val replayGainAlbumPeak: Float? = null,
    val addedAt: Long = modifiedAt,
    val favorite: Boolean = false,
    val playbackError: String? = null
) {
    val displayArtist: String get() = artist?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown artist"
    val displayAlbum: String get() = album?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown album"

    val primaryArtist: String get() = ArtistCredit.primary(displayArtist)

    val featuredArtist: String? get() = ArtistCredit.featured(displayArtist)

    val creditedArtists: List<String> get() = ArtistCredit.names(displayArtist)

    val albumArtistName: String get() = albumArtist?.trim()?.takeIf { it.isNotEmpty() }
        ?: creditedArtists.firstOrNull()
        ?: primaryArtist

    fun isCreditedTo(name: String): Boolean =
        ArtistCredit.credits(displayArtist, name)

    val extension: String get() = absolutePath.substringAfterLast('.', "").lowercase()

    val fileName: String get() = relativePath.substringAfterLast('/')

    val decodeFailed: Boolean get() = playbackError != null

    val audiobookFolderKey: String? get() = AudiobookIdentity.folderKey(volumeId, relativePath)

    val isAudiobookChapter: Boolean get() = audiobookFolderKey != null
}

object ArtistCredit {

    // Explicit feature words split credits. FFmpeg joins repeated Vorbis ARTIST
    // values with semicolons, which are always boundaries. A comma also separates
    // simple artist lists, but remains part of established '&' and '+' band-name forms.
    private val BARE_MARKERS = arrayOf("featuring", "feat.", "feat", "ft.", "ft")

    // "with" is only a marker inside brackets, as in "(with X)". Bare it would cut
    // Sleeping with Sirens in half.
    private const val BRACKETED_MARKER = "with"

    fun primary(credit: String): String {
        val cleaned = credit.substringBefore(REPEATED_VALUE_SEPARATOR).trim()
        val at = markerStart(cleaned)
        if (at < 0) return firstCommaSeparated(cleaned)
        val head = cleaned.substring(0, at).trimEnd(' ', '(', '[', '-', '\u2013', ',')
        return firstCommaSeparated(head.ifEmpty { cleaned })
    }

    fun featured(credit: String): String? {
        val cleaned = credit.substringBefore(REPEATED_VALUE_SEPARATOR).trim()
        val at = markerStart(cleaned)
        if (at < 0) return null
        val length = markerLengthAt(cleaned, at)
        if (length <= 0) return null
        val tail = cleaned.substring(at + length).trim().trim(')', ']').trim()
        return tail.ifEmpty { null }
    }

    fun names(credit: String): List<String> {
        val result = ArrayList<String>(2)
        var start = 0
        for (index in credit.indices) {
            if (credit[index] == REPEATED_VALUE_SEPARATOR) {
                appendNames(credit.substring(start, index).trim(), result)
                start = index + 1
            }
        }
        appendNames(credit.substring(start).trim(), result)
        return result
    }

    fun credits(credit: String, name: String): Boolean =
        names(credit).any { it.equals(name.trim(), ignoreCase = true) }

    private fun appendNames(credit: String, result: MutableList<String>) {
        val at = markerStart(credit)
        if (at < 0) {
            appendCommaSeparated(credit, result)
            return
        }
        val head = credit.substring(0, at).trimEnd(' ', '(', '[', '-', '\u2013', ',')
        appendCommaSeparated(head, result)
        val length = markerLengthAt(credit, at)
        if (length <= 0) return
        val tail = credit.substring(at + length).trim().trim(')', ']').trim()
        if (tail.isNotEmpty()) appendNames(tail, result)
    }

    private fun appendCommaSeparated(value: String, result: MutableList<String>) {
        val cleaned = value.trim()
        if (cleaned.isEmpty()) return
        if (cleaned.indexOf(',') < 0 || cleaned.indexOf('&') >= 0 || cleaned.indexOf('+') >= 0) {
            appendUnique(cleaned, result)
            return
        }
        var start = 0
        for (index in cleaned.indices) {
            if (cleaned[index] == ',') {
                appendUnique(cleaned.substring(start, index).trim(), result)
                start = index + 1
            }
        }
        appendUnique(cleaned.substring(start).trim(), result)
    }

    private fun firstCommaSeparated(value: String): String {
        val comma = value.indexOf(',')
        return if (comma >= 0 && value.indexOf('&') < 0 && value.indexOf('+') < 0) {
            value.substring(0, comma).trim().ifEmpty { value }
        } else value
    }

    private fun appendUnique(name: String, result: MutableList<String>) {
        if (name.isNotEmpty() && result.none { it.equals(name, ignoreCase = true) }) result.add(name)
    }

    // Scans without allocating; the overwhelming majority of credits have no marker.
    private fun markerStart(credit: String): Int {
        for (index in credit.indices) {
            if (markerLengthAt(credit, index) > 0) return index
        }
        return -1
    }

    private fun markerLengthAt(credit: String, index: Int): Int {
        if (index == 0) return 0
        val before = credit[index - 1]
        val bracketed = before == '(' || before == '['
        if (!bracketed && before != ' ') return 0
        BARE_MARKERS.forEach { marker ->
            if (matches(credit, index, marker)) return marker.length
        }
        if (bracketed && matches(credit, index, BRACKETED_MARKER)) return BRACKETED_MARKER.length
        return 0
    }

    private fun matches(credit: String, index: Int, marker: String): Boolean {
        if (!credit.regionMatches(index, marker, 0, marker.length, ignoreCase = true)) return false
        val after = index + marker.length
        if (after >= credit.length) return false
        // A marker must be a whole word, so "ft" never matches inside "ftfoo".
        return credit[after] == ' '
    }

    private const val REPEATED_VALUE_SEPARATOR = ';'
}

object AudiobookIdentity {
    const val ROOT_SEGMENT = "AUDIOBOOKS"

    private const val KEY_SEPARATOR = '|'

    fun folderKey(volumeId: String, relativePath: String): String? {
        val segments = segmentsOf(relativePath) ?: return null
        if (segments.size < 2) return null
        val rootIndex = segments.indexOfFirst { it.equals(ROOT_SEGMENT, ignoreCase = true) }
        if (rootIndex < 0) return null
        val fileIndex = segments.lastIndex
        if (rootIndex >= fileIndex) return null
        var bookIndex = fileIndex - 1
        // Stops one folder below the marker: the book itself may be named "Part 1".
        while (bookIndex > rootIndex + 1 && isDiscFolder(segments[bookIndex])) {
            bookIndex -= 1
        }
        val endExclusive = if (bookIndex > rootIndex) bookIndex + 1 else segments.size
        return buildString {
            append(volumeId)
            append(KEY_SEPARATOR)
            for (index in 0 until endExclusive) {
                if (index > 0) append('/')
                append(segments[index])
            }
        }
    }

    private fun isDiscFolder(segment: String): Boolean =
        DISC_FOLDER.matches(segment.trim())

    // Narrow on purpose. Splitting one book loses a place; merging two loses both
    // positions. "book" is omitted because "Book 1" is a plausible title.
    private val DISC_FOLDER = Regex(
        "^(disc|disk|cd|part|pt|vol|volume|tape|side)[\\s._-]*\\d+$",
        RegexOption.IGNORE_CASE
    )

    private fun segmentsOf(relativePath: String): List<String>? {
        val segments = relativePath.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
        return if (segments.any { it == ".." }) null else segments
    }
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
    val container: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val trackTotal: Int? = null,
    val discTotal: Int? = null,
    val comment: String? = null,
    val composer: String? = null,
    val genre: String? = null,
    val date: String? = null,
    val year: Int? = null,
    val bitrate: Long? = null,
    val hasArtwork: Boolean = false,
    val replayGainTrackDb: Float? = null,
    val replayGainTrackPeak: Float? = null,
    val replayGainAlbumDb: Float? = null,
    val replayGainAlbumPeak: Float? = null,
    val metadataBytesRead: Long = 0,
    val playbackError: String? = null
)

data class AudiobookProgress(
    val folderKey: String,
    val trackId: Long,
    val positionMs: Long,
    val updatedAt: Long
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val trackCount: Int
)

object AudioCodecLabels {
    fun label(codec: String?, extension: String): String {
        val cleaned = codec?.trim()?.lowercase()?.substringAfter('/')?.removePrefix("x-")
        val fallback = extension.trim().uppercase().ifEmpty { "AUDIO" }
        return when (cleaned) {
            null, "" -> fallback
            "mpeg", "mp3" -> "MP3"
            "mp2" -> "MP2"
            "mp4", "m4a", "aac", "mp4a-latm" -> "AAC"
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

enum class CodecSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

object AudioCodecSupport {
    val DECODERS: Set<String> = setOf(
        "aac", "alac", "flac", "mp3", "opus", "vorbis",
        "pcm_f32le", "pcm_f64le", "pcm_s8", "pcm_s16be", "pcm_s16le",
        "pcm_s24be", "pcm_s24le", "pcm_s32be", "pcm_s32le", "pcm_u8"
    )

    val DEMUXERS: Set<String> = setOf("aac", "aiff", "asf", "flac", "mov", "mp3", "ogg", "wav")

    private val SUPPORTED_CODECS = setOf(
        "mpeg", "mp3",
        "mp4a-latm", "aac",
        "alac",
        "flac",
        "vorbis", "opus",
        "pcm", "wav", "wave",
        "aiff", "aiff-c"
    )

    private val UNSUPPORTED_CODECS = setOf(
        "amr", "amr-wb",
        "mp2",
        "wma", "ms-wma",
        "ape",
        "wavpack",
        "dsf", "dff", "dsd",
        "ac3", "eac3"
    )

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
    YEAR("year"),
    ADDED("added"),
    RECENT("recent");

    fun next(): TrackSortOrder = values()[(ordinal + 1) % values().size]

    companion object {
        fun fromStorage(value: String?): TrackSortOrder = values().firstOrNull {
            it.storageId == value || it.name == value
        } ?: TITLE
    }
}
