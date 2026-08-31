package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AlbumKey
import com.schulzcode.y2player.core.model.AlbumSortOrder
import com.schulzcode.y2player.core.model.LibraryScope
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.NaturalTextOrder
import com.schulzcode.y2player.core.model.Track
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.Locale
import java.util.PriorityQueue

internal sealed interface DeviceSearchResult {
    val title: String
    val score: Int

    data class TrackResult(val track: Track, override val score: Int) : DeviceSearchResult {
        override val title: String = track.title
    }
    data class AlbumResult(val key: AlbumKey, val artist: String, val artworkTrackId: Long?, override val score: Int) : DeviceSearchResult {
        override val title: String = key.title
    }
    data class ArtistResult(override val title: String, val artworkTrackId: Long?, override val score: Int) : DeviceSearchResult
    data class PlaylistResult(val id: Long, override val title: String, val trackCount: Int, override val score: Int) : DeviceSearchResult
    data class AudiobookResult(val folderKey: String, override val title: String, val artworkTrackId: Long?, override val score: Int) : DeviceSearchResult
}

/** Normalizes and groups metadata once per library, then caches recent query results. */
internal object DeviceSearch {
    private const val MAX_RESULTS = 100
    private const val QUERY_CACHE_SIZE = 32
    private val DIACRITICS = Regex("\\p{M}+")
    private val WHITESPACE = Regex("\\s+")

    private val resultOrder = Comparator<DeviceSearchResult> { first, second ->
        first.score.compareTo(second.score)
            .takeIf { it != 0 }
            ?: typeOrder(first).compareTo(typeOrder(second)).takeIf { it != 0 }
            ?: NaturalTextOrder.compare(first.title, second.title)
    }
    private val rankedOrder = Comparator<RankedResult> { first, second ->
        resultOrder.compare(first.result, second.result).takeIf { it != 0 }
            ?: first.ordinal.compareTo(second.ordinal)
    }
    private val worstFirstOrder = Comparator<RankedResult> { first, second ->
        rankedOrder.compare(second, first)
    }

    private var activeIndex: SearchIndex? = null
    private var indexBuildCount = 0

    @Synchronized
    fun find(library: LibraryState, rawQuery: String): List<DeviceSearchResult> {
        val query = normalized(rawQuery)
        if (query.isEmpty()) return emptyList()
        val index = activeIndex?.takeIf { it.matches(library) } ?: SearchIndex(library).also {
            activeIndex = it
            indexBuildCount++
        }
        return index.results(query)
    }

    internal fun clearCacheForTests() {
        activeIndex = null
        indexBuildCount = 0
    }

    internal fun indexBuildCountForTests(): Int = indexBuildCount

    private class SearchIndex(library: LibraryState) {
        private val libraryIndex = library.index
        private val playlists = library.playlists
        private val entries = buildEntries(library)
        private val queryCache = object : LinkedHashMap<String, List<DeviceSearchResult>>(QUERY_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<DeviceSearchResult>>?): Boolean =
                size > QUERY_CACHE_SIZE
        }

        fun matches(library: LibraryState): Boolean =
            library.index === libraryIndex && library.playlists === playlists

        fun results(query: String): List<DeviceSearchResult> = queryCache[query] ?: search(query).also {
            queryCache[query] = it
        }

        private fun search(query: String): List<DeviceSearchResult> {
            val tokens = query.split(' ').filter(String::isNotEmpty)
            val best = PriorityQueue<RankedResult>(MAX_RESULTS, worstFirstOrder)
            entries.forEachIndexed { ordinal, entry ->
                val score = score(tokens, entry.fields) ?: return@forEachIndexed
                val result = RankedResult(entry.result(score), ordinal)
                if (best.size < MAX_RESULTS) {
                    best.add(result)
                } else if (rankedOrder.compare(result, best.peek()) < 0) {
                    best.poll()
                    best.add(result)
                }
            }
            return best.toMutableList().apply { sortWith(rankedOrder) }.map(RankedResult::result)
        }
    }

    private data class RankedResult(val result: DeviceSearchResult, val ordinal: Int)

    private class IndexedEntry(
        val fields: Array<String>,
        val result: (Int) -> DeviceSearchResult
    )

    private fun buildEntries(library: LibraryState): List<IndexedEntry> = buildList {
        library.availableTracks.forEach { track ->
            add(entry(track.title, track.displayArtist, track.displayAlbum, track.fileName, track.genre, track.composer) {
                DeviceSearchResult.TrackResult(track, it)
            })
        }
        library.index.organization.albums(LibraryScope.All, AlbumSortOrder.TITLE).forEach { album ->
            add(entry(album.title, album.albumArtist) {
                DeviceSearchResult.AlbumResult(album.key, album.albumArtist, album.tracks.firstOrNull()?.id, it)
            })
        }
        library.index.organization.artists(LibraryScope.All).forEach { artist ->
            add(entry(artist.name) {
                DeviceSearchResult.ArtistResult(artist.name, artist.tracks.firstOrNull()?.id, it)
            })
        }
        library.playlists.forEach { playlist ->
            add(entry(playlist.name) {
                DeviceSearchResult.PlaylistResult(playlist.id, playlist.name, playlist.trackCount, it)
            })
        }
        val audiobookFolders = LinkedHashMap<String, MutableList<Track>>()
        library.availableTracks.forEach { track ->
            if (track.isAudiobookChapter) audiobookFolders.getOrPut(track.audiobookFolderKey.orEmpty(), ::ArrayList).add(track)
        }
        audiobookFolders.forEach { (folderKey, tracks) ->
            val first = tracks.first()
            val title = first.relativePath.substringBeforeLast('/', "")
                .substringAfterLast('/').ifBlank { first.displayAlbum }
            add(entry(title, first.displayArtist, first.displayAlbum) {
                DeviceSearchResult.AudiobookResult(folderKey, title, first.id, it)
            })
        }
    }

    private fun entry(vararg fields: String?, result: (Int) -> DeviceSearchResult): IndexedEntry = IndexedEntry(
        fields.mapNotNull { it?.takeIf(String::isNotBlank)?.let(::normalized) }.toTypedArray(), result
    )

    private fun score(tokens: List<String>, fields: Array<String>): Int? {
        if (fields.isEmpty()) return null
        var total = 0
        tokens.forEach { token ->
            var best = Int.MAX_VALUE
            fields.forEach { field ->
                val candidate = fieldScore(field, token)
                if (candidate < best) best = candidate
            }
            if (best == Int.MAX_VALUE) return null
            total += best
        }
        return total
    }

    private fun fieldScore(field: String, token: String): Int {
        if (field == token) return 0
        if (field.startsWith(token)) return 1
        var match = field.indexOf(token)
        while (match >= 0) {
            if (match > 0 && field[match - 1] == ' ') return 2
            match = field.indexOf(token, match + 1)
        }
        return if (token in field) 3 else Int.MAX_VALUE
    }

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(Locale.ROOT)
        .trim()
        .replace(WHITESPACE, " ")

    private fun typeOrder(result: DeviceSearchResult): Int = when (result) {
        is DeviceSearchResult.TrackResult -> 0
        is DeviceSearchResult.AlbumResult -> 1
        is DeviceSearchResult.ArtistResult -> 2
        is DeviceSearchResult.PlaylistResult -> 3
        is DeviceSearchResult.AudiobookResult -> 4
    }
}
