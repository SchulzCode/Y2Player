package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryIndex
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenContentTest {
    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

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

    private fun albumTrack(position: Int, title: String, number: Int?): Track = track.copy(
        id = position.toLong(),
        absolutePath = "/storage/sdcard0/Music/Meteora/%02d. %s.flac".format(position, title),
        relativePath = "Music/Meteora/%02d. %s.flac".format(position, title),
        title = title,
        album = "Meteora",
        trackNumber = number,
        discNumber = null
    )

    // These tests are about track order, so the leading Shuffle row is not part of the answer.
    private fun albumRowTitles(tracks: List<Track>): List<String> = ScreenContent.rows(
        AppState(
            screenStack = listOf(ScreenEntry(Screen.AlbumSongs("Meteora"))),
            library = LibraryState(tracks = tracks)
        )
    ).filterIsInstance<ScreenRow.TrackRow>().map { it.title }

    @Test fun partiallyNumberedAlbumFallsBackToFilenameOrder() {
        val tracks = listOf(
            albumTrack(1, "Foreword", null),
            albumTrack(2, "Don't Stay", 2),
            albumTrack(3, "Somewhere I Belong", null),
            albumTrack(4, "Lying from You", 4),
            albumTrack(5, "Hit the Floor", null),
            albumTrack(6, "Easier to Run", 6)
        )
        assertEquals(
            listOf("Foreword", "Don't Stay", "Somewhere I Belong", "Lying from You", "Hit the Floor", "Easier to Run"),
            albumRowTitles(tracks.shuffled())
        )
    }

    @Test fun untaggedAlbumUsesFilenameOrderNotTitleOrder() {
        val tracks = listOf(
            albumTrack(1, "Zebra", null),
            albumTrack(2, "Apple", null),
            albumTrack(3, "Mango", null)
        )
        assertEquals(listOf("Zebra", "Apple", "Mango"), albumRowTitles(tracks.shuffled()))
    }

    @Test fun fullyNumberedAlbumStillUsesTrackNumbers() {
        val tracks = listOf(
            albumTrack(1, "Third", 3),
            albumTrack(2, "First", 1),
            albumTrack(3, "Second", 2)
        )
        assertEquals(listOf("First", "Second", "Third"), albumRowTitles(tracks))
    }

    @Test fun filenameOrderTreatsEmbeddedNumbersAsNumbers() {
        val tracks = (1..12).map { albumTrack(it, "Track $it", null) }
        assertEquals(tracks.map { it.title }, albumRowTitles(tracks.shuffled()))
    }

    @Test fun folderRowsFollowAlbumOrderRatherThanTitle() {
        val tracks = listOf(
            albumTrack(1, "Zebra", null),
            albumTrack(2, "Apple", null),
            albumTrack(10, "Mango", null)
        )
        val rows = ScreenContent.rows(
            AppState(
                screenStack = listOf(ScreenEntry(Screen.Folders("internal", "Music/Meteora"))),
                library = LibraryState(tracks = tracks.shuffled())
            )
        )
        assertEquals(listOf("Zebra", "Apple", "Mango"), rows.map { it.title })
    }

    @Test fun naturalOrderComparesDigitRunsByValue() {
        assertTrue(NaturalOrder.compare("2. b.flac", "10. a.flac") < 0)
        assertTrue(NaturalOrder.compare("10. a.flac", "9. z.flac") > 0)
        assertEquals(0, NaturalOrder.compare("007. x.flac", "7. x.flac"))
        assertEquals(0, NaturalOrder.compare("A. x.flac", "a. x.flac"))
        assertTrue(NaturalOrder.compare("01 a.flac", "01 b.flac") < 0)
        assertTrue("a prefix sorts before a longer name", NaturalOrder.compare("a.flac", "a.flac.bak") < 0)
        assertEquals(0, NaturalOrder.compare("", ""))
    }

    @Test fun queueRowsKeepIndexAlignmentForMissingTracks() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Queue, selectedIndex = 1)),
            library = LibraryState(tracks = listOf(track)),
            playback = PlaybackSnapshot(queue = testQueue(99L, 1L), currentQueueEntryId = 2L)
        )
        val rows = ScreenContent.rows(state)
        assertEquals(2, rows.size)
        assertTrue("missing id renders as a placeholder, not dropped", rows[0] is ScreenRow.Group)
        assertTrue(rows[1] is ScreenRow.TrackRow)
        val effect = AppReducer.reduce(state, AppAction.Confirm).effects.single()
        assertEquals(AppEffect.PlayQueueEntry(2L), effect)
    }

    @Test fun recentlyPlayedBumpReusesCachedTrackRows() {
        val library = LibraryState(tracks = listOf(track))
        val first = AppState(screenStack = listOf(ScreenEntry(Screen.Songs)), library = library)
        val rows = ScreenContent.rows(first)
        val second = first.copy(
            library = library.copy(revision = library.revision + 1, recentlyPlayedIds = listOf(1L))
        )
        assertSame(rows, ScreenContent.rows(second))
    }

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
