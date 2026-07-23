package com.schulzcode.y2player.library

import android.media.MediaMetadataRetriever
import com.schulzcode.y2player.core.model.TrackDraft
import com.schulzcode.y2player.storage.StorageRoot
import java.io.File

/**
 * Not thread-safe: the retriever instance is reused across files within one scan
 * (creating one per file costs a native service connection each, the dominant
 * per-file overhead on a 30k-file first scan). It is recreated after any failure
 * because some MediaTek builds leave a retriever unusable after a codec error, and
 * released between scans via [release].
 */
class MetadataReader(private val headerParser: AudioHeaderParser = AudioHeaderParser()) {
    private var retriever: MediaMetadataRetriever? = null

    fun read(root: StorageRoot, file: File): TrackDraft {
        val technical = headerParser.read(file)
        var retrieverError: String? = null
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null
        var durationMs: Long? = null
        var mimeType: String? = null
        val active = retriever ?: MediaMetadataRetriever().also { retriever = it }
        try {
            active.setDataSource(file.absolutePath)
            title = active.text(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = active.text(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = active.text(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            albumArtist = active.text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            trackNumber = active.text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.toIntOrNull()
            discNumber = active.text(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.toIntOrNull()
            durationMs = active.text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            mimeType = active.text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        } catch (error: RuntimeException) {
            retrieverError = error.message ?: error.javaClass.simpleName
            release()
        }
        return TrackDraft(
            volumeId = root.id,
            absolutePath = file.absolutePath,
            relativePath = runCatching { file.relativeTo(root.directory).path.replace('\\', '/') }.getOrElse { file.name },
            title = title ?: file.nameWithoutExtension,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = trackNumber,
            discNumber = discNumber,
            durationMs = durationMs?.takeIf { it > 0 } ?: technical?.durationMs ?: 0L,
            fileSize = file.length(),
            modifiedAt = file.lastModified(),
            codec = technical?.codec ?: mimeType ?: file.extension.lowercase(),
            sampleRate = technical?.sampleRate,
            bitDepth = technical?.bitDepth,
            channels = technical?.channels,
            // A header-only result is enough to index formats unsupported by MediaMetadataRetriever.
            scanError = if (technical == null) retrieverError else null
        )
    }

    /** Frees the native retriever; safe to call repeatedly, called between scans. */
    fun release() {
        retriever?.let { runCatching { it.release() } }
        retriever = null
    }

    private fun MediaMetadataRetriever.text(key: Int): String? = extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
}
