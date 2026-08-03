package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.ArtistCredit
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeaturedArtistTest {

    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

    private var nextId = 1L

    private fun song(
        title: String,
        artist: String?,
        album: String? = "Random Access Memories",
        albumArtist: String? = "Daft Punk",
        number: Int? = null
    ) = Track(
        id = nextId++,
        volumeId = "sdcard",
        absolutePath = "/storage/sdcard1/Music/$title.mp3",
        relativePath = "Music/$title.mp3",
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        trackNumber = number,
        discNumber = null,
        durationMs = 240_000,
        fileSize = 1,
        modifiedAt = 1
    )

    private val ram = listOf(
        song("Give Life Back to Music", "Daft Punk", number = 1),
        song("Get Lucky", "Daft Punk feat. Pharrell Williams", number = 2),
        song("Contact", "Daft Punk", number = 3)
    )

    private fun keys(state: AppState) = ScreenContent.rows(state).map {
        when (it) {
            is ScreenRow.Group -> it.key
            is ScreenRow.Action -> it.key
            is ScreenRow.TrackRow -> it.track.title
            else -> "?"
        }
    }

    private fun onScreen(screen: Screen, tracks: List<Track> = ram) =
        AppState(screenStack = listOf(ScreenEntry(screen)), library = LibraryState(tracks = tracks))

    // ---- the reported defect -------------------------------------------------------

    @Test fun `an album keeps every track when reached through its main artist`() {
        val rows = ScreenContent.rows(onScreen(Screen.AlbumSongs("Random Access Memories", "Daft Punk")))
        val titles = rows.filterIsInstance<ScreenRow.TrackRow>().map { it.track.title }
        assertEquals(
            "the feature track must not vanish from the album",
            listOf("Give Life Back to Music", "Get Lucky", "Contact"),
            titles
        )
    }

    @Test fun `the raw feature credit is no longer its own artist`() {
        val artists = keys(onScreen(Screen.Artists))
        assertFalse("Daft Punk feat. Pharrell Williams" in artists)
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), artists)
    }

    @Test fun `the album is listed once under its main artist, not twice`() {
        val albums = keys(onScreen(Screen.ArtistAlbums("Daft Punk")))
        assertEquals(listOf("artist_all_songs", "Random Access Memories"), albums)
    }

    // ---- the featured artist -------------------------------------------------------

    @Test fun `a featured artist gets their own entry`() {
        assertTrue("Pharrell Williams" in keys(onScreen(Screen.Artists)))
    }

    @Test fun `a featured artist lists the album they appear on`() {
        assertEquals(
            listOf("artist_all_songs", "Random Access Memories"),
            keys(onScreen(Screen.ArtistAlbums("Pharrell Williams")))
        )
    }

    @Test fun `the album row under a guest names whose album it is`() {
        val row = ScreenContent.rows(onScreen(Screen.ArtistAlbums("Pharrell Williams")))
            .filterIsInstance<ScreenRow.Group>()
            .single()
        assertEquals("Random Access Memories", row.title)
        assertEquals("Daft Punk", row.subtitle)
    }

    @Test fun `opening that album under the guest shows only their track`() {
        val rows = ScreenContent.rows(onScreen(Screen.AlbumSongs("Random Access Memories", "Pharrell Williams")))
        assertEquals(listOf("Get Lucky"), rows.filterIsInstance<ScreenRow.TrackRow>().map { it.track.title })
    }

    @Test fun `All Songs for the guest holds only their track`() {
        val rows = ScreenContent.rows(onScreen(Screen.ArtistSongs("Pharrell Williams")))
        assertEquals(listOf("Get Lucky"), rows.filterIsInstance<ScreenRow.TrackRow>().map { it.track.title })
    }

    @Test fun `All Songs for the main artist holds the whole album`() {
        val rows = ScreenContent.rows(onScreen(Screen.ArtistSongs("Daft Punk")))
        assertEquals(3, rows.filterIsInstance<ScreenRow.TrackRow>().size)
    }

    @Test fun `the artist count matches what the artist screen lists`() {
        val rows = ScreenContent.rows(onScreen(Screen.Artists)).filterIsInstance<ScreenRow.Group>()
        rows.forEach { row ->
            val listed = ScreenContent.rows(onScreen(Screen.ArtistSongs(row.key)))
                .filterIsInstance<ScreenRow.TrackRow>().size
            assertTrue("${row.key} says \"${row.subtitle}\" but lists $listed", row.subtitle!!.startsWith("$listed "))
        }
    }

    // ---- band names that must survive ------------------------------------------------

    @Test fun `ampersands commas and plus signs never split a name`() {
        listOf(
            "Simon & Garfunkel",
            "Earth, Wind & Fire",
            "Florence + the Machine",
            "Crosby, Stills, Nash & Young",
            "AC/DC",
            "Hall & Oates"
        ).forEach { name ->
            assertEquals(name, ArtistCredit.primary(name))
            assertNull("$name must not be treated as a feature", ArtistCredit.featured(name))
        }
    }

    @Test fun `a bare with never splits a band name`() {
        listOf("Sleeping with Sirens", "Girls with Guitars", "The Boy with the Arab Strap").forEach { name ->
            assertEquals(name, ArtistCredit.primary(name))
            assertNull(ArtistCredit.featured(name))
        }
    }

    @Test fun `a bracketed with is a feature`() {
        assertEquals("Elton John", ArtistCredit.primary("Elton John (with Dua Lipa)"))
        assertEquals("Dua Lipa", ArtistCredit.featured("Elton John (with Dua Lipa)"))
    }

    @Test fun `every spelling of the feature marker is recognised`() {
        listOf(
            "Daft Punk feat. Pharrell Williams",
            "Daft Punk feat Pharrell Williams",
            "Daft Punk ft. Pharrell Williams",
            "Daft Punk ft Pharrell Williams",
            "Daft Punk featuring Pharrell Williams",
            "Daft Punk FEAT. Pharrell Williams",
            "Daft Punk (feat. Pharrell Williams)",
            "Daft Punk [feat. Pharrell Williams]"
        ).forEach { credit ->
            assertEquals(credit, "Daft Punk", ArtistCredit.primary(credit))
            assertEquals(credit, "Pharrell Williams", ArtistCredit.featured(credit))
        }
    }

    @Test fun `a marker inside a word is not a marker`() {
        listOf("Ftown Boys", "Feather", "Featherweight Kings", "Withered Hand").forEach { name ->
            assertEquals(name, ArtistCredit.primary(name))
            assertNull(ArtistCredit.featured(name))
        }
    }

    @Test fun `a trailing marker with nobody after it is ignored`() {
        assertEquals("Daft Punk feat.", ArtistCredit.primary("Daft Punk feat."))
        assertNull(ArtistCredit.featured("Daft Punk feat."))
    }

    @Test fun `artist identity ignores surrounding whitespace and case`() {
        val tracks = listOf(
            song("One", "  Daft Punk feat. Guest  ", number = 1),
            song("Two", "daft punk feat. guest", albumArtist = " DAFT PUNK ", number = 2)
        )
        val artists = keys(onScreen(Screen.Artists, tracks))
        assertEquals(listOf("Daft Punk", "Guest"), artists)

        val guestSongs = ScreenContent.rows(onScreen(Screen.ArtistSongs("Guest"), tracks))
            .filterIsInstance<ScreenRow.TrackRow>()
        assertEquals(2, guestSongs.size)
    }

    // ---- album artist as the tie breaker ---------------------------------------------

    @Test fun `album artist keeps a compilation whole without any parsing`() {
        val comp = listOf(
            song("One", "Portishead", album = "Chillout", albumArtist = "Various Artists", number = 1),
            song("Two", "Massive Attack", album = "Chillout", albumArtist = "Various Artists", number = 2)
        )
        val rows = ScreenContent.rows(onScreen(Screen.Albums, comp)).filterIsInstance<ScreenRow.Group>()
        assertEquals("Chillout", rows.single().title)
        assertEquals("the album artist tag names the album", "Various Artists", rows.single().subtitle)
    }

    @Test fun `Various Artists does not become an artist`() {
        val comp = listOf(
            song("One", "Portishead", album = "Chillout", albumArtist = "Various Artists"),
            song("Two", "Massive Attack", album = "Chillout", albumArtist = "Various Artists")
        )
        assertEquals(listOf("Massive Attack", "Portishead"), keys(onScreen(Screen.Artists, comp)))
    }

    @Test fun `a literal Various Artists track credit is still not a performer row`() {
        val comp = listOf(
            song("Unknown Credit", "Various Artists", album = "Chillout", albumArtist = "Various Artists"),
            song("Known Credit", "Portishead", album = "Chillout", albumArtist = "Various Artists")
        )
        assertEquals(listOf("Portishead"), keys(onScreen(Screen.Artists, comp)))
    }

    @Test fun `a guest only track still belongs to the album artist`() {
        val guestOnly = listOf(
            song("Intro", "Daft Punk", number = 1),
            song("Guest Spot", "Pharrell Williams", number = 2)
        )
        val underOwner = ScreenContent.rows(onScreen(Screen.AlbumSongs("Random Access Memories", "Daft Punk"), guestOnly))
        assertEquals(2, underOwner.filterIsInstance<ScreenRow.TrackRow>().size)

        val underGuest = ScreenContent.rows(onScreen(Screen.AlbumSongs("Random Access Memories", "Pharrell Williams"), guestOnly))
        assertEquals(listOf("Guest Spot"), underGuest.filterIsInstance<ScreenRow.TrackRow>().map { it.track.title })
    }

    @Test fun `a missing album artist falls back to the primary credit`() {
        val untagged = listOf(song("Solo", "Daft Punk feat. Pharrell Williams", albumArtist = null))
        assertEquals("Daft Punk", untagged.single().albumArtistName)
    }

    // ---- navigation ------------------------------------------------------------------

    @Test fun `Go to Artist opens an artist that actually exists`() {
        val featureTrack = ram[1]
        val browse = AppState(
            screenStack = listOf(ScreenEntry(Screen.TrackBrowse(featureTrack.id), selectedIndex = 1)),
            library = LibraryState(tracks = ram)
        )
        val result = AppReducer.reduce(browse, AppAction.Confirm).state
        assertEquals(Screen.ArtistSongs("Daft Punk"), result.currentScreen)
        assertTrue("the artist screen must not be empty", ScreenContent.rows(result).isNotEmpty())
    }

    @Test fun `the Go to Artist row names the artist it opens`() {
        val browse = onScreen(Screen.TrackBrowse(ram[1].id))
        val row = ScreenContent.rows(browse).filterIsInstance<ScreenRow.Action>()
            .single { it.key.startsWith("track_artist:") }
        assertEquals("Daft Punk", row.subtitle)
    }

    @Test fun `Now Playing still shows the full credit`() {
        assertEquals("Daft Punk feat. Pharrell Williams", ram[1].displayArtist)
    }
}
