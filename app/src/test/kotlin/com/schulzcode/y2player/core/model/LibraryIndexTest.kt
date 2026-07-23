package com.schulzcode.y2player.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIndexTest {
    private fun track(id: Long, available: Boolean = true, favorite: Boolean = false) = Track(
        id = id,
        volumeId = "internal",
        absolutePath = "/storage/sdcard0/$id.mp3",
        relativePath = "$id.mp3",
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
}
