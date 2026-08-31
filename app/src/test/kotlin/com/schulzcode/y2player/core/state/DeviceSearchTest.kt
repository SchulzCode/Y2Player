package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSearchTest {
    private val song = track(1, "Beyoncé Halo", "Beyoncé", "I Am Sasha Fierce", "Music/Halo.mp3")
    private val audiobook = track(2, "Chapter One", "Frank Herbert", "Dune", "Audiobooks/Dune/01.mp3")
    private val library = LibraryState(
        tracks = listOf(song, audiobook),
        playlists = listOf(PlaylistSummary(7, "Road Mix", 1)),
        playlistTrackIds = mapOf(7L to listOf(1L))
    )

    @Test fun `search covers metadata playlists and audiobook folders`() {
        assertTrue(DeviceSearch.find(library, "halo").any { it is DeviceSearchResult.TrackResult })
        assertTrue(DeviceSearch.find(library, "sasha").any { it is DeviceSearchResult.AlbumResult })
        assertTrue(DeviceSearch.find(library, "beyonce").any { it is DeviceSearchResult.ArtistResult })
        assertTrue(DeviceSearch.find(library, "road").any { it is DeviceSearchResult.PlaylistResult })
        assertTrue(DeviceSearch.find(library, "dune").any { it is DeviceSearchResult.AudiobookResult })
    }

    @Test fun `search is accent insensitive and supports multiple tokens`() {
        val results = DeviceSearch.find(library, "beyonce halo")
        assertEquals(listOf(song.id), results.filterIsInstance<DeviceSearchResult.TrackResult>().map { it.track.id })
    }

    @Test fun `blank queries do not enumerate the library`() {
        assertTrue(DeviceSearch.find(library, "   ").isEmpty())
    }

    private fun track(id: Long, title: String, artist: String, album: String, relativePath: String) = Track(
        id = id,
        volumeId = "sdcard",
        absolutePath = "/storage/$relativePath",
        relativePath = relativePath,
        title = title,
        artist = artist,
        album = album,
        albumArtist = artist,
        trackNumber = 1,
        discNumber = 1,
        durationMs = 60_000,
        fileSize = 100,
        modifiedAt = 1
    )
}
