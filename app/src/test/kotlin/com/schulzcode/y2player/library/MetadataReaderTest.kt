package com.schulzcode.y2player.library

import com.schulzcode.y2player.storage.StorageRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MetadataReaderTest {
    @Test fun mapsTheCompleteFfmpegRecordInOnePass() {
        val root = File(System.getProperty("java.io.tmpdir"), "y2-metadata-root")
        val file = File(root, "Album/03 Song.opus")
        val reader = MetadataReader {
            FfmpegMetadata(
                success = true,
                title = " Song ",
                artist = "Artist",
                album = "Album",
                albumArtist = "Album Artist",
                composer = "Composer",
                genre = "Electronic",
                date = "2026-07-28",
                comment = "Fixture comment",
                trackNumber = 3,
                trackTotal = 12,
                discNumber = 2,
                discTotal = 3,
                year = 2026,
                durationMs = 123_456,
                codec = "opus",
                container = "ogg",
                bitrate = 192_000,
                sampleRate = 48_000,
                bitDepth = 24,
                channels = 2,
                replayGainTrackGain = -650_000,
                replayGainTrackPeak = 98_000,
                replayGainAlbumGain = -720_000,
                replayGainAlbumPeak = 99_000,
                bytesRead = 12_345,
                hasArtwork = true
            )
        }

        val draft = reader.read(StorageRoot("sd", root), file, fileSize = 42, modifiedAt = 99)

        assertEquals("Song", draft.title)
        assertEquals("Artist", draft.artist)
        assertEquals("Album", draft.album)
        assertEquals("Album Artist", draft.albumArtist)
        assertEquals("Composer", draft.composer)
        assertEquals("Electronic", draft.genre)
        assertEquals("2026-07-28", draft.date)
        assertEquals(2026, draft.year)
        assertEquals(3, draft.trackNumber)
        assertEquals(12, draft.trackTotal)
        assertEquals(2, draft.discNumber)
        assertEquals(3, draft.discTotal)
        assertEquals("Fixture comment", draft.comment)
        assertEquals(123_456L, draft.durationMs)
        assertEquals("opus", draft.codec)
        assertEquals("ogg", draft.container)
        assertEquals(192_000L, draft.bitrate)
        assertEquals(48_000, draft.sampleRate)
        assertEquals(24, draft.bitDepth)
        assertEquals(2, draft.channels)
        assertEquals(-6.5f, draft.replayGainTrackDb!!, 0.0001f)
        assertEquals(0.98f, draft.replayGainTrackPeak!!, 0.0001f)
        assertEquals(-7.2f, draft.replayGainAlbumDb!!, 0.0001f)
        assertEquals(0.99f, draft.replayGainAlbumPeak!!, 0.0001f)
        assertTrue(draft.hasArtwork)
        assertEquals(12_345L, draft.metadataBytesRead)
        assertNull(draft.scanError)
        assertNull(draft.playbackError)
    }

    @Test fun aCorruptContainerKeepsFilenameFallbackAndFailureVerdict() {
        val root = File(System.getProperty("java.io.tmpdir"), "y2-metadata-root")
        val file = File(root, "broken.flac")
        val draft = MetadataReader {
            FfmpegMetadata(
                errorCategory = 3,
                errorDetail = "open metadata source: invalid data"
            )
        }.read(StorageRoot("internal", root), file, 100, 200)

        assertFalse(draft.hasArtwork)
        assertEquals("broken", draft.title)
        assertEquals("flac", draft.codec)
        assertEquals("open metadata source: invalid data", draft.scanError)
        assertEquals(draft.scanError, draft.playbackError)
    }
}
