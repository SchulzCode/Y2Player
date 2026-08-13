package com.schulzcode.y2player.core.model

import java.util.Locale

sealed interface LibraryScope {
    val label: String

    data object All : LibraryScope {
        override val label: String = "Music"
    }

    data class Genre(val key: String, override val label: String) : LibraryScope

    data class Year(val value: Int?) : LibraryScope {
        override val label: String = value?.toString() ?: "Unknown Year"
    }
}

data class AlbumKey(val title: String, val albumArtist: String)

data class AlbumEntry(
    val key: AlbumKey,
    val title: String,
    val albumArtist: String,
    val year: Int?,
    val tracks: List<Track>
)

data class ArtistEntry(val name: String, val tracks: List<Track>)
data class GenreEntry(val key: String, val label: String, val tracks: List<Track>)
data class YearEntry(val year: Int?, val tracks: List<Track>)

/**
 * Immutable, derived organization of the available music library. It keeps metadata
 * normalization and ordering out of screen rendering while retaining Track references
 * instead of duplicating the library's heavier metadata objects.
 */
class LibraryOrganization internal constructor(private val musicTracks: List<Track>) {
    private val genreEntries: List<GenreEntry>
    private val genresByKey: Map<String, GenreEntry>
    private val yearsByValue: Map<Int?, YearEntry>

    init {
        val genres = LinkedHashMap<String, GenreAccumulator>()
        val years = LinkedHashMap<Int?, MutableList<Track>>()
        musicTracks.forEach { track ->
            years.getOrPut(track.year) { ArrayList() }.add(track)
            val values = genreValues(track.genre)
            if (values.isEmpty()) {
                genres.getOrPut(UNKNOWN_GENRE_KEY) { GenreAccumulator("Unknown Genre") }.tracks.add(track)
            } else {
                values.forEach { label ->
                    val key = normalize(label)
                    genres.getOrPut(key) { GenreAccumulator(label) }.tracks.add(track)
                }
            }
        }
        genreEntries = genres.map { (key, value) -> GenreEntry(key, value.label, value.tracks) }
            .sortedWith { first, second -> when {
                first.key == UNKNOWN_GENRE_KEY && second.key == UNKNOWN_GENRE_KEY -> 0
                first.key == UNKNOWN_GENRE_KEY -> 1
                second.key == UNKNOWN_GENRE_KEY -> -1
                else -> compareText(first.label, second.label)
            } }
        genresByKey = genreEntries.associateBy(GenreEntry::key)
        yearsByValue = years.mapValues { (year, tracks) -> YearEntry(year, tracks) }
    }

    fun tracks(scope: LibraryScope): List<Track> = when (scope) {
        LibraryScope.All -> musicTracks
        is LibraryScope.Genre -> genresByKey[scope.key]?.tracks.orEmpty()
        is LibraryScope.Year -> yearsByValue[scope.value]?.tracks.orEmpty()
    }

    fun genres(): List<GenreEntry> = genreEntries

    fun years(order: YearSortOrder): List<YearEntry> {
        val known = yearsByValue.values.filter { it.year != null }.sortedWith { first, second ->
            val comparison = compareValues(first.year, second.year)
            if (order == YearSortOrder.NEWEST_FIRST) -comparison else comparison
        }
        return known + listOfNotNull(yearsByValue[null])
    }

    fun albums(scope: LibraryScope, order: AlbumSortOrder): List<AlbumEntry> =
        albumEntries(tracks(scope)).sortedWith(albumComparator(order))

    fun artists(scope: LibraryScope): List<ArtistEntry> = artistEntries(tracks(scope))

    fun artistAlbums(scope: LibraryScope, artist: String, order: AlbumSortOrder): List<AlbumEntry> =
        albumEntries(tracks(scope).filter { it.isCreditedTo(artist) }).sortedWith(albumComparator(order))

    fun albumTracks(scope: LibraryScope, key: AlbumKey): List<Track> = albumTrackOrder(
        tracks(scope).filter { albumKey(it) == key }
    )

