package com.schulzcode.y2player.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class PlaylistTextReaderTest {
    @Test
    fun skipsOversizedLineWithoutLosingFollowingEntries() {
        val file = File.createTempFile("y2-playlist-lines", ".m3u8")
        try {
            file.writeText("first.mp3\r\n${"x".repeat(10_000)}\nlast.flac", Charsets.UTF_8)
            val lines = ArrayList<String>()

            PlaylistTextReader.forEachLine(file, Charsets.UTF_8, maxLines = 100, maxLineChars = 4_096, lines::add)

            assertEquals(listOf("first.mp3", "last.flac"), lines)
        } finally { file.delete() }
    }

    @Test
    fun detectsWindows1252WhenM3uIsNotUtf8() {
        val file = File.createTempFile("y2-playlist-charset", ".m3u")
        try {
            file.writeBytes("Müsic/song.mp3".toByteArray(Charset.forName("windows-1252")))
            assertEquals("windows-1252", PlaylistTextReader.charsetFor(file, forceUtf8 = false).name().lowercase())
        } finally { file.delete() }
    }
}
