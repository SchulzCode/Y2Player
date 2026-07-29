package com.schulzcode.y2player.library

import com.schulzcode.y2player.core.model.TrackDraft
import com.schulzcode.y2player.playback.NativeAudio
import com.schulzcode.y2player.storage.StorageRoot
import java.io.File

/** One metadata-only FFmpeg result. Numeric sentinels avoid boxed JNI values. */
class FfmpegMetadata internal constructor(
    val success: Boolean = false,
    val errorCategory: Int = 0,
    val errorDetail: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val composer: String? = null,
    val genre: String? = null,
    val date: String? = null,
    val comment: String? = null,
    val trackNumber: Int = 0,
    val trackTotal: Int = 0,
    val discNumber: Int = 0,
    val discTotal: Int = 0,
    val year: Int = 0,
    val durationMs: Long = 0,
    val codec: String? = null,
    val container: String? = null,
    val bitrate: Long = 0,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val replayGainTrackGain: Int = Int.MIN_VALUE,
    val replayGainTrackPeak: Long = 0,
    val replayGainAlbumGain: Int = Int.MIN_VALUE,
    val replayGainAlbumPeak: Long = 0,
    val bytesRead: Long = 0,
    val hasArtwork: Boolean = false
)

/**
 * The library's single metadata parser. FFmpeg inspects the container without
 * opening a decoder; one JNI crossing returns every scan field.
 */
class MetadataReader(
    private val extract: (String) -> FfmpegMetadata = { NativeAudio.nativeReadMetadata(it) }
) {
    fun read(
        root: StorageRoot,
        file: File,
        fileSize: Long = file.length(),
        modifiedAt: Long = file.lastModified(),
        profiler: ScanProfiler? = null
    ): TrackDraft {
        val nativeStarted = profiler?.start() ?: 0L
        val metadata = try {
            extract(file.absolutePath)
        } finally {
            profiler?.stop(ScanPhase.METADATA_NATIVE, nativeStarted)
        }
        val mappingStarted = profiler?.start() ?: 0L
        return try {
            val failure = metadata.errorDetail?.trim()?.takeIf { it.isNotEmpty() }
            TrackDraft(
                volumeId = root.id,
                absolutePath = file.absolutePath,
                relativePath = runCatching { file.relativeTo(root.directory).path.replace('\\', '/') }
                    .getOrElse { file.name },
                title = metadata.title.clean() ?: file.nameWithoutExtension,
                artist = metadata.artist.clean(),
                album = metadata.album.clean(),
                albumArtist = metadata.albumArtist.clean(),
                trackNumber = metadata.trackNumber.positiveOrNull(),
                discNumber = metadata.discNumber.positiveOrNull(),
                durationMs = metadata.durationMs.coerceAtLeast(0L),
                fileSize = fileSize,
                modifiedAt = modifiedAt,
                codec = metadata.codec.clean() ?: file.extension.lowercase(),
                container = metadata.container.clean(),
                sampleRate = metadata.sampleRate.positiveOrNull(),
                bitDepth = metadata.bitDepth.positiveOrNull(),
                channels = metadata.channels.positiveOrNull(),
                trackTotal = metadata.trackTotal.positiveOrNull(),
                discTotal = metadata.discTotal.positiveOrNull(),
                comment = metadata.comment.clean(),
                composer = metadata.composer.clean(),
                genre = metadata.genre.clean(),
                date = metadata.date.clean(),
                year = metadata.year.positiveOrNull(),
                bitrate = metadata.bitrate.takeIf { it > 0L },
                hasArtwork = metadata.hasArtwork,
                replayGainTrackDb = metadata.replayGainTrackGain.gainDbOrNull(),
                replayGainTrackPeak = metadata.replayGainTrackPeak.peakOrNull(),
                replayGainAlbumDb = metadata.replayGainAlbumGain.gainDbOrNull(),
                replayGainAlbumPeak = metadata.replayGainAlbumPeak.peakOrNull(),
                metadataBytesRead = metadata.bytesRead.coerceAtLeast(0L),
                scanError = if (metadata.success) null else failure ?: "FFmpeg could not read metadata",
                playbackError = if (!metadata.success && metadata.errorCategory in MEDIA_FAILURES) {
                    failure ?: "FFmpeg could not read this audio file"
                } else null
            )
        } finally {
            profiler?.stop(ScanPhase.METADATA_MAPPING, mappingStarted)
        }
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    private fun Int.positiveOrNull(): Int? = takeIf { it > 0 }
    private fun Int.gainDbOrNull(): Float? = takeUnless { it == Int.MIN_VALUE }?.div(REPLAY_GAIN_SCALE)
    private fun Long.peakOrNull(): Float? = takeIf { it > 0L }?.div(REPLAY_GAIN_SCALE)

    companion object {
        private const val REPLAY_GAIN_SCALE = 100_000f
        private val MEDIA_FAILURES = setOf(2, 3) // NativeErrorCategory.UNSUPPORTED/CORRUPT
    }
}