    fun artistTracks(scope: LibraryScope, artist: String, albumOrder: AlbumSortOrder): List<Track> {
        val matching = tracks(scope).filter { it.isCreditedTo(artist) }
        val albumRanks = albumEntries(matching).sortedWith(albumComparator(albumOrder))
            .mapIndexed { index, album -> album.key to index }.toMap()
        return matching.sortedWith { first, second ->
            compareValues(albumRanks[albumKey(first)] ?: Int.MAX_VALUE, albumRanks[albumKey(second)] ?: Int.MAX_VALUE)
                .takeUnless { it == 0 }
                ?: albumTrackComparator(first, second, matching.all { it.trackNumber != null })
        }
    }

    fun sortTracks(tracks: List<Track>, order: TrackSortOrder): List<Track> = tracks.sortedWith { first, second ->
        when (order) {
            TrackSortOrder.TITLE -> compareText(first.title, second.title).takeUnless { it == 0 }
                ?: compareText(first.displayArtist, second.displayArtist).takeUnless { it == 0 }
                ?: compareText(first.displayAlbum, second.displayAlbum)
            TrackSortOrder.ARTIST -> compareText(first.displayArtist, second.displayArtist).takeUnless { it == 0 }
                ?: compareText(first.title, second.title).takeUnless { it == 0 }
                ?: compareText(first.displayAlbum, second.displayAlbum)
            TrackSortOrder.ALBUM -> compareText(first.albumArtistName, second.albumArtistName).takeUnless { it == 0 }
                ?: compareText(first.displayAlbum, second.displayAlbum).takeUnless { it == 0 }
                ?: albumTrackComparator(first, second, numbered = false)
            TrackSortOrder.YEAR -> compareNullableYear(first.year, second.year).takeUnless { it == 0 }
                ?: compareText(first.albumArtistName, second.albumArtistName).takeUnless { it == 0 }
                ?: compareText(first.displayAlbum, second.displayAlbum).takeUnless { it == 0 }
                ?: albumTrackComparator(first, second, numbered = false)
            TrackSortOrder.ADDED -> compareValues(second.addedAt, first.addedAt).takeUnless { it == 0 }
                ?: compareText(first.title, second.title)
            TrackSortOrder.RECENT -> compareValues(second.modifiedAt, first.modifiedAt).takeUnless { it == 0 }
                ?: compareText(first.title, second.title)
        }.takeUnless { it == 0 }
            ?: NaturalTextOrder.compare(first.relativePath, second.relativePath).takeUnless { it == 0 }
            ?: compareValues(first.id, second.id)
    }

    fun albumTrackOrder(tracks: List<Track>): List<Track> {
        val numbered = tracks.all { it.trackNumber != null }
        return tracks.sortedWith { first, second -> albumTrackComparator(first, second, numbered) }
    }

    private fun albumEntries(tracks: List<Track>): List<AlbumEntry> {
        val albums = LinkedHashMap<AlbumKey, AlbumAccumulator>()
        tracks.forEach { track ->
            val key = albumKey(track)
            albums.getOrPut(key) { AlbumAccumulator(track.displayAlbum, track.albumArtistName) }.tracks.add(track)
        }
        return albums.map { (key, value) ->
            AlbumEntry(key, value.title, value.albumArtist, canonicalYear(value.tracks), value.tracks)
        }
    }

    private fun artistEntries(tracks: List<Track>): List<ArtistEntry> {
        val names = LinkedHashMap<String, String>()
        val grouped = LinkedHashMap<String, MutableList<Track>>()
        tracks.forEach { track ->
            track.creditedArtists.forEach { artist ->
                val key = normalize(artist)
                if (!artist.equals("Various Artists", ignoreCase = true)) {
                    if (key !in names) names[key] = artist.trim()
                    grouped.getOrPut(key) { ArrayList() }.add(track)
                }
            }
        }
        return grouped.map { (key, values) -> ArtistEntry(names.getValue(key), values) }
            .sortedWith { first, second -> compareText(first.name, second.name) }
    }

