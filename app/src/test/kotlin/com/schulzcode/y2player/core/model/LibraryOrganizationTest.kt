package com.schulzcode.y2player.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryOrganizationTest {
    private fun track(
        id: Long,
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        albumArtist: String? = artist,
        year: Int? = null,
        genre: String? = null,
        number: Int? = 1
    ) = Track(
        id = id,
        volumeId = "sdcard",
        absolutePath = "/Music/$id-$title.flac",
        relativePath = "Music/$album/$id-$title.flac",
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        trackNumber = number,
        discNumber = 1,
        durationMs = 60_000,
        fileSize = 100,
        modifiedAt = id,
        year = year,
        genre = genre
    )

    @Test fun `same named albums remain separate by album artist`() {
        val organization = LibraryOrganization(listOf(
            track(1, "One", artist = "Bowie", album = "Greatest Hits"),
            track(2, "Two", artist = "Queen", album = "Greatest Hits")
        ))

        val albums = organization.albums(LibraryScope.All, AlbumSortOrder.ARTIST)
        assertEquals(listOf("Bowie", "Queen"), albums.map(AlbumEntry::albumArtist))
        assertEquals(listOf(listOf(1L), listOf(2L)), albums.map { album -> album.tracks.map(Track::id) })
    }

    @Test fun `album year uses the most common value and breaks a tie toward the earliest`() {
        val majority = LibraryOrganization(listOf(
            track(1, "One", year = 2001),
            track(2, "Two", year = 2002),
            track(3, "Three", year = 2002)
        )).albums(LibraryScope.All, AlbumSortOrder.TITLE).single()
        assertEquals(2002, majority.year)

        val tied = LibraryOrganization(listOf(
            track(1, "One", year = 2002),
            track(2, "Two", year = 2001)
        )).albums(LibraryScope.All, AlbumSortOrder.TITLE).single()
        assertEquals(2001, tied.year)
    }

    @Test fun `year and album chronology keep unknown values last in both directions`() {
        val organization = LibraryOrganization(listOf(
            track(1, "Old", album = "Old", year = 1990),
            track(2, "New", album = "New", year = 2020),
            track(3, "Unknown", album = "Unknown", year = null)
        ))

        assertEquals(listOf(2020, 1990, null), organization.years(YearSortOrder.NEWEST_FIRST).map(YearEntry::year))
        assertEquals(listOf(1990, 2020, null), organization.years(YearSortOrder.OLDEST_FIRST).map(YearEntry::year))
        assertEquals(
            listOf("Old", "New", "Unknown"),
            organization.albums(LibraryScope.All, AlbumSortOrder.YEAR_ASCENDING).map(AlbumEntry::title)
        )
        assertEquals(
            listOf("New", "Old", "Unknown"),
            organization.albums(LibraryScope.All, AlbumSortOrder.YEAR_DESCENDING).map(AlbumEntry::title)
        )
    }

    @Test fun `genres split repeated values deduplicate case and keep compound names intact`() {
        val organization = LibraryOrganization(listOf(
            track(1, "One", genre = "Rock; Electronic; rock"),
            track(2, "Two", genre = "R&B/Soul"),
            track(3, "Three", genre = null)
        ))

        assertEquals(
            listOf("Electronic", "R&B/Soul", "Rock", "Unknown Genre"),
            organization.genres().map(GenreEntry::label)
        )
        assertEquals(listOf(1L), organization.tracks(LibraryScope.Genre("rock", "Rock")).map(Track::id))
    }

    @Test fun `a year scope contains only tracks tagged with that exact year`() {
        val organization = LibraryOrganization(listOf(
            track(1, "One", year = 1999),
            track(2, "Two", year = 2000),
            track(3, "Three", year = null)
        ))

        assertEquals(listOf(2L), organization.tracks(LibraryScope.Year(2000)).map(Track::id))
        assertEquals(listOf(3L), organization.tracks(LibraryScope.Year(null)).map(Track::id))
    }

    @Test fun `album track order remains semantic regardless of library sort choices`() {
        val tracks = listOf(
            track(3, "Third", number = 3),
            track(1, "First", number = 1),
            track(2, "Second", number = 2)
        )
        val organization = LibraryOrganization(tracks)

        assertEquals(listOf("First", "Second", "Third"), organization.albumTrackOrder(tracks).map(Track::title))
        assertNull(organization.albums(LibraryScope.All, AlbumSortOrder.TITLE).single().year)
    }
}
