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

            assertEquals(expected, PlaylistPathResolver(playlistDirectory).resolve("..\\Music\\song.flac"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun relocatesAbsoluteWindowsPathsByTheirLongestExistingTail() {
        val root = Files.createTempDirectory("y2-playlist-windows-").toFile()
        try {
            val playlistDirectory = File(root, "Playlists").apply { mkdirs() }
            val track = File(root, "Music/GAY PRIESTS/song.mp3").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("audio")
            }
            File(playlistDirectory, track.name).writeText("wrong audio")

            val resolved = PlaylistPathResolver(playlistDirectory).resolve(
                "C:\\Users\\PC-R\\Music\\GAY PRIESTS\\song.mp3"
            )

            assertEquals(track.canonicalPath, resolved)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun relocatesMacLinuxAndForeignFileUriPaths() {
        val root = Files.createTempDirectory("y2-playlist-unix-").toFile()
        try {
            val playlistDirectory = File(root, "Playlists").apply { mkdirs() }
            val album = File(root, "Music/Album").apply { mkdirs() }
            val macTrack = File(album, "mac song.mp3").apply { writeText("audio") }
            val linuxTrack = File(album, "linux song.flac").apply { writeText("audio") }
            val resolver = PlaylistPathResolver(playlistDirectory)

            assertEquals(
                macTrack.canonicalPath,
                resolver.resolve("/Users/listener/Music/Album/mac song.mp3")
            )
            assertEquals(
                linuxTrack.canonicalPath,
                resolver.resolve("file:///home/listener/Music/Album/linux%20song.flac")
            )
            assertEquals(macTrack.canonicalPath, resolver.resolve(macTrack.absolutePath))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun resolvesEncodedFileUri() {
        val root = Files.createTempDirectory("y2-playlist-uri-").toFile()
        try {
            val track = File(root, "Music/A song.flac")
            assertEquals(track.canonicalPath, PlaylistPathResolver(root).resolve(track.toURI().toASCIIString()))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun ignoresCommentsAndNonFileUris() {
        val base = File(System.getProperty("java.io.tmpdir") ?: ".")
        val resolver = PlaylistPathResolver(base)
        assertNull(resolver.resolve("#EXTINF:123,Artist - Title"))
        assertNull(resolver.resolve("https://example.invalid/song.mp3"))
    }
}
