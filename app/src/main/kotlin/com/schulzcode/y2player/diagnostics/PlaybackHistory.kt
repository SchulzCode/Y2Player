package com.schulzcode.y2player.diagnostics

import com.schulzcode.y2player.core.model.PlaybackExitReason
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.Track
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

internal data class PlaybackSession(
    val track: Track,
    val startedAtUtcMs: Long,
    val endedAtUptimeMs: Long,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val listenedMs: Long,
    val exitReason: PlaybackExitReason,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode
)

internal class PlaybackHistory(
    private val directoryProvider: () -> File?,
    private val appVersion: String,
    private val onWarning: (String) -> Unit,
    private val allDirectoriesProvider: () -> List<File> = {
        listOfNotNull(directoryProvider())
    }
) {
    private var consecutiveFailures = 0

    @Synchronized
    fun append(session: PlaybackSession): Boolean {
        val directory = runCatching { directoryProvider() }.getOrNull() ?: return false
        return runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) return false
            val active = File(directory, ACTIVE_NAME)
            rotateIfNeeded(active)
            FileOutputStream(active, true).bufferedWriter().use { writer ->
                writer.append(encode(session, appVersion))
                // One complete JSON object per line, so a torn final write costs one record.
                writer.append('\n')
            }
            consecutiveFailures = 0
            true
        }.getOrElse { error ->
            // Throttles logging only. Gating the write here is what made a single SD-card
            // removal disable history for the life of the process.
            consecutiveFailures += 1
            if (consecutiveFailures == 1 || consecutiveFailures % LOG_EVERY_N == 0) {
                onWarning(
                    "playback history write failed (${error.javaClass.simpleName}); " +
                        "consecutive=$consecutiveFailures; writes are still being attempted"
                )
            }
            false
        }
    }

    private fun rotateIfNeeded(active: File) {
        if (!active.isFile || active.length() < MAX_ACTIVE_BYTES) return
        val backup = File(active.parentFile, BACKUP_NAME)
        if (backup.exists() && !backup.delete()) return
        active.renameTo(backup)
    }

    @Synchronized
    fun summary(): Summary {
        var sessions = 0
        var bytes = 0L
        directories().forEach { directory ->
            listOf(ACTIVE_NAME, BACKUP_NAME).forEach { name ->
                val file = File(directory, name)
                if (file.isFile) {
                    bytes += file.length()
                    runCatching { file.forEachLine { if (isCompleteRecord(it)) sessions += 1 } }
                }
            }
        }
        return Summary(sessions, bytes)
    }

    @Synchronized
    fun clear(): Boolean {
        var cleared = false
        directories().forEach { directory ->
            listOf(ACTIVE_NAME, BACKUP_NAME).forEach { name ->
                val file = File(directory, name)
                if (file.isFile && file.delete()) cleared = true
            }
        }
        consecutiveFailures = 0
        return cleared
    }

    private fun directories(): List<File> = runCatching { allDirectoriesProvider() }
        .getOrDefault(emptyList())
        .distinctBy { it.absolutePath }

    private fun isCompleteRecord(line: String): Boolean =
        line.length >= 2 && line.first() == '{' && line.last() == '}'

    data class Summary(val sessions: Int = 0, val bytes: Long = 0)

    companion object {
        const val ACTIVE_NAME = "playback-history.ndjson"
        const val BACKUP_NAME = "playback-history.ndjson.1"
        const val SCHEMA_NAME = "y2player.playback-history"
        const val SCHEMA_VERSION = 1

        const val MAX_ACTIVE_BYTES = 512L * 1024L

        private const val RECORD_SIZE_HINT = 512

        private const val LOG_EVERY_N = 20

        const val MIN_SESSION_MS = 5_000L

        const val QUALIFY_MIN_DURATION_MS = 30_000L
        const val QUALIFY_ABSOLUTE_MS = 240_000L
        const val QUALIFY_FRACTION = 0.5

        // Last.fm's scrobble rule: half the track, or four minutes.
        fun qualifies(listenedMs: Long, durationMs: Long): Boolean {
            if (durationMs < QUALIFY_MIN_DURATION_MS) return false
            if (listenedMs >= QUALIFY_ABSOLUTE_MS) return true
            return listenedMs >= (durationMs * QUALIFY_FRACTION).toLong()
        }

        fun playedFraction(listenedMs: Long, durationMs: Long): Double {
            if (durationMs <= 0 || listenedMs <= 0) return 0.0
            return (listenedMs.toDouble() / durationMs).coerceAtMost(1.0)
        }

        fun formatFraction(value: Double): String = String.format(Locale.US, "%.3f", value)

        internal fun encode(session: PlaybackSession, appVersion: String): String {
            val track = session.track
            val builder = StringBuilder(RECORD_SIZE_HINT)
            builder.append('{')
            builder.number("schema_version", SCHEMA_VERSION.toLong())
            builder.text("schema", SCHEMA_NAME)
            builder.text("client", "Y2Player $appVersion")
            builder.text("event", "listen")
            builder.number("timestamp_utc_ms", session.startedAtUtcMs)
            builder.number("ended_at_uptime_ms", session.endedAtUptimeMs)

            builder.text("media_type", if (track.isAudiobookChapter) "audiobook" else "music")
            track.audiobookFolderKey?.let { builder.text("audiobook_key", it) }

            builder.number("track_id", track.id)
            builder.text("volume_id", track.volumeId)
            builder.text("path", track.relativePath)
            builder.text("title", track.title)
            builder.textOrNull("artist", track.artist)
            builder.textOrNull("album", track.album)
            builder.textOrNull("album_artist", track.albumArtist)
            builder.numberOrNull("track_number", track.trackNumber?.toLong())
            builder.numberOrNull("disc_number", track.discNumber?.toLong())

            builder.number("duration_ms", track.durationMs)
            builder.number("listened_ms", session.listenedMs)
            builder.number("start_position_ms", session.startPositionMs)
            builder.number("end_position_ms", session.endPositionMs)
            builder.append("\"played_fraction\":")
                .append(formatFraction(playedFraction(session.listenedMs, track.durationMs)))
                .append(',')
            builder.bool("qualified", qualifies(session.listenedMs, track.durationMs))
            builder.text("exit_reason", session.exitReason.code)

            builder.bool("shuffle_enabled", session.shuffleEnabled)
            builder.text("repeat_mode", session.repeatMode.storageId)

            builder.textOrNull("codec", track.codec)
            builder.textOrNull("container", track.container)
            builder.numberOrNull("sample_rate", track.sampleRate?.toLong())
            builder.numberOrNull("bit_depth", track.bitDepth?.toLong())
            builder.numberOrNull("bitrate", track.bitrate)

            if (builder.last() == ',') builder.setLength(builder.length - 1)
            builder.append('}')
            return builder.toString()
        }

        private fun StringBuilder.text(key: String, value: String) {
            append('"').append(key).append("\":")
            EventJson.escape(value, this)
            append(',')
        }

        private fun StringBuilder.textOrNull(key: String, value: String?) {
            val cleaned = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
            text(key, cleaned)
        }

        private fun StringBuilder.number(key: String, value: Long) {
            append('"').append(key).append("\":").append(value).append(',')
        }

        private fun StringBuilder.numberOrNull(key: String, value: Long?) {
            if (value != null) number(key, value)
        }

        private fun StringBuilder.bool(key: String, value: Boolean) {
            append('"').append(key).append("\":").append(if (value) "true" else "false").append(',')
        }
    }
}
