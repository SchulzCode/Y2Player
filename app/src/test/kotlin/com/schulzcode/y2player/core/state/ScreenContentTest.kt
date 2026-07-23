package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryIndex
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContentTest {
    private val track = Track(
        id = 1,
        volumeId = "internal",
        absolutePath = "/storage/sdcard0/song.mp3",
        relativePath = "song.mp3",
        title = "Song",
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        trackNumber = 1,
        discNumber = 1,
        durationMs = 60_000,
        fileSize = 123,
        modifiedAt = 1
    )

    /** Queue rows must stay 1:1 with queue indices even when a queued id is unknown. */
    @Test fun queueRowsKeepIndexAlignmentForMissingTracks() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Queue, selectedIndex = 1)),
            library = LibraryState(tracks = listOf(track)),
            playback = PlaybackSnapshot(queue = listOf(99L, 1L), currentQueueIndex = 1)
        )
        val rows = ScreenContent.rows(state)
        assertEquals(2, rows.size)
        assertTrue("missing id renders as a placeholder, not dropped", rows[0] is ScreenRow.Group)
        assertTrue(rows[1] is ScreenRow.TrackRow)
        // Confirm on the selected row must address the real queue position.
        val effect = AppReducer.reduce(state, AppAction.Confirm).effects.single()
        assertEquals(AppEffect.PlayQueueIndex(1), effect)
    }

    /** Recently-played bookkeeping (revision-only bump) must not rebuild track rows. */
    @Test fun recentlyPlayedBumpReusesCachedTrackRows() {
        val library = LibraryState(tracks = listOf(track))
        val first = AppState(screenStack = listOf(ScreenEntry(Screen.Songs)), library = library)
        val rows = ScreenContent.rows(first)
        val second = first.copy(
            library = library.copy(revision = library.revision + 1, recentlyPlayedIds = listOf(1L))
        )
        assertSame(rows, ScreenContent.rows(second))
    }

    /** A real track change (new index + tracksRevision) must rebuild the rows. */
    @Test fun tracksRevisionBumpRebuildsTrackRows() {
        val library = LibraryState(tracks = listOf(track))
        val first = AppState(screenStack = listOf(ScreenEntry(Screen.Songs)), library = library)
        val rows = ScreenContent.rows(first)
        val second = first.copy(
            library = library.copy(
                revision = library.revision + 1,
                tracksRevision = library.tracksRevision + 1,
                index = LibraryIndex.of(listOf(track.copy(favorite = true)))
            )
        )
        val rebuilt = ScreenContent.rows(second)
        assertNotSame(rows, rebuilt)
        assertTrue((rebuilt.single() as ScreenRow.TrackRow).track.favorite)
    }

    @Test fun detailScreensUseCompactTypeLabelsAndStableRows() {
        val album = AppState(
            screenStack = listOf(ScreenEntry(Screen.AlbumSongs("A very long album title"))),
            library = LibraryState(tracks = listOf(track.copy(album = "A very long album title")))
        )
        assertEquals("Album", ScreenContent.title(album))
        val albumRows = ScreenContent.rows(album)
        assertEquals(1, albumRows.size)
        assertTrue(
            ScreenContent.sameRowIdentity(
                ScreenRow.TrackRow(track),
                albumRows.first()
            )
        )

        val artist = album.copy(screenStack = listOf(ScreenEntry(Screen.ArtistSongs("Artist"))))
        assertEquals("Artist", ScreenContent.title(artist))
        assertTrue(ScreenContent.rows(artist).first() is ScreenRow.TrackRow)
        assertEquals("Song", ScreenContent.rows(artist).first().title)
    }
}