    private fun albumComparator(order: AlbumSortOrder): Comparator<AlbumEntry> = Comparator { first, second ->
        val primary = when (order) {
            AlbumSortOrder.TITLE -> compareText(first.title, second.title)
            AlbumSortOrder.ARTIST -> compareText(first.albumArtist, second.albumArtist)
            AlbumSortOrder.YEAR_ASCENDING -> compareNullableYear(first.year, second.year)
            AlbumSortOrder.YEAR_DESCENDING -> compareNullableYear(first.year, second.year, descending = true)
        }
        primary.takeUnless { it == 0 }
            ?: compareText(first.title, second.title).takeUnless { it == 0 }
            ?: compareText(first.albumArtist, second.albumArtist).takeUnless { it == 0 }
            ?: compareText(first.key.title, second.key.title).takeUnless { it == 0 }
            ?: compareText(first.key.albumArtist, second.key.albumArtist)
    }

    private fun albumTrackComparator(first: Track, second: Track, numbered: Boolean): Int =
        compareValues(first.discNumber ?: 0, second.discNumber ?: 0).takeUnless { it == 0 }
            ?: (if (numbered) compareValues(first.trackNumber, second.trackNumber).takeUnless { it == 0 } else null)
            ?: NaturalTextOrder.compare(first.fileName, second.fileName).takeUnless { it == 0 }
            ?: compareText(first.title, second.title).takeUnless { it == 0 }
            ?: compareValues(first.id, second.id)

    private fun albumKey(track: Track): AlbumKey = AlbumKey(
        normalize(track.displayAlbum),
        normalize(track.albumArtistName)
    )

    private fun canonicalYear(tracks: List<Track>): Int? = tracks.mapNotNull(Track::year)
        .groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
        .firstOrNull()?.key

    private fun genreValues(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val result = LinkedHashMap<String, String>()
        value.split(';').forEach { raw ->
            val label = raw.trim()
            if (label.isNotEmpty()) {
                val key = normalize(label)
                if (key !in result) result[key] = label
            }
        }
        return result.values.toList()
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.US)
    private fun compareText(first: String, second: String): Int = NaturalTextOrder.compare(first, second)

    private fun compareNullableYear(first: Int?, second: Int?, descending: Boolean = false): Int = when {
        first == null && second == null -> 0
        first == null -> 1
        second == null -> -1
        descending -> compareValues(second, first)
        else -> compareValues(first, second)
    }

    private data class GenreAccumulator(val label: String, val tracks: MutableList<Track> = ArrayList())
    private data class AlbumAccumulator(
        val title: String,
        val albumArtist: String,
        val tracks: MutableList<Track> = ArrayList()
    )

    companion object {
        private const val UNKNOWN_GENRE_KEY = ""
    }
}

internal object NaturalTextOrder {
    fun compare(first: String, second: String): Int {
        var i = 0
        var j = 0
        while (i < first.length && j < second.length) {
            val left = first[i]
            val right = second[j]
            if (left.isDigit() && right.isDigit()) {
                val endLeft = digitRunEnd(first, i)
                val endRight = digitRunEnd(second, j)
                compareDigitRuns(first, i, endLeft, second, j, endRight).takeIf { it != 0 }?.let { return it }
                i = endLeft
                j = endRight
            } else {
                val result = left.lowercaseChar().compareTo(right.lowercaseChar())
                if (result != 0) return result
                i++
                j++
            }
        }
        return (first.length - i) - (second.length - j)
    }

    private fun digitRunEnd(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index].isDigit()) index++
        return index
    }

    private fun compareDigitRuns(
        first: String,
        startFirst: Int,
        endFirst: Int,
        second: String,
        startSecond: Int,
        endSecond: Int
    ): Int {
        var left = startFirst
        var right = startSecond
        while (left < endFirst - 1 && first[left] == '0') left++
        while (right < endSecond - 1 && second[right] == '0') right++
        val lengthLeft = endFirst - left
        val lengthRight = endSecond - right
        if (lengthLeft != lengthRight) return lengthLeft - lengthRight
        while (left < endFirst) {
            val result = first[left].compareTo(second[right])
            if (result != 0) return result
            left++
            right++
        }
        return 0
    }
}
