package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class MultipleArtistAlbumNavigationTest {

    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

    private val albumTracks = listOf(
        track(1, "Solo One", "Lead Artist", 1),
        track(2, "Featured Song", "Lead Artist feat. Guest Artist", 2),
        track(3, "Solo Two", "Lead Artist", 3)
    )

    @Test fun `solo and featured tracks keep one album referenced by both artists`() {
        val artists = groups(state(Screen.Artists, albumTracks)).map { it.key }
        assertEquals(listOf("Guest Artist", "Lead Artist"), artists)
        assertFalse("Lead Artist feat. Guest Artist" in artists)

        assertEquals(
            listOf("Shared Album"),
            groups(state(Screen.ArtistAlbums("Lead Artist"), albumTracks)).map { it.key }
        )
        assertEquals(
            listOf("Shared Album"),
            groups(state(Screen.ArtistAlbums("Guest Artist"), albumTracks)).map { it.key }
        )
    }

    @Test fun `a comma separated multi artist credit creates individual entries`() {
        val tracks = listOf(track(4, "Collaboration", "Lead Artist, Guest One, Guest Two", 1))
        val artists = groups(state(Screen.Artists, tracks)).map { it.key }

        assertEquals(listOf("Guest One", "Guest Two", "Lead Artist"), artists)
        assertFalse("Lead Artist, Guest One, Guest Two" in artists)
        assertEquals("Lead Artist", tracks.single().primaryArtist)
        artists.forEach { artist ->
            assertEquals(
                listOf("Shared Album"),
                groups(state(Screen.ArtistAlbums(artist), tracks)).map { it.key }
            )
        }
    }

    @Test fun `opening an album from a featured artist shows every album track`() {
        val artistAlbums = state(Screen.ArtistAlbums("Guest Artist"), albumTracks)
        val albumRow = ScreenContent.rows(artistAlbums).indexOfFirst {
            (it as? ScreenRow.Group)?.key == "Shared Album"
        }
        val selected = artistAlbums.copy(
            screenStack = listOf(artistAlbums.currentEntry.copy(selectedIndex = albumRow))
        )

        val opened = AppReducer.reduce(selected, AppAction.Confirm).state

        assertEquals(Screen.AlbumSongs("Shared Album", "Lead Artist"), opened.currentScreen)
        assertEquals(
            listOf("Solo One", "Featured Song", "Solo Two"),
            ScreenContent.rows(opened).filterIsInstance<ScreenRow.TrackRow>().map { it.track.title }
        )
    }

    private fun groups(state: AppState): List<ScreenRow.Group> =
        ScreenContent.rows(state).filterIsInstance<ScreenRow.Group>()

    private fun state(screen: Screen, tracks: List<Track>): AppState = AppState(
        screenStack = listOf(ScreenEntry(screen)),
        library = LibraryState(tracks = tracks)
    )

    private fun track(id: Long, title: String, artist: String, number: Int) = Track(
        id = id,
        volumeId = "sdcard",
        absolutePath = "/storage/sdcard1/Music/$title.mp3",
        relativePath = "Music/$title.mp3",
        title = title,
        artist = artist,
        album = "Shared Album",
        albumArtist = "Lead Artist",
        trackNumber = number,
        discNumber = 1,
        durationMs = 180_000,
        fileSize = 1,
        modifiedAt = 1
    )
}
