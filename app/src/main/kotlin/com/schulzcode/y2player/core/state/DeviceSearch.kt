package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AlbumKey
import com.schulzcode.y2player.core.model.LibraryScope
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.NaturalTextOrder
import com.schulzcode.y2player.core.model.Track
import java.text.Normalizer
import java.util.Locale

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

internal object DeviceSearch {
    private const val MAX_RESULTS = 100
    private val DIACRITICS = Regex("\\p{M}+")
    private val WHITESPACE = Regex("\\s+")

    fun find(library: LibraryState, rawQuery: String): List<DeviceSearchResult> {
        val query = normalized(rawQuery)
        if (query.isEmpty()) return emptyList()
        val tokens = query.split(' ').filter(String::isNotEmpty)
        val results = ArrayList<DeviceSearchResult>()

        library.availableTracks.forEach { track ->
            score(tokens, track.title, track.displayArtist, track.displayAlbum, track.fileName, track.genre, track.composer)
                ?.let { results += DeviceSearchResult.TrackResult(track, it) }
        }
        library.index.organization.albums(LibraryScope.All, com.schulzcode.y2player.core.model.AlbumSortOrder.TITLE)
            .forEach { album ->
                score(tokens, album.title, album.albumArtist)?.let {
                    results += DeviceSearchResult.AlbumResult(album.key, album.albumArtist, album.tracks.firstOrNull()?.id, it)
                }
            }
        library.index.organization.artists(LibraryScope.All).forEach { artist ->
            score(tokens, artist.name)?.let {
                results += DeviceSearchResult.ArtistResult(artist.name, artist.tracks.firstOrNull()?.id, it)
            }
        }
        library.playlists.forEach { playlist ->
            score(tokens, playlist.name)?.let {
                results += DeviceSearchResult.PlaylistResult(playlist.id, playlist.name, playlist.trackCount, it)
            }
        }
        library.availableTracks.asSequence().filter(Track::isAudiobookChapter)
            .groupBy { it.audiobookFolderKey.orEmpty() }.forEach { (folderKey, tracks) ->
                val title = tracks.first().relativePath.substringBeforeLast('/', "")
                    .substringAfterLast('/').ifBlank { tracks.first().displayAlbum }
                score(tokens, title, tracks.first().displayArtist, tracks.first().displayAlbum)?.let {
                    results += DeviceSearchResult.AudiobookResult(folderKey, title, tracks.firstOrNull()?.id, it)
                }
            }

        return results.sortedWith { first, second ->
            first.score.compareTo(second.score)
                .takeIf { it != 0 }
                ?: typeOrder(first).compareTo(typeOrder(second)).takeIf { it != 0 }
                ?: NaturalTextOrder.compare(first.title, second.title)
        }.take(MAX_RESULTS)
    }

    private fun score(tokens: List<String>, vararg fields: String?): Int? {
        val values = fields.mapNotNull { it?.takeIf(String::isNotBlank)?.let(::normalized) }
        if (values.isEmpty() || tokens.any { token -> values.none { token in it } }) return null
        return tokens.sumOf { token ->
            values.minOf { value -> when {
                value == token -> 0
                value.startsWith(token) -> 1
                value.split(' ').any { it.startsWith(token) } -> 2
                else -> 3
            } }
        }
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
