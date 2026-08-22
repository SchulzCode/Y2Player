package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AlbumSortOrder
import com.schulzcode.y2player.core.model.LibraryScope
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.core.model.TrackSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AlphabetNavigationTest {
    @Before fun clearCaches() {
        ScreenContent.clearCachedRows()
        AlphabetNavigation.resetForTests()
    }

    @Test fun scrubMovesForwardAndBackwardAndJumpsToTheFirstMatchingRow() {
        val titles = ('A'..'Z').map { "$it first" } + listOf("M second")
        var state = songsState(titles)
        state = selectTitle(state, "H first")

        val forward = AppReducer.reduce(state, AppAction.AlphabetMoved(1))
        assertTrue(forward.effects.isEmpty())
        state = forward.state
        assertEquals("I", state.alphabetScrub?.label)
        assertEquals("I first", selectedTitle(state))

        state = AppReducer.reduce(state, AppAction.AlphabetMoved(-1)).state
        assertEquals("H", state.alphabetScrub?.label)
        assertEquals("H first", selectedTitle(state))

        state = selectTitle(state.copy(alphabetScrub = null), "L first")
        state = AppReducer.reduce(state, AppAction.AlphabetMoved(1)).state
        assertEquals("M first", selectedTitle(state))
    }

    @Test fun nonAlphabeticTitlesUseTheNumberBucket() {
        var state = songsState(listOf("123", "! Alert") + ('A'..'Z').map { "$it song" })
        state = selectTitle(state, "A song")

        state = AppReducer.reduce(state, AppAction.AlphabetMoved(-1)).state

        assertEquals("#", state.alphabetScrub?.label)
        assertEquals("! Alert", selectedTitle(state))
    }

    @Test fun missingLettersRemainSafeAndTheNextPresentLetterJumps() {
        val titles = ('A'..'Z').filterNot { it == 'Q' }.map { "$it song" }
        var state = selectTitle(songsState(titles), "P song")

        state = AppReducer.reduce(state, AppAction.AlphabetMoved(1)).state
        assertEquals("Q", state.alphabetScrub?.label)
        assertEquals("P song", selectedTitle(state))

        state = AppReducer.reduce(state, AppAction.AlphabetMoved(1)).state
        assertEquals("R", state.alphabetScrub?.label)
        assertEquals("R song", selectedTitle(state))
    }

    @Test fun endingScrubKeepsTheJumpedSelectionAndDismissesTheIndicator() {
        var state = selectTitle(songsState(('A'..'Z').map { "$it song" }), "H song")
        state = AppReducer.reduce(state, AppAction.AlphabetMoved(1)).state
        val jumpedIndex = state.selectedIndex

        state = AppReducer.reduce(state, AppAction.EndAlphabetScrub).state

        assertNull(state.alphabetScrub)
        assertEquals(jumpedIndex, state.selectedIndex)
        assertEquals("I song", selectedTitle(state))
    }

    @Test fun normalWheelMovementAfterScrubClearsTheModeAndUsesRowNavigation() {
        var state = selectTitle(songsState(('A'..'Z').map { "$it song" }), "H song")
        state = AppReducer.reduce(state, AppAction.AlphabetMoved(1)).state
        val jumpedIndex = state.selectedIndex

        state = AppReducer.reduce(state, AppAction.WheelMoved(1)).state

        assertNull(state.alphabetScrub)
        assertEquals(jumpedIndex + 1, state.selectedIndex)
    }

    @Test fun eligibilityTracksTheEffectiveSortAndScreen() {
        val tracks = ('A'..'Z').mapIndexed { index, letter ->
            track(
                id = index.toLong() + 1,
                title = "$letter song",
                artist = "$letter artist",
                album = "$letter album",
                year = 2000 + index
            )
        }
        val library = LibraryState(tracks = tracks)

        assertAllowed(state(Screen.Songs, library, trackSort = TrackSortOrder.TITLE))
        assertDenied(state(Screen.Songs, library, trackSort = TrackSortOrder.ARTIST))
        val favorites = LibraryState(tracks = tracks.map { it.copy(favorite = true) })
        assertAllowed(state(Screen.Favorites, favorites, trackSort = TrackSortOrder.TITLE))
        assertDenied(state(Screen.Favorites, favorites, trackSort = TrackSortOrder.RECENT))
        assertAllowed(state(Screen.Artists, library))
        assertAllowed(state(Screen.Albums, library, albumSort = AlbumSortOrder.TITLE))
        assertDenied(state(Screen.Albums, library, albumSort = AlbumSortOrder.ARTIST))
        assertDenied(state(Screen.Albums, library, albumSort = AlbumSortOrder.YEAR_ASCENDING))
        assertAllowed(state(Screen.FacetAlbums(LibraryScope.All), library, albumSort = AlbumSortOrder.TITLE))
        val oneArtistLibrary = LibraryState(tracks = tracks.map { it.copy(artist = "One Artist", albumArtist = "One Artist") })
        assertAllowed(state(Screen.ArtistAlbums("One Artist"), oneArtistLibrary))
        assertDenied(state(
            Screen.ArtistAlbums("One Artist"),
            oneArtistLibrary,
            albumSort = AlbumSortOrder.YEAR_DESCENDING
        ))

        assertDenied(state(Screen.AlbumSongs("A album", "A artist"), library))
        assertDenied(state(Screen.RecentlyPlayed, library))
        assertDenied(state(Screen.Years, library))
        assertDenied(state(Screen.Queue, library))
        assertDenied(state(Screen.Settings, library))
        assertDenied(state(Screen.NowPlayingOptions, library))
    }

    @Test fun facetTrackScrubRequiresAVisibleTitleSortedCollection() {
        val tracks = ('A'..'Z').mapIndexed { index, letter ->
            track(index.toLong() + 1, "$letter song", "Artist", "Album", genre = "Rock")
        }
        val library = LibraryState(tracks = tracks)
        val scope = LibraryScope.Genre("rock", "Rock")

        assertAllowed(state(Screen.FacetTracks(scope, "Rock"), library, trackSort = TrackSortOrder.TITLE))
        assertDenied(state(Screen.FacetTracks(scope, "Rock"), library, trackSort = TrackSortOrder.ALBUM))
        assertDenied(state(Screen.FacetTracks(scope, "Artist", artist = "Artist"), library))
    }

    @Test fun playlistsScrubOnlyWhenTheirActualOrderIsAlphabetic() {
        val sorted = ('A'..'L').mapIndexed { index, letter ->
            PlaylistSummary(index.toLong() + 1, "$letter list", 0)
        }
        val sortedState = AppState(
            screenStack = listOf(ScreenEntry(Screen.Playlists)),
            library = LibraryState(tracks = emptyList(), playlists = sorted)
        )
        assertAllowed(sortedState)

        ScreenContent.clearCachedRows()
        AlphabetNavigation.clearCachedIndex()
        val unsortedState = sortedState.copy(
            library = LibraryState(tracks = emptyList(), playlists = sorted.reversed())
        )
        assertDenied(unsortedState)
    }

    @Test fun largeListsBuildTheLetterLookupOnlyOnceAcrossWheelEvents() {
        val tracks = ArrayList<Track>(10_000)
        var id = 1L
        for (letter in 'A'..'Z') {
            repeat(385) { number ->
                tracks += track(id++, "$letter ${number.toString().padStart(4, '0')}", "Artist", "Album")
            }
        }
        var state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Songs)),
            library = LibraryState(tracks = tracks)
        )

        assertTrue(AlphabetNavigation.allowsScrubbing(state))
        repeat(50) {
            state = AppReducer.reduce(state, AppAction.AlphabetMoved(1)).state
        }

        assertEquals(1, AlphabetNavigation.indexBuildCountForTests())
    }

    private fun assertAllowed(state: AppState) = assertTrue(
        "Expected alphabet scrub on ${state.currentScreen}",
        AlphabetNavigation.allowsScrubbing(state)
    )

    private fun assertDenied(state: AppState) = assertFalse(
        "Expected no alphabet scrub on ${state.currentScreen}",
        AlphabetNavigation.allowsScrubbing(state)
    )

    private fun songsState(titles: List<String>): AppState = AppState(
        screenStack = listOf(ScreenEntry(Screen.Songs)),
        library = LibraryState(tracks = titles.mapIndexed { index, title ->
            track(index.toLong() + 1, title, "Artist", "Album")
        })
    )

    private fun state(
        screen: Screen,
        library: LibraryState,
        trackSort: TrackSortOrder = TrackSortOrder.TITLE,
        albumSort: AlbumSortOrder = AlbumSortOrder.TITLE
    ): AppState = AppState(
        screenStack = listOf(ScreenEntry(screen)),
        library = library,
        preferences = PlayerPreferencesState(sortOrder = trackSort, albumSortOrder = albumSort)
    )

    private fun selectTitle(state: AppState, title: String): AppState {
        val index = ScreenContent.rows(state).indexOfFirst { it.title == title }
        assertTrue("Missing row $title", index >= 0)
        return state.copy(screenStack = listOf(ScreenEntry(state.currentScreen, index)))
    }

    private fun selectedTitle(state: AppState): String = ScreenContent.rows(state)[state.selectedIndex].title

    private fun track(
        id: Long,
        title: String,
        artist: String,
        album: String,
        year: Int? = null,
        genre: String? = null
    ) = Track(
        id = id,
        volumeId = "internal",
        absolutePath = "/music/$id.mp3",
        relativePath = "music/$id.mp3",
        title = title,
        artist = artist,
        album = album,
        albumArtist = artist,
        trackNumber = 1,
        discNumber = 1,
        durationMs = 60_000,
        fileSize = 1_000,
        modifiedAt = id,
        year = year,
        genre = genre
    )
}
