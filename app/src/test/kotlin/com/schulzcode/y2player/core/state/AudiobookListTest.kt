package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AudiobookProgress
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudiobookListTest {

    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

    private var nextId = 1L

    private fun chapter(
        path: String,
        durationMs: Long = 600_000,
        title: String? = null,
        trackNumber: Int? = null,
        discNumber: Int? = null
    ) = Track(
        id = nextId++,
        volumeId = "sdcard",
        absolutePath = "/storage/sdcard1/$path",
        relativePath = path,
        title = title ?: path.substringAfterLast('/'),
        artist = "Narrator",
        album = null,
        albumArtist = null,
        trackNumber = trackNumber,
        discNumber = discNumber,
        durationMs = durationMs,
        fileSize = 1,
        modifiedAt = 1
    )

    private fun state(
        tracks: List<Track>,
        progress: Map<String, AudiobookProgress> = emptyMap()
    ) = AppState(
        screenStack = listOf(ScreenEntry(Screen.Audiobooks)),
        library = LibraryState(tracks = tracks).copy(audiobookProgress = progress)
    )

    private fun rows(state: AppState) = ScreenContent.rows(state).map { it as ScreenRow.Action }

    private fun key(book: String) = "sdcard|AUDIOBOOKS/$book"

    // ---- grouping ---------------------------------------------------------------

    @Test fun `one row per book, not per chapter`() {
        val tracks = (1..5).map { chapter("AUDIOBOOKS/Dune/0$it.mp3") }
        val rows = rows(state(tracks))
        assertEquals(1, rows.size)
        assertEquals("Dune", rows.single().title)
    }

    @Test fun `disc folders stay one book`() {
        val tracks = listOf(
            chapter("AUDIOBOOKS/Neuromancer/Disc 1/01.mp3"),
            chapter("AUDIOBOOKS/Neuromancer/Disc 1/02.mp3"),
            chapter("AUDIOBOOKS/Neuromancer/Disc 2/01.mp3")
        )
        val rows = rows(state(tracks))
        assertEquals(1, rows.size)
        assertEquals("Neuromancer", rows.single().title)
        assertTrue(rows.single().subtitle!!.startsWith("3 chapters"))
    }

    @Test fun `an author folder shows the book, not the author`() {
        val tracks = (1..3).map { chapter("AUDIOBOOKS/Frank Herbert/Children of Dune/0$it.mp3") }
        assertEquals("Children of Dune", rows(state(tracks)).single().title)
    }

    @Test fun `a single file book uses its track title`() {
        val tracks = listOf(chapter("AUDIOBOOKS/single-file-book.mp3", title = "The Hobbit"))
        val row = rows(state(tracks)).single()
        assertEquals("The Hobbit", row.title)
        assertEquals("1 chapter · 10 min", row.subtitle)
    }

    @Test fun `music outside AUDIOBOOKS never appears`() {
        val tracks = listOf(chapter("Music/Album/01.mp3"), chapter("AUDIOBOOKS/Dune/01.mp3"))
        assertEquals(1, rows(state(tracks)).size)
    }

    @Test fun `chapters order naturally, not lexically`() {
        val tracks = listOf(
            chapter("AUDIOBOOKS/Dune/10.mp3"),
            chapter("AUDIOBOOKS/Dune/2.mp3"),
            chapter("AUDIOBOOKS/Dune/1.mp3")
        )
        val entry = ScreenContent.audiobookEntry(state(tracks), key("Dune"))!!
        val titles = entry.chapterIds.map { id -> tracks.first { it.id == id }.title }
        assertEquals(listOf("1.mp3", "2.mp3", "10.mp3"), titles)
    }

    @Test fun `track numbers that restart in each untagged disc do not interleave discs`() {
        val tracks = listOf(
            chapter("AUDIOBOOKS/Dune/Disc 2/02.mp3", trackNumber = 2),
            chapter("AUDIOBOOKS/Dune/Disc 1/02.mp3", trackNumber = 2),
            chapter("AUDIOBOOKS/Dune/Disc 2/01.mp3", trackNumber = 1),
            chapter("AUDIOBOOKS/Dune/Disc 1/01.mp3", trackNumber = 1)
        )
        val entry = ScreenContent.audiobookEntry(state(tracks), key("Dune"))!!
        assertEquals(listOf(tracks[3].id, tracks[1].id, tracks[2].id, tracks[0].id), entry.chapterIds)
    }

    @Test fun `disc folder numbers sort naturally and deterministically`() {
        val tracks = listOf(
            chapter("AUDIOBOOKS/Dune/Disc 10/01.mp3"),
            chapter("AUDIOBOOKS/Dune/Disc 2/01.mp3"),
            chapter("AUDIOBOOKS/Dune/Disc 1/01.mp3")
        )
        val entry = ScreenContent.audiobookEntry(state(tracks), key("Dune"))!!
        assertEquals(listOf(tracks[2].id, tracks[1].id, tracks[0].id), entry.chapterIds)
    }

    // ---- subtitle correctness ----------------------------------------------------

    @Test fun `an unstarted book shows chapter count and total duration`() {
        val tracks = (1..24).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        assertEquals("24 chapters · 4 h", rows(state(tracks)).single().subtitle)
    }

    @Test fun `a started book shows chapter position and percent`() {
        val tracks = (1..24).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        val progress = mapOf(key("Dune") to AudiobookProgress(key("Dune"), tracks[7].id, 300_000, 99))
        assertEquals("Chapter 8 of 24 · 31%", rows(state(tracks, progress)).single().subtitle)
    }

    @Test fun `chapter one at zero reads as zero percent`() {
        val tracks = (1..10).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        val progress = mapOf(key("Dune") to AudiobookProgress(key("Dune"), tracks[0].id, 0, 99))
        assertEquals("Chapter 1 of 10 · 0%", rows(state(tracks, progress)).single().subtitle)
    }

    @Test fun `the final chapter near its end never exceeds one hundred percent`() {
        val tracks = (1..3).map { chapter("AUDIOBOOKS/Dune/0$it.mp3") }
        val progress = mapOf(key("Dune") to AudiobookProgress(key("Dune"), tracks[2].id, 599_999, 99))
        val subtitle = rows(state(tracks, progress)).single().subtitle!!
        assertTrue(subtitle.endsWith("99%") || subtitle.endsWith("100%"))
    }

    @Test fun `a position past the total is clamped, never negative or over one hundred`() {
        val tracks = listOf(chapter("AUDIOBOOKS/Dune/01.mp3", durationMs = 1_000))
        val progress = mapOf(key("Dune") to AudiobookProgress(key("Dune"), tracks[0].id, 9_999_999, 99))
        assertEquals("Chapter 1 of 1 · 100%", rows(state(tracks, progress)).single().subtitle)
    }

    @Test fun `an unknown duration drops the percent instead of dividing by zero`() {
        val tracks = (1..4).map { chapter("AUDIOBOOKS/Dune/0$it.mp3", durationMs = 0) }
        assertEquals("4 chapters", rows(state(tracks)).single().subtitle)

        val progress = mapOf(key("Dune") to AudiobookProgress(key("Dune"), tracks[1].id, 5_000, 99))
        assertEquals("Chapter 2 of 4", rows(state(tracks, progress)).single().subtitle)
    }

    @Test fun `one unknown chapter duration suppresses the whole total`() {
        val tracks = listOf(
            chapter("AUDIOBOOKS/Dune/01.mp3", durationMs = 600_000),
            chapter("AUDIOBOOKS/Dune/02.mp3", durationMs = 0)
        )
        assertEquals("2 chapters", rows(state(tracks)).single().subtitle)
    }

    @Test fun `progress naming a deleted chapter falls back to unstarted`() {
        val tracks = (1..5).map { chapter("AUDIOBOOKS/Dune/0$it.mp3") }
        val progress = mapOf(key("Dune") to AudiobookProgress(key("Dune"), 9_999L, 300_000, 99))
        val row = rows(state(tracks, progress)).single()
        assertEquals("5 chapters · 50 min", row.subtitle)
        assertFalse(row.subtitle!!.contains("Chapter"))
    }

    // ---- ordering ----------------------------------------------------------------

    @Test fun `books in progress sort above untouched ones, most recent first`() {
        val tracks = listOf("Alpha", "Beta", "Gamma").flatMap { book ->
            (1..2).map { chapter("AUDIOBOOKS/$book/0$it.mp3") }
        }
        val progress = mapOf(
            key("Gamma") to AudiobookProgress(key("Gamma"), tracks[4].id, 1_000, 500),
            key("Alpha") to AudiobookProgress(key("Alpha"), tracks[0].id, 1_000, 100)
        )
        assertEquals(listOf("Gamma", "Alpha", "Beta"), rows(state(tracks, progress)).map { it.title })
    }

    @Test fun `untouched books stay alphabetical and stable across rebuilds`() {
        val tracks = listOf("Zeta", "alpha", "Mu").flatMap { book ->
            listOf(chapter("AUDIOBOOKS/$book/01.mp3"))
        }
        val first = rows(state(tracks)).map { it.title }
        ScreenContent.clearCachedRows()
        val second = rows(state(tracks)).map { it.title }
        assertEquals(listOf("alpha", "Mu", "Zeta"), first)
        assertEquals(first, second)
    }

    // ---- actions -----------------------------------------------------------------

    @Test fun `Confirm resumes at the saved chapter`() {
        val tracks = (1..10).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        val progress = mapOf(key("Dune") to AudiobookProgress(key("Dune"), tracks[6].id, 120_000, 99))
        val result = AppReducer.reduce(state(tracks, progress), AppAction.Confirm)
        val effect = result.effects.single() as AppEffect.PlayCollection
        assertEquals(10, effect.trackIds.size)
        assertEquals("resumes at chapter 7", 6, effect.startIndex)
        assertFalse("an audiobook must never start shuffled", effect.shuffled)
        assertEquals(Screen.NowPlaying, result.state.currentScreen)
    }

    @Test fun `Confirm on an untouched book starts at chapter one`() {
        val tracks = (1..10).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        val effect = AppReducer.reduce(state(tracks), AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertEquals(0, effect.startIndex)
    }

    @Test fun `Confirm queues every chapter of that book only`() {
        val tracks = (1..3).map { chapter("AUDIOBOOKS/Dune/0$it.mp3") } +
            (1..4).map { chapter("AUDIOBOOKS/Neuromancer/0$it.mp3") }
        val duneRow = rows(state(tracks)).indexOfFirst { it.title == "Dune" }
        val selected = state(tracks).copy(screenStack = listOf(ScreenEntry(Screen.Audiobooks, duneRow)))
        val effect = AppReducer.reduce(selected, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertEquals(3, effect.trackIds.size)
    }

    @Test fun `opening Audiobooks reloads progress once`() {
        val home = AppState(library = LibraryState(tracks = listOf(chapter("AUDIOBOOKS/Dune/01.mp3"))))
        val selected = home.copy(screenStack = listOf(ScreenEntry(Screen.MainMenu, 1)))
        val result = AppReducer.reduce(selected, AppAction.Confirm)
        assertEquals(Screen.Audiobooks, result.state.currentScreen)
        assertEquals(AppEffect.RefreshAudiobooks, result.effects.single())
    }

    @Test fun `an empty AUDIOBOOKS folder produces the empty state, not a phantom row`() {
        assertTrue(ScreenContent.rows(state(emptyList())).isEmpty())
    }

    @Test fun `Confirm on a book whose chapters vanished does nothing`() {
        val tracks = listOf(chapter("AUDIOBOOKS/Dune/01.mp3"))
        val populated = state(tracks)
        val emptied = populated.copy(library = LibraryState())
        assertTrue(AppReducer.reduce(emptied, AppAction.Confirm).effects.none { it is AppEffect.PlayCollection })
    }

    @Test fun `a rescan does not wipe saved progress`() {
        val tracks = (1..10).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        val progress = mapOf(key("Dune") to AudiobookProgress(key("Dune"), tracks[4].id, 60_000, 99))
        val before = state(tracks, progress)
        assertTrue(rows(before).single().subtitle!!.contains("Chapter 5 of 10"))

        // What a scan produces: a new index and bumped revisions, everything else carried.
        val rescanned = before.copy(
            library = before.library.copy(
                revision = before.library.revision + 1,
                tracksRevision = before.library.tracksRevision + 1,
                index = com.schulzcode.y2player.core.model.LibraryIndex.of(tracks)
            )
        )
        assertTrue(
            "a rescan must not reset the book to unstarted",
            rows(rescanned).single().subtitle!!.contains("Chapter 5 of 10")
        )
    }

    @Test fun `progress surviving a rescan keeps Confirm resuming`() {
        val tracks = (1..10).map { chapter("AUDIOBOOKS/Dune/%02d.mp3".format(it)) }
        val progress = mapOf(key("Dune") to AudiobookProgress(key("Dune"), tracks[4].id, 60_000, 99))
        val rescanned = state(tracks, progress).let {
            it.copy(library = it.library.copy(
                revision = it.library.revision + 1,
                index = com.schulzcode.y2player.core.model.LibraryIndex.of(tracks)
            ))
        }
        val effect = AppReducer.reduce(rescanned, AppAction.Confirm).effects.single() as AppEffect.PlayCollection
        assertEquals("must still resume at chapter 5", 4, effect.startIndex)
    }

    // ---- cost --------------------------------------------------------------------

    @Test fun `a large library groups once per revision, not per call`() {
        val tracks = ArrayList<Track>(9_000)
        repeat(300) { book ->
            repeat(30) { part ->
                tracks += chapter("AUDIOBOOKS/Book %03d/%02d.mp3".format(book, part))
            }
        }
        val large = state(tracks)

        val first = ScreenContent.rows(large)
        val second = ScreenContent.rows(large)

        assertEquals(300, first.size)
        assertSame("the grouping pass must be cached, not repeated", first, second)
    }

    @Test fun `a library change rebuilds the grouping`() {
        val tracks = (1..2).map { chapter("AUDIOBOOKS/Dune/0$it.mp3") }
        val before = state(tracks)
        val rowsBefore = ScreenContent.rows(before)

        val added = tracks + chapter("AUDIOBOOKS/Neuromancer/01.mp3")
        val after = before.copy(
            library = before.library.copy(
                revision = 1,
                tracksRevision = 1,
                index = com.schulzcode.y2player.core.model.LibraryIndex.of(added)
            )
        )
        val rowsAfter = ScreenContent.rows(after)

        assertEquals(1, rowsBefore.size)
        assertEquals(2, rowsAfter.size)
    }

    @Test fun `saved progress changes the subtitle without a track change`() {
        val tracks = (1..4).map { chapter("AUDIOBOOKS/Dune/0$it.mp3") }
        val before = state(tracks)
        assertFalse(
            "an untouched book must not claim a chapter position",
            (ScreenContent.rows(before).single() as ScreenRow.Action).subtitle!!.contains("Chapter")
        )

        val progress = mapOf(key("Dune") to AudiobookProgress(key("Dune"), tracks[2].id, 0, 5))
        val after = before.copy(library = before.library.copy(revision = 1, audiobookProgress = progress))
        val row = ScreenContent.rows(after).single() as ScreenRow.Action
        assertNotNull(row.subtitle)
        assertTrue("progress must invalidate the cached rows", row.subtitle!!.contains("Chapter 3 of 4"))
    }
}
