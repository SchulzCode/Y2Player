package com.schulzcode.y2player.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookIdentityTest {
    private fun key(path: String, volume: String = "sdcard") =
        AudiobookIdentity.folderKey(volume, path)

    @Test
    fun `book folder under audiobooks is the identity`() {
        assertEquals("sdcard|AUDIOBOOKS/Dune", key("AUDIOBOOKS/Dune/01 - Chapter One.mp3"))
    }

    @Test
    fun `every chapter of one book shares one key`() {
        val first = key("AUDIOBOOKS/Dune/01.mp3")
        val second = key("AUDIOBOOKS/Dune/17.mp3")
        assertEquals(first, second)
    }

    @Test
    fun `different books never share a key`() {
        assertTrue(key("AUDIOBOOKS/Dune/01.mp3") != key("AUDIOBOOKS/Neuromancer/01.mp3"))
    }

    @Test
    fun `volume is part of the identity`() {
        assertTrue(
            key("AUDIOBOOKS/Dune/01.mp3", volume = "sdcard") !=
                key("AUDIOBOOKS/Dune/01.mp3", volume = "internal")
        )
    }

    @Test
    fun `author level between audiobooks and the book is preserved`() {
        assertEquals(
            "sdcard|AUDIOBOOKS/Frank Herbert/Dune",
            key("AUDIOBOOKS/Frank Herbert/Dune/01.mp3")
        )
    }

    @Test
    fun `audiobooks nested below other music folders still matches`() {
        assertEquals(
            "sdcard|Media/Spoken/AUDIOBOOKS/Dune",
            key("Media/Spoken/AUDIOBOOKS/Dune/01.mp3")
        )
    }

    @Test
    fun `disc subfolders belong to one book`() {
        assertEquals("sdcard|AUDIOBOOKS/Dune", key("AUDIOBOOKS/Dune/Disc 1/01.mp3"))
        assertEquals(
            key("AUDIOBOOKS/Dune/Disc 1/01.mp3"),
            key("AUDIOBOOKS/Dune/Disc 2/01.mp3")
        )
    }

    @Test
    fun `common disc and part spellings are all recognised`() {
        for (folder in listOf(
            "Disc 1", "disc1", "Disc_2", "Disk 3", "CD1", "CD 2", "cd-3",
            "Part 1", "part_2", "Pt 3", "Vol 1", "Volume 2", "Tape 1", "Side 2"
        )) {
            assertEquals(
                "folder '$folder' should not split the book",
                "sdcard|AUDIOBOOKS/Dune",
                key("AUDIOBOOKS/Dune/$folder/01.mp3")
            )
        }
    }

    @Test
    fun `nested discs under an author still resolve to the book`() {
        assertEquals(
            "sdcard|AUDIOBOOKS/Frank Herbert/Dune",
            key("AUDIOBOOKS/Frank Herbert/Dune/Disc 1/01.mp3")
        )
    }

    @Test
    fun `folders that merely look like subdivisions are still books`() {
        assertEquals("sdcard|AUDIOBOOKS/Discworld", key("AUDIOBOOKS/Discworld/01.mp3"))
        assertEquals("sdcard|AUDIOBOOKS/Part of the Deal", key("AUDIOBOOKS/Part of the Deal/01.mp3"))
        assertEquals("sdcard|AUDIOBOOKS/CD Player Manual", key("AUDIOBOOKS/CD Player Manual/01.mp3"))
        assertEquals("sdcard|AUDIOBOOKS/Book 1", key("AUDIOBOOKS/Book 1/01.mp3"))
    }

    @Test
    fun `a book folder named like a subdivision is still one book`() {
        assertEquals("sdcard|AUDIOBOOKS/Part 1", key("AUDIOBOOKS/Part 1/01.mp3"))
        assertEquals(
            key("AUDIOBOOKS/Part 1/01.mp3"),
            key("AUDIOBOOKS/Part 1/02.mp3")
        )
        assertEquals("sdcard|AUDIOBOOKS/Disc 1", key("AUDIOBOOKS/Disc 1/01.mp3"))
        assertEquals("sdcard|AUDIOBOOKS/CD1", key("AUDIOBOOKS/CD1/track.mp3"))
    }

    @Test
    fun `stacked subdivision folders collapse to the book`() {
        assertEquals("sdcard|AUDIOBOOKS/Dune", key("AUDIOBOOKS/Dune/Disc 1/CD 2/01.mp3"))
    }

    @Test
    fun `nothing above the marker can become the key`() {
        val chapter = key("Media/AUDIOBOOKS/Disc 1/01.mp3")!!
        assertTrue("key must not stop above AUDIOBOOKS", chapter.contains("AUDIOBOOKS"))
        assertEquals("sdcard|Media/AUDIOBOOKS/Disc 1", chapter)
    }

    @Test
    fun `file directly inside audiobooks becomes its own book`() {
        assertEquals("sdcard|AUDIOBOOKS/loose.mp3", key("AUDIOBOOKS/loose.mp3"))
    }

    @Test
    fun `two loose files inside audiobooks do not share a key`() {
        assertTrue(key("AUDIOBOOKS/a.mp3") != key("AUDIOBOOKS/b.mp3"))
    }

    @Test
    fun `marker directory matches regardless of case`() {
        assertEquals("sdcard|Audiobooks/Dune", key("Audiobooks/Dune/01.mp3"))
        assertEquals("sdcard|audiobooks/Dune", key("audiobooks/Dune/01.mp3"))
        assertEquals("sdcard|AudioBooks/Dune", key("AudioBooks/Dune/01.mp3"))
    }

    @Test
    fun `backslash separators are normalised`() {
        assertEquals("sdcard|AUDIOBOOKS/Dune", key("AUDIOBOOKS\\Dune\\01.mp3"))
    }

    @Test
    fun `mixed and repeated separators are normalised`() {
        assertEquals("sdcard|AUDIOBOOKS/Dune", key("/AUDIOBOOKS//Dune\\01.mp3"))
    }

    @Test
    fun `current-directory segments are ignored`() {
        assertEquals("sdcard|AUDIOBOOKS/Dune", key("AUDIOBOOKS/./Dune/01.mp3"))
    }

    @Test
    fun `parent-directory segments make the path unusable`() {
        assertNull(key("AUDIOBOOKS/Dune/../Dune/01.mp3"))
        assertNull(key("AUDIOBOOKS/../AUDIOBOOKS/Dune/01.mp3"))
        assertNull(key("../AUDIOBOOKS/Dune/01.mp3"))
    }

    @Test
    fun `ordinary music has no audiobook identity`() {
        assertNull(key("Music/Pink Floyd/Animals/01 - Pigs.flac"))
    }

    @Test
    fun `substring folder names must not match`() {
        assertNull(key("MyAudiobooks/Dune/01.mp3"))
        assertNull(key("AUDIOBOOKS_OLD/Dune/01.mp3"))
        assertNull(key("Old AUDIOBOOKS backup/Dune/01.mp3"))
    }

    @Test
    fun `a file named audiobooks is not a marker`() {
        assertNull(key("Music/audiobooks.mp3"))
    }

    @Test
    fun `marker with no file below it yields nothing`() {
        assertNull(key("AUDIOBOOKS"))
        assertNull(key("AUDIOBOOKS/"))
    }

    @Test
    fun `malformed paths are rejected rather than throwing`() {
        assertNull(key(""))
        assertNull(key("/"))
        assertNull(key("///"))
        assertNull(key("."))
    }

    @Test
    fun `track exposes the identity and the boolean`() {
        val chapter = track("AUDIOBOOKS/Dune/01.mp3")
        assertEquals("sdcard|AUDIOBOOKS/Dune", chapter.audiobookFolderKey)
        assertTrue(chapter.isAudiobookChapter)

        val song = track("Music/Album/01.flac")
        assertNull(song.audiobookFolderKey)
        assertFalse(song.isAudiobookChapter)
    }

    private fun track(relativePath: String) = Track(
        id = 1,
        volumeId = "sdcard",
        absolutePath = "/storage/sdcard1/$relativePath",
        relativePath = relativePath,
        title = "t",
        artist = null,
        album = null,
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        durationMs = 1_000,
        fileSize = 1,
        modifiedAt = 0
    )
}
