package com.schulzcode.y2player.backup

import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.queue.PersistedPlaybackSession
import java.util.Locale

data class PortableMediaIdentity(val volumeId: String, val relativePath: String) {
    val key: String get() = "${normalizeVolumeId(volumeId)}|${normalizeRelativePath(relativePath).lowercase(Locale.US)}"

    companion object {
        fun from(track: Track) = PortableMediaIdentity(track.volumeId, normalizeRelativePath(track.relativePath))

        fun normalizeVolumeId(value: String): String {
            val cleaned = value.trim().lowercase(Locale.US)
            require(cleaned.isNotEmpty() && cleaned.length <= 64) { "Invalid media volume" }
            require(cleaned.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
                "Invalid media volume"
            }
            return when (cleaned) {
                "sdcard0", "emulated", "emulated0" -> "internal"
                "sdcard1", "sdcard2", "extsdcard", "external" -> "sdcard"
                else -> cleaned
            }
        }

        fun normalizeRelativePath(value: String): String {
            require(value.length <= MAX_PATH_CHARS && '\u0000' !in value) { "Invalid media path" }
            val segments = value.replace('\\', '/').split('/')
                .filter { it.isNotEmpty() && it != "." }
            require(segments.isNotEmpty() && segments.none { it == ".." }) { "Invalid media path" }
            return segments.joinToString("/")
        }

        private const val MAX_PATH_CHARS = 4_096
    }
}

data class PortablePlaylist(val name: String, val tracks: List<PortableMediaIdentity>)

data class PortableAudiobookProgress(
    val track: PortableMediaIdentity,
    val positionMs: Long,
    val updatedAtUtcMs: Long
)

data class PortableRecentTrack(
    val track: PortableMediaIdentity,
    val lastPlayedUtcMs: Long,
    val playCount: Int
)

data class PortableQueue(
    val tracks: List<PortableMediaIdentity>,
    val currentIndex: Int?,
    val positionMs: Long,
    val repeatMode: String,
    val shuffleEnabled: Boolean,
    val shuffleSeed: Long,
    val playOrder: List<Int>?
)

data class PortableUserData(
    val favorites: List<PortableMediaIdentity> = emptyList(),
    val playlists: List<PortablePlaylist> = emptyList(),
    val audiobookProgress: List<PortableAudiobookProgress> = emptyList(),
    val recentlyPlayed: List<PortableRecentTrack> = emptyList(),
    val queue: PortableQueue? = null
)

data class ResolvedPlaylist(val name: String, val trackIds: List<Long>)
data class ResolvedAudiobookProgress(val folderKey: String, val trackId: Long, val positionMs: Long, val updatedAtUtcMs: Long)
data class ResolvedRecentTrack(val trackId: Long, val lastPlayedUtcMs: Long, val playCount: Int)

data class ResolvedUserData(
    val favoriteTrackIds: List<Long>,
    val playlists: List<ResolvedPlaylist>,
    val audiobookProgress: List<ResolvedAudiobookProgress>,
    val recentlyPlayed: List<ResolvedRecentTrack>,
    val queueTrackIds: List<Long>,
    val playbackSession: PersistedPlaybackSession?,
    val restoredReferences: Int,
    val unresolvedReferences: Int
)

