package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MainMenuHierarchyTest {

    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

    private val track = Track(
        id = 1,
        volumeId = "sdcard",
        absolutePath = "/storage/sdcard1/Music/song.mp3",
        relativePath = "Music/song.mp3",
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

    private val library = LibraryState(tracks = listOf(track))
    private val playing = PlaybackSnapshot(
        currentTrackId = 1L,
        queue = listOf(1L),
        currentQueueIndex = 0,
        status = PlaybackStatus.PLAYING
    )

    private fun keys(state: AppState) = ScreenContent.rows(state).map { (it as ScreenRow.Action).key }

    private fun select(state: AppState, key: String): AppState {
        val index = ScreenContent.rows(state).indexOfFirst { (it as? ScreenRow.Action)?.key == key }
        require(index >= 0) { "Missing row $key on ${state.currentScreen}" }
        return state.copy(screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index))
    }

    private fun open(state: AppState, key: String) = AppReducer.reduce(select(state, key), AppAction.Confirm)

    // ---- main menu -------------------------------------------------------------

    @Test fun `the main menu fits the split layout without scrolling`() {
        // 318 px of row area at 58 dp per row leaves room for five; four keeps a margin.
        assertTrue("main menu must not exceed the split-home row budget", ScreenContent.rows(AppState()).size <= 5)
        assertEquals(4, ScreenContent.rows(AppState()).size)
    }

    @Test fun `the main menu offers Shuffle All when nothing is loaded`() {
        assertEquals(listOf("music", "audiobooks", "shuffle_all", "settings"), keys(AppState(library = library)))
    }

    @Test fun `the main menu offers Now Playing once a track is loaded`() {
        val state = AppState(library = library, playback = playing)
        assertEquals(listOf("music", "audiobooks", "now_playing", "settings"), keys(state))
    }

    @Test fun `the Now Playing row names the current track`() {
        val state = AppState(library = library, playback = playing)
        val row = ScreenContent.rows(state)[2] as ScreenRow.Action
        assertEquals("Now Playing", row.title)
        assertTrue(row.subtitle.orEmpty().contains("Song"))
    }

    @Test fun `the Now Playing row opens Now Playing`() {
        val state = AppState(library = library, playback = playing)
        val result = open(state, "now_playing")
        assertEquals(Screen.NowPlaying, result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun `Shuffle All from the main menu still shuffles`() {
        val result = open(AppState(library = library), "shuffle_all")
        assertEquals(AppEffect.ShuffleAll, result.effects.single())
    }

    @Test fun `Audiobooks stays on the main menu even with no books`() {
        assertTrue("audiobooks" in keys(AppState()))
        assertTrue("audiobooks" in keys(AppState(library = library)))
    }

    @Test fun `Audiobooks opens its own screen`() {
        assertEquals(Screen.Audiobooks, open(AppState(library = library), "audiobooks").state.currentScreen)
    }

    @Test fun `the main menu subtitle never regroups the library`() {
        val row = ScreenContent.rows(AppState(library = library))[1] as ScreenRow.Action
        assertEquals("Audiobooks", row.title)
        assertEquals("Pick up where you stopped", row.subtitle)
    }

    // ---- Music -----------------------------------------------------------------

    @Test fun `Music owns every library entry point`() {
        val music = AppState(screenStack = listOf(ScreenEntry(Screen.Music)), library = library)
        assertEquals(
            listOf("shuffle_all", "songs", "albums", "artists", "playlists", "favorites", "recent", "folders"),
            keys(music)
        )
    }

    @Test fun `Favorites and Recently Played are one Confirm from Music`() {
        val music = AppState(screenStack = listOf(ScreenEntry(Screen.Music)), library = library)
        assertEquals(Screen.Favorites, open(music, "favorites").state.currentScreen)
        assertEquals(Screen.RecentlyPlayed, open(music, "recent").state.currentScreen)
    }

    @Test fun `Favorites is three levels deep, not four`() {
        var state: AppState = AppState(library = library)
        state = open(state, "music").state
        state = open(state, "favorites").state
        assertEquals(Screen.Favorites, state.currentScreen)
        assertEquals(3, state.screenStack.size)
    }

    @Test fun `Shuffle All inside Music shuffles the whole library`() {
        val music = AppState(screenStack = listOf(ScreenEntry(Screen.Music)), library = library)
        assertEquals(AppEffect.ShuffleAll, open(music, "shuffle_all").effects.single())
    }

    @Test fun `Music counts favourites and recents without a track list`() {
        val music = AppState(screenStack = listOf(ScreenEntry(Screen.Music)), library = library)
        val rows = ScreenContent.rows(music).associate { (it as ScreenRow.Action).key to it.subtitle }
        assertEquals("Empty", rows["favorites"])
        assertEquals("Empty", rows["recent"])
        assertNull(rows["songs"])
    }

    // ---- Playlists -------------------------------------------------------------

    @Test fun `Playlists holds only user playlists`() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.Playlists)),
            library = LibraryState(tracks = listOf(track), playlists = listOf(PlaylistSummary(5, "Road Trip", 1)))
        )
        assertEquals(listOf("playlist:5", "playlist_create"), keys(state))
    }

    @Test fun `Favorites and Recently Played no longer masquerade as playlists`() {
        val state = AppState(screenStack = listOf(ScreenEntry(Screen.Playlists)), library = library)
        val all = keys(state)
        assertFalse("playlist_favorites" in all)
        assertFalse("playlist_recent" in all)
    }

    // ---- Settings --------------------------------------------------------------

    @Test fun `Audio moved under Settings and stays reachable`() {
        val settings = AppState(screenStack = listOf(ScreenEntry(Screen.Settings)))
        assertEquals(listOf("bluetooth", "audio", "interface", "library_settings", "system"), keys(settings))
        assertEquals(Screen.Audio, open(settings, "audio").state.currentScreen)
    }

    @Test fun `Audio is no longer a top level destination`() {
        assertFalse("audio" in keys(AppState()))
    }

    // ---- reachability ----------------------------------------------------------

    @Test fun `every screen the reducer can show is reachable from the main menu`() {
        val reachable = HashSet<String>()
        val seen = HashSet<String>()

        fun walk(state: AppState, depth: Int) {
            val code = state.currentScreen.code
            reachable += code
            if (depth > 6 || !seen.add(code + "@" + depth)) return
            val rows = ScreenContent.rows(state)
            rows.indices.forEach { index ->
                val moved = state.copy(
                    screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index)
                )
                val next = AppReducer.reduce(moved, AppAction.Confirm).state
                if (next.screenStack.size > state.screenStack.size) walk(next, depth + 1)
            }
        }

        walk(
            AppState(
                library = LibraryState(tracks = listOf(track), playlists = listOf(PlaylistSummary(5, "Road Trip", 1))),
                device = DeviceState(internalStorageAvailable = true)
            ),
            0
        )

        listOf(
            "music", "audiobooks", "songs", "albums", "artists", "playlists", "favorites",
            "recently_played", "folders", "settings", "audio", "playback_transitions",
            "sound_effects", "bluetooth", "interface_settings", "library_settings",
            "display", "controls", "storage", "playback_history", "system", "diagnostics", "about"
        ).forEach { assertTrue("$it is unreachable from the main menu", it in reachable) }
    }
}
