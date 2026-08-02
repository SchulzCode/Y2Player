package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AudiobookProgress
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudiobookOptionsTest {

    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

    private var nextId = 1L

    private fun chapter(path: String, durationMs: Long = 600_000) = Track(
        id = nextId++,
        volumeId = "sdcard",
        absolutePath = "/storage/sdcard1/$path",
        relativePath = path,
        title = path.substringAfterLast('/'),
        artist = "Narrator",
        album = null,
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        durationMs = durationMs,
        fileSize = 1,
        modifiedAt = 1
    )

    private val dune = "sdcard|AUDIOBOOKS/Dune"
    private val chapters = (1..10).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }

    private fun library(progress: Map<String, AudiobookProgress> = emptyMap()) =
        LibraryState(tracks = chapters).copy(audiobookProgress = progress)

    private fun onOptions(progress: Map<String, AudiobookProgress> = emptyMap()) = AppState(
        screenStack = listOf(
            ScreenEntry(Screen.Audiobooks),
            ScreenEntry(Screen.AudiobookOptions(dune))
        ),
        library = library(progress)
    )

    private fun startedAtChapter(number: Int, positionMs: Long = 60_000) =
        mapOf(dune to AudiobookProgress(dune, chapters[number - 1].id, positionMs, 99))

    private fun keys(state: AppState) = ScreenContent.rows(state).map { (it as ScreenRow.Action).key }

    private fun select(state: AppState, index: Int) =
        state.copy(screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index))

    // Pick by key rather than by position, so a later reordering does not silently retarget a test.
    private fun selectKey(state: AppState, key: String): AppState {
        val index = ScreenContent.rows(state).indexOfFirst { (it as? ScreenRow.Action)?.key?.startsWith(key) == true }
        require(index >= 0) { "Missing row $key on ${state.currentScreen}" }
        return select(state, index)
    }

    // ---- reaching Book Options ---------------------------------------------------

    @Test fun `Right on a book opens Book Options without playing`() {
        val list = AppState(screenStack = listOf(ScreenEntry(Screen.Audiobooks)), library = library())
        val result = AppReducer.reduce(list, AppAction.Right)
        assertEquals(Screen.AudiobookOptions(dune), result.state.currentScreen)
        assertTrue("Right must not start playback", result.effects.isEmpty())
    }

    @Test fun `long Confirm matches Right`() {
        val list = AppState(screenStack = listOf(ScreenEntry(Screen.Audiobooks)), library = library())
        assertEquals(
            AppReducer.reduce(list, AppAction.Right).state.currentScreen,
            AppReducer.reduce(list, AppAction.ConfirmLong).state.currentScreen
        )
    }

    @Test fun `Confirm still resumes rather than opening options`() {
        val list = AppState(
            screenStack = listOf(ScreenEntry(Screen.Audiobooks)),
            library = library(startedAtChapter(4))
        )
        val result = AppReducer.reduce(list, AppAction.Confirm)
        assertEquals(Screen.NowPlaying, result.state.currentScreen)
        assertTrue(result.effects.single() is AppEffect.PlayCollection)
    }

    @Test fun `Book Options is titled with the book`() {
        assertEquals("Dune", ScreenContent.title(onOptions()))
    }

    // ---- conditional Clear Progress ----------------------------------------------

    @Test fun `Clear Progress is hidden until there is progress`() {
        assertEquals(
            listOf("audiobook_chapters:$dune", "audiobook_restart:$dune"),
            keys(onOptions())
        )
    }

    @Test fun `Clear Progress appears once a chapter is saved`() {
        val keys = keys(onOptions(startedAtChapter(4)))
        assertEquals(3, keys.size)
        assertEquals("audiobook_clear:$dune", keys[2])
    }

    @Test fun `Clear Progress names the chapter it forgets`() {
        val row = ScreenContent.rows(onOptions(startedAtChapter(4)))[2] as ScreenRow.Action
        assertEquals("Clear Progress", row.title)
        assertEquals("Forget chapter 4", row.subtitle)
    }

    @Test fun `progress on a deleted chapter does not offer Clear Progress`() {
        val stale = mapOf(dune to AudiobookProgress(dune, 9_999L, 1_000, 99))
        assertFalse("audiobook_clear:$dune" in keys(onOptions(stale)))
    }

    // ---- Start from Beginning ------------------------------------------------------

    @Test fun `Start from Beginning plays chapter one from zero`() {
        val result = AppReducer.reduce(selectKey(onOptions(startedAtChapter(4)), "audiobook_restart:"), AppAction.Confirm)
        val effect = result.effects.single() as AppEffect.PlayCollection
        assertEquals(0, effect.startIndex)
        assertTrue("must bypass the saved position", effect.fromStart)
        assertFalse(effect.shuffled)
        assertEquals(Screen.NowPlaying, result.state.currentScreen)
    }

    @Test fun `Start from Beginning ignores a saved position inside chapter one`() {
        val effect = AppReducer.reduce(selectKey(onOptions(startedAtChapter(1, 300_000)), "audiobook_restart:"), AppAction.Confirm)
            .effects.single() as AppEffect.PlayCollection
        assertEquals(0, effect.startIndex)
        assertTrue(effect.fromStart)
    }

    // ---- Clear Progress confirmation ------------------------------------------------

    @Test fun `Clear Progress asks first and names the book`() {
        val result = AppReducer.reduce(selectKey(onOptions(startedAtChapter(4)), "audiobook_clear:"), AppAction.Confirm)
        assertEquals(Screen.ConfirmAction(ConfirmPrompts.CLEAR_AUDIOBOOK + dune), result.state.currentScreen)
        assertTrue("must not clear before confirming", result.effects.isEmpty())

        val prompt = ScreenContent.rows(result.state).first() as ScreenRow.Group
        assertTrue(prompt.title.contains("Dune"))

        val selected = ScreenContent.rows(result.state)[result.state.selectedIndex] as ScreenRow.Action
        assertEquals(ScreenContent.CONFIRM_CANCEL_KEY, selected.key)
    }

    @Test fun `confirming clears progress and returns to the book list`() {
        val onConfirm = AppState(
            screenStack = listOf(
                ScreenEntry(Screen.Audiobooks),
                ScreenEntry(Screen.AudiobookOptions(dune)),
                ScreenEntry(Screen.ConfirmAction(ConfirmPrompts.CLEAR_AUDIOBOOK + dune), 2)
            ),
            library = library(startedAtChapter(4))
        )
        val result = AppReducer.reduce(onConfirm, AppAction.Confirm)
        assertEquals(AppEffect.ClearAudiobookProgress(dune), result.effects.single())
        assertEquals(Screen.Audiobooks, result.state.currentScreen)
    }

    @Test fun `cancelling leaves the progress alone`() {
        val onConfirm = AppState(
            screenStack = listOf(
                ScreenEntry(Screen.Audiobooks),
                ScreenEntry(Screen.AudiobookOptions(dune)),
                ScreenEntry(Screen.ConfirmAction(ConfirmPrompts.CLEAR_AUDIOBOOK + dune), 1)
            ),
            library = library(startedAtChapter(4))
        )
        val result = AppReducer.reduce(onConfirm, AppAction.Confirm)
        assertTrue(result.effects.isEmpty())
        assertEquals(Screen.AudiobookOptions(dune), result.state.currentScreen)
    }

    // ---- Chapters ------------------------------------------------------------------

    @Test fun `Chapters lists every chapter in playback order`() {
        val result = AppReducer.reduce(selectKey(onOptions(), "audiobook_chapters:"), AppAction.Confirm)
        assertEquals(Screen.AudiobookChapters(dune), result.state.currentScreen)
        val rows = ScreenContent.rows(result.state)
        assertEquals(10, rows.size)
        assertEquals("01.mp3", rows.first().title)
        assertEquals("10.mp3", rows.last().title)
    }

    @Test fun `Chapters opens on the chapter you are listening to`() {
        val result = AppReducer.reduce(selectKey(onOptions(startedAtChapter(7)), "audiobook_chapters:"), AppAction.Confirm)
        assertEquals(6, result.state.selectedIndex)
    }

    @Test fun `Chapters opens at the top for an untouched book`() {
        val result = AppReducer.reduce(selectKey(onOptions(), "audiobook_chapters:"), AppAction.Confirm)
        assertEquals(0, result.state.selectedIndex)
    }

    @Test fun `picking a chapter starts it from the beginning, not the saved position`() {
        val chaptersScreen = AppState(
            screenStack = listOf(
                ScreenEntry(Screen.Audiobooks),
                ScreenEntry(Screen.AudiobookOptions(dune)),
                ScreenEntry(Screen.AudiobookChapters(dune), 3)
            ),
            library = library(startedAtChapter(7))
        )
        val effect = AppReducer.reduce(chaptersScreen, AppAction.Confirm)
            .effects.single() as AppEffect.PlayCollection
        assertEquals("chapter 4", 3, effect.startIndex)
        assertTrue("explicit selection must not resume", effect.fromStart)
        assertEquals(10, effect.trackIds.size)
    }

    @Test fun `Right on a chapter opens Track Options`() {
        val chaptersScreen = AppState(
            screenStack = listOf(ScreenEntry(Screen.AudiobookChapters(dune), 2)),
            library = library()
        )
        val result = AppReducer.reduce(chaptersScreen, AppAction.Right)
        assertEquals(Screen.TrackOptions(chapters[2].id), result.state.currentScreen)
    }

    @Test fun `Back from Chapters returns to Book Options`() {
        val chaptersScreen = AppState(
            screenStack = listOf(
                ScreenEntry(Screen.Audiobooks),
                ScreenEntry(Screen.AudiobookOptions(dune)),
                ScreenEntry(Screen.AudiobookChapters(dune))
            ),
            library = library()
        )
        assertEquals(
            Screen.AudiobookOptions(dune),
            AppReducer.reduce(chaptersScreen, AppAction.Back).state.currentScreen
        )
    }

    // ---- the book disappearing ------------------------------------------------------

    @Test fun `Book Options survives the card being pulled`() {
        val gone = onOptions().copy(library = LibraryState())
        val rows = ScreenContent.rows(gone)
        assertEquals(1, rows.size)
        assertTrue(rows.single() is ScreenRow.Group)
        assertTrue(AppReducer.reduce(gone, AppAction.Confirm).effects.isEmpty())
    }

    @Test fun `Chapters of a vanished book is empty rather than wrong`() {
        val gone = AppState(
            screenStack = listOf(ScreenEntry(Screen.AudiobookChapters(dune))),
            library = LibraryState()
        )
        assertTrue(ScreenContent.rows(gone).isEmpty())
    }

    @Test fun `no audiobook action ever shuffles`() {
        val states = listOf(
            AppState(screenStack = listOf(ScreenEntry(Screen.Audiobooks)), library = library(startedAtChapter(4))),
            selectKey(onOptions(startedAtChapter(4)), "audiobook_restart:"),
            AppState(
                screenStack = listOf(ScreenEntry(Screen.AudiobookChapters(dune), 5)),
                library = library(startedAtChapter(4))
            )
        )
        states.forEach { state ->
            AppReducer.reduce(state, AppAction.Confirm).effects
                .filterIsInstance<AppEffect.PlayCollection>()
                .forEach { assertFalse("an audiobook must never shuffle", it.shuffled) }
        }
    }
}