object PortableUserDataResolver {
    fun resolve(source: PortableUserData, tracks: List<Track>): ResolvedUserData {
        validate(source)
        val tracksByKey = tracks.associateBy { PortableMediaIdentity.from(it).key }
        var restored = 0
        var unresolved = 0

        fun resolve(identity: PortableMediaIdentity): Track? {
            val track = tracksByKey[identity.key]
            if (track == null) unresolved += 1 else restored += 1
            return track
        }

        val favorites = source.favorites.mapNotNull(::resolve).map(Track::id).distinct()
        val playlists = source.playlists.map { playlist ->
            ResolvedPlaylist(playlist.name, playlist.tracks.mapNotNull(::resolve).map(Track::id).distinct())
        }
        val audiobook = source.audiobookProgress.mapNotNull { progress ->
            val track = resolve(progress.track) ?: return@mapNotNull null
            val folderKey = track.audiobookFolderKey
            if (folderKey == null) {
                restored -= 1
                unresolved += 1
                return@mapNotNull null
            }
            ResolvedAudiobookProgress(
                folderKey,
                track.id,
                progress.positionMs,
                progress.updatedAtUtcMs
            )
        }
        val recent = source.recentlyPlayed.mapNotNull { item ->
            resolve(item.track)?.let { ResolvedRecentTrack(it.id, item.lastPlayedUtcMs, item.playCount) }
        }.distinctBy(ResolvedRecentTrack::trackId)

        val oldToNew = HashMap<Int, Int>()
        val queueIds = ArrayList<Long>()
        source.queue?.tracks?.forEachIndexed { oldIndex, identity ->
            resolve(identity)?.let { track ->
                oldToNew[oldIndex] = queueIds.size
                queueIds += track.id
            }
        }
        val queue = source.queue
        val currentIndex = queue?.currentIndex?.let(oldToNew::get)
        val playOrder = queue?.playOrder?.mapNotNull(oldToNew::get)
            ?.takeIf { it.size == queueIds.size && it.toSet().size == queueIds.size }
        val session = queue?.let {
            PersistedPlaybackSession(
                currentIndex = currentIndex,
                positionMs = if (currentIndex == null) 0 else it.positionMs,
                repeatMode = RepeatMode.fromStorage(it.repeatMode),
                shuffleEnabled = it.shuffleEnabled,
                shuffleSeed = it.shuffleSeed,
                playOrder = playOrder
            )
        }

        return ResolvedUserData(
            favoriteTrackIds = favorites,
            playlists = playlists,
            audiobookProgress = audiobook,
            recentlyPlayed = recent,
            queueTrackIds = queueIds,
            playbackSession = session,
            restoredReferences = restored,
            unresolvedReferences = unresolved
        )
    }

    fun validate(source: PortableUserData) {
        require(source.favorites.size <= MAX_FAVORITES) { "Too many favorites" }
        require(source.playlists.size <= MAX_PLAYLISTS) { "Too many playlists" }
        require(source.audiobookProgress.size <= MAX_PROGRESS) { "Too many audiobook positions" }
        require(source.recentlyPlayed.size <= MAX_RECENT) { "Too much recent history" }
        source.favorites.forEach(::validateIdentity)
        val names = HashSet<String>()
        source.playlists.forEach { playlist ->
            require(playlist.name.isNotBlank() && playlist.name.length <= MAX_PLAYLIST_NAME_CHARS && '\u0000' !in playlist.name) {
                "Invalid playlist name"
            }
            require(names.add(playlist.name.lowercase(Locale.US))) { "Duplicate playlist name" }
            require(playlist.tracks.size <= MAX_PLAYLIST_TRACKS) { "Playlist is too large" }
            playlist.tracks.forEach(::validateIdentity)
        }
        source.audiobookProgress.forEach {
            validateIdentity(it.track)
            require(it.positionMs >= 0 && it.updatedAtUtcMs >= 0) { "Invalid audiobook progress" }
        }
        source.recentlyPlayed.forEach {
            validateIdentity(it.track)
            require(it.lastPlayedUtcMs >= 0 && it.playCount in 1..MAX_PLAY_COUNT) { "Invalid recent history" }
        }
        source.queue?.let { queue ->
            require(queue.tracks.size <= MAX_QUEUE_TRACKS) { "Queue is too large" }
            queue.tracks.forEach(::validateIdentity)
            require(queue.currentIndex == null || queue.currentIndex in queue.tracks.indices) { "Invalid queue index" }
            require(queue.positionMs >= 0) { "Invalid queue position" }
            require(RepeatMode.values().any { it.storageId == queue.repeatMode }) { "Invalid repeat mode" }
            queue.playOrder?.let { order ->
                require(order.size == queue.tracks.size && order.toSet() == queue.tracks.indices.toSet()) {
                    "Invalid shuffle order"
                }
            }
        }
    }

    private fun validateIdentity(identity: PortableMediaIdentity) {
        PortableMediaIdentity.normalizeVolumeId(identity.volumeId)
        PortableMediaIdentity.normalizeRelativePath(identity.relativePath)
    }

    const val MAX_FAVORITES = 100_000
    const val MAX_PLAYLISTS = 2_000
    const val MAX_PLAYLIST_TRACKS = 100_000
    const val MAX_PROGRESS = 20_000
    const val MAX_RECENT = 100_000
    const val MAX_QUEUE_TRACKS = 50_000
    private const val MAX_PLAYLIST_NAME_CHARS = 256
    private const val MAX_PLAY_COUNT = 1_000_000_000
}
