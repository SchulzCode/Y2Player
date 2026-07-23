package com.schulzcode.y2player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PlaylistPathResolverTest {
    @Test
    fun resolvesRelativeWindowsSeparatorsAndParentSegments() {
        val root = Files.createTempDirectory("y2-playlist-").toFile()
        try {
            val playlistDirectory = File(root, "Lists").apply { mkdirs() }
            val expected = File(root, "Music/song.flac").canonicalPath

            assertEquals(expected, PlaylistPathResolver.resolve(playlistDirectory, "..\\Music\\song.flac"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun resolvesEncodedFileUri() {
        val root = Files.createTempDirectory("y2-playlist-uri-").toFile()
        try {
            val track = File(root, "Music/A song.flac")
            assertEquals(track.canonicalPath, PlaylistPathResolver.resolve(root, track.toURI().toASCIIString()))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun ignoresCommentsAndNonFileUris() {
        val base = File(System.getProperty("java.io.tmpdir") ?: ".")
        assertNull(PlaylistPathResolver.resolve(base, "#EXTINF:123,Artist - Title"))
        assertNull(PlaylistPathResolver.resolve(base, "https://example.invalid/song.mp3"))
    }
}
