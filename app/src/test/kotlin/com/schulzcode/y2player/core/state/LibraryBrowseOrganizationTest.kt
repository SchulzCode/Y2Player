package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AlbumSortOrder
import com.schulzcode.y2player.core.model.LibraryScope
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.core.model.YearSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryBrowseOrganizationTest {
    @Before fun clearRows() = ScreenContent.clearCachedRows()

    private fun track(
        id: Long,
        title: String,
        artist: String,
        album: String,
        year: Int?,
        genre: String?
    ) = Track(
        id = id,
        volumeId = "sdcard",
        absolutePath = "/Music/$artist/$album/$id.flac",
        relativePath = "Music/$artist/$album/$id.flac",
        title = title,
        artist = artist,
        album = album,
        albumArtist = artist,
        trackNumber = 1,
        discNumber = 1,
        durationMs = 60_000,
        fileSize = 100,
        modifiedAt = id,
        year = year,
        genre = genre
    )

    private val tracks = listOf(
        track(1, "Airbag", "Radiohead", "OK Computer", 1997, "Alternative; Rock"),
        track(2, "Smells Like Teen Spirit", "Nirvana", "Nevermind", 1991, "Rock"),
        track(3, "Everything in Its Right Place", "Radiohead", "Kid A", 2000, "Electronic"),
        track(4, "Demo", "Unknown", "Demos", null, null)
    )

    private fun state(screen: Screen, preferences: PlayerPreferencesState = PlayerPreferencesState()) = AppState(
        screenStack = listOf(ScreenEntry(screen)),
        library = LibraryState(tracks = tracks),
        preferences = preferences
    )

    private fun select(state: AppState, title: String): AppState {
        val index = ScreenContent.rows(state).indexOfFirst { it.title == title }
        require(index >= 0) { "Missing $title on ${state.currentScreen}" }
        return state.copy(screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index))
    }

    @Test fun `genre exposes all tracks artists and albums as requested`() {
        val genres = state(Screen.Genres)
        val rockMenu = AppReducer.reduce(select(genres, "Rock"), AppAction.Confirm).state
        assertEquals(Screen.FacetMenu(LibraryScope.Genre("rock", "Rock")), rockMenu.currentScreen)
        assertEquals(listOf("All Tracks", "Artists", "Albums"), ScreenContent.rows(rockMenu).map(ScreenRow::title))

        val allTracks = AppReducer.reduce(select(rockMenu, "All Tracks"), AppAction.Confirm).state
        assertEquals(listOf("Airbag", "Smells Like Teen Spirit"),
            ScreenContent.rows(allTracks).filterIsInstance<ScreenRow.TrackRow>().map { it.track.title })

        val artists = AppReducer.reduce(select(rockMenu, "Artists"), AppAction.Confirm).state
        assertEquals(listOf("Nirvana", "Radiohead"), ScreenContent.rows(artists).map(ScreenRow::title))
        val albums = AppReducer.reduce(select(rockMenu, "Albums"), AppAction.Confirm).state
        assertEquals(listOf("Nevermind", "OK Computer"), ScreenContent.rows(albums).map(ScreenRow::title))
    }

    @Test fun `year artist album navigation retains its year filter`() {
        val years = state(Screen.Years)
        val yearMenu = AppReducer.reduce(select(years, "1997"), AppAction.Confirm).state
        val artists = AppReducer.reduce(select(yearMenu, "Artists"), AppAction.Confirm).state
        val radiohead = AppReducer.reduce(select(artists, "Radiohead"), AppAction.Confirm).state
        assertEquals(Screen.FacetArtistAlbums(LibraryScope.Year(1997), "Radiohead"), radiohead.currentScreen)
        assertEquals(listOf("All Tracks", "OK Computer"), ScreenContent.rows(radiohead).map(ScreenRow::title))

        val album = AppReducer.reduce(select(radiohead, "OK Computer"), AppAction.Confirm).state
        assertTrue(album.currentScreen is Screen.FacetTracks)
        assertEquals(listOf("Airbag"), ScreenContent.rows(album).filterIsInstance<ScreenRow.TrackRow>().map { it.track.title })
    }

    @Test fun `album rows show years and obey the shared album ordering preference`() {
        val alphabetical = state(Screen.Albums)
        assertEquals(listOf("Demos", "Kid A", "Nevermind", "OK Computer"), ScreenContent.rows(alphabetical).map(ScreenRow::title))
        assertEquals("1997 · Radiohead", ScreenContent.rows(alphabetical).single { it.title == "OK Computer" }.subtitle)
        assertEquals("Year unknown · Unknown", ScreenContent.rows(alphabetical).single { it.title == "Demos" }.subtitle)

        val chronological = state(
            Screen.Albums,
            PlayerPreferencesState(albumSortOrder = AlbumSortOrder.YEAR_ASCENDING)
        )
        assertEquals(listOf("Nevermind", "OK Computer", "Kid A", "Demos"),
            ScreenContent.rows(chronological).map(ScreenRow::title))
    }

    @Test fun `unknown years remain last when reversing the year list`() {
        val newest = state(Screen.Years)
        assertEquals(listOf("2000", "1997", "1991", "Unknown Year"), ScreenContent.rows(newest).map(ScreenRow::title))
        val oldest = state(Screen.Years, PlayerPreferencesState(yearSortOrder = YearSortOrder.OLDEST_FIRST))
        assertEquals(listOf("1991", "1997", "2000", "Unknown Year"), ScreenContent.rows(oldest).map(ScreenRow::title))
    }

    @Test fun `sorting settings emit typed effects and return to the sorting overview`() {
        val overview = state(Screen.SortOrder)
        assertEquals(listOf("Tracks", "Albums", "Year Lists"), ScreenContent.rows(overview).map(ScreenRow::title))
        val albums = AppReducer.reduce(select(overview, "Albums"), AppAction.Confirm).state
        val chosen = AppReducer.reduce(select(albums, "Year · oldest first"), AppAction.Confirm)
        assertEquals(Screen.SortOrder, chosen.state.currentScreen)
        assertEquals(AppEffect.SetAlbumSortOrder(AlbumSortOrder.YEAR_ASCENDING), chosen.effects.single())
    }
}
