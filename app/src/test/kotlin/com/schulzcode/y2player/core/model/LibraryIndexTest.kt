package com.schulzcode.y2player.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIndexTest {
    private fun track(
        id: Long,
        available: Boolean = true,
        favorite: Boolean = false,
        relativePath: String = "$id.mp3"
    ) = Track(
        id = id,
        volumeId = "internal",
        absolutePath = "/storage/sdcard0/$relativePath",
        relativePath = relativePath,
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        durationMs = 1_000,
        fileSize = 1,
        modifiedAt = 1,
        available = available,
        favorite = favorite
    )

    @Test fun derivedLookupsAreConsistent() {
        val available = track(1, favorite = true)
        val unavailable = track(2, available = false, favorite = true)
        val plain = track(3)
        val index = LibraryIndex.of(listOf(available, unavailable, plain))

        assertEquals(3, index.byId.size)
        assertSame(unavailable, index.byId[2L])
        assertEquals(listOf(available, plain), index.availableTracks)
        assertEquals(listOf(available), index.favoriteTracks)
        assertEquals(setOf(1L, 3L), index.availableTrackIds)
    }

    @Test fun emptyListsShareTheSingletonIndex() {
        assertSame(LibraryIndex.EMPTY, LibraryIndex.of(emptyList()))
        assertSame(LibraryState().index, LibraryState(tracks = emptyList()).index)
    }

    @Test fun stateCopyPreservesTheIndexByReference() {
        val state = LibraryState(tracks = listOf(track(1)))
        val copied = state.copy(revision = 42, recentlyPlayedIds = listOf(1L))
        assertSame(state.index, copied.index)
        assertSame(state.byId, copied.byId)
    }

    @Test fun recentlyPlayedResolvesThroughTheIndex() {
        val one = track(1)
        val state = LibraryState(tracks = listOf(one)).copy(recentlyPlayedIds = listOf(1L, 99L))
        assertEquals(listOf(one), state.recentlyPlayed)
        assertFalse(state.recentlyPlayed.isEmpty())
        assertTrue(state.availableTrackIds.contains(1L))
    }

    @Test fun audiobookTracksStayAvailableButAreExcludedFromMusicLookups() {
        val song = track(1, favorite = true, relativePath = "Music/Album/01.mp3")
        val chapter = track(2, favorite = true, relativePath = "AUDIOBOOKS/Book/01.mp3")
        val state = LibraryState(tracks = listOf(song, chapter)).copy(recentlyPlayedIds = listOf(2L, 1L))

        assertEquals(listOf(song, chapter), state.availableTracks)
        assertEquals(listOf(song, chapter), state.favoriteTracks)
        assertEquals(listOf(song), state.musicTracks)
        assertEquals(listOf(song), state.favoriteMusicTracks)
        assertEquals(listOf(song), state.recentlyPlayedMusic)
        assertEquals(setOf(1L, 2L), state.availableTrackIds)
    }
}
