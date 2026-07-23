package com.schulzcode.y2player.diagnostics

import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatProbeControllerTest {
    @Test
    fun sampleSelectionStopsTouchingFilesAfterFindingEachExtension() {
        val tracks = (1L..100L).map { id -> track(id, if (id <= 50) "mp3" else "flac") }
        var readabilityChecks = 0

        val selected = FormatProbeController.selectSamples(tracks) {
            readabilityChecks += 1
            true
        }

        assertEquals(listOf("flac", "mp3"), selected.map { it.extension })
        assertEquals(2, readabilityChecks)
    }

    private fun track(id: Long, extension: String) = Track(
        id = id,
        volumeId = "internal",
        absolutePath = "/music/$id.$extension",
        relativePath = "$id.$extension",
        title = "$id",
        artist = null,
        album = null,
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        durationMs = 1_000,
        fileSize = 1,
        modifiedAt = 1
    )
}
