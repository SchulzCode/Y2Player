package com.schulzcode.y2player.ui

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.core.state.AppState
import com.schulzcode.y2player.core.state.Screen
import com.schulzcode.y2player.core.state.ScreenContent
import com.schulzcode.y2player.core.state.ScreenEntry
import com.schulzcode.y2player.core.state.ScreenRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisibleRowArtworkPlannerTest {
    @Test fun onlyVisibleRowsAreScheduled() {
        val tracks = (1L..4L).map(::track)
        val state = state(Screen.Songs, tracks)
        val rows = tracks.map(ScreenRow::TrackRow)

        val requests = VisibleRowArtworkPlanner.requests(state, rows, visibleStart = 1, visibleCount = 2)

        assertEquals(listOf(1, 2), requests.map { it.rowIndex })
        assertEquals(listOf(2L, 3L), requests.map { it.track.id })
    }

    @Test fun albumTrackNumbersArePreservedInsteadOfSchedulingThumbnails() {
        val tracks = listOf(track(1L), track(2L))
        val state = state(Screen.AlbumSongs("Album"), tracks)

        assertEquals(
            emptyList<VisibleRowArtworkRequest>(),
            VisibleRowArtworkPlanner.requests(state, tracks.map(ScreenRow::TrackRow), 0, tracks.size)
        )
    }

    @Test fun unavailableBlankAndOffscreenArtworkSourcesAreIgnored() {
        val usable = track(1L)
        val unavailable = track(2L).copy(available = false)
        val blank = track(3L).copy(absolutePath = "")
        val state = state(Screen.Songs, listOf(usable, unavailable, blank))
        val rows = listOf(
            ScreenRow.Action("Offscreen", key = "offscreen", artworkTrackId = usable.id),
            ScreenRow.Action("Unavailable", key = "unavailable", artworkTrackId = unavailable.id),
            ScreenRow.Action("Blank", key = "blank", artworkTrackId = blank.id)
        )

        assertEquals(emptyList<VisibleRowArtworkRequest>(), VisibleRowArtworkPlanner.requests(state, rows, 1, 2))
    }

    @Test fun collectionRowsExposeRepresentativeArtworkWithoutLibrarySearchesInTheView() {
        val first = track(1L)
        val second = track(2L).copy(title = "Second")
        val library = LibraryState(
            tracks = listOf(first, second),
            playlists = listOf(PlaylistSummary(9, "Mix", 2)),
            playlistTrackIds = mapOf(9L to listOf(second.id, first.id))
        )

        val albumRow = ScreenContent.rows(state(Screen.Albums, library)).first()
        val artistRow = ScreenContent.rows(state(Screen.Artists, library)).first()
        val playlistRow = ScreenContent.rows(state(Screen.Playlists, library)).first()

        assertEquals(first.id, albumRow.artworkTrackId)
        assertEquals(first.id, artistRow.artworkTrackId)
        assertEquals(second.id, playlistRow.artworkTrackId)
        assertNull(ScreenRow.Action("New Playlist", key = "playlist_create").artworkTrackId)
    }

    private fun state(screen: Screen, tracks: List<Track>): AppState =
        state(screen, LibraryState(tracks = tracks))

    private fun state(screen: Screen, library: LibraryState): AppState = AppState(
        screenStack = listOf(ScreenEntry(screen)),
        library = library
    )

    private fun track(id: Long) = Track(
        id = id,
        volumeId = "sd",
        absolutePath = "/music/$id.flac",
        relativePath = "Music/$id.flac",
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        albumArtist = "Artist",
        trackNumber = id.toInt(),
        discNumber = 1,
        durationMs = 60_000,
        fileSize = 1_024,
        modifiedAt = 100 + id,
        hasArtwork = true
    )
}
