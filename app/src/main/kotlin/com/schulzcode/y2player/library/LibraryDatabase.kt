package com.schulzcode.y2player.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.schulzcode.y2player.backup.PortableAudiobookProgress
import com.schulzcode.y2player.backup.PortableMediaIdentity
import com.schulzcode.y2player.backup.PortablePlaylist
import com.schulzcode.y2player.backup.PortableQueue
import com.schulzcode.y2player.backup.PortableRecentTrack
import com.schulzcode.y2player.backup.PortableUserData
import com.schulzcode.y2player.backup.ResolvedUserData
import com.schulzcode.y2player.core.model.AudiobookProgress
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.QueueEntry
import com.schulzcode.y2player.core.model.QueueOrigin
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.core.model.TrackDraft
import com.schulzcode.y2player.queue.PersistedPlaybackSession
import com.schulzcode.y2player.queue.QueueController

internal object DecoderBackendMigration {
    const val VERSION = 9
    val RESET_STATEMENTS = listOf(
        "UPDATE tracks SET playback_error = NULL WHERE playback_error IS NOT NULL",
        "DELETE FROM format_probe"
    )
}

internal object LibrarySchema {
    const val VERSION = 15
}

internal object QueueModelMigration {
    const val VERSION = 15
    val STATEMENTS = listOf(
        "ALTER TABLE queue_items ADD COLUMN entry_id INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE queue_items ADD COLUMN origin TEXT NOT NULL DEFAULT 'continuation'",
        "ALTER TABLE queue_items ADD COLUMN source_order INTEGER",
        "UPDATE queue_items SET entry_id = position + 1, source_order = position",
        "ALTER TABLE playback_session ADD COLUMN current_entry_id INTEGER"
    )
}

// Additive only. Invalidating track metadata here would trigger a full rescan.
internal object AudiobookProgressMigration {
    const val VERSION = 14
    val STATEMENTS = listOf(AudiobookProgressTable.CREATE)
}

internal object AudiobookProgressTable {
    const val NAME = "audiobook_progress"

    const val CREATE = "CREATE TABLE IF NOT EXISTS $NAME (" +
        "folder_key TEXT PRIMARY KEY, " +
        "track_id INTEGER NOT NULL, " +
        "position_ms INTEGER NOT NULL, " +
        "updated_at INTEGER NOT NULL)"
}

internal object FfmpegMetadataMigration {
    const val VERSION = 11
    val STATEMENTS = listOf(
        "ALTER TABLE tracks ADD COLUMN composer TEXT",
        "ALTER TABLE tracks ADD COLUMN genre TEXT",
        "ALTER TABLE tracks ADD COLUMN date TEXT",
        "ALTER TABLE tracks ADD COLUMN year INTEGER",
        "ALTER TABLE tracks ADD COLUMN bitrate INTEGER",
        "ALTER TABLE tracks ADD COLUMN container TEXT",
        "ALTER TABLE tracks ADD COLUMN has_artwork INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE tracks ADD COLUMN replaygain_track_db REAL",
        "ALTER TABLE tracks ADD COLUMN replaygain_track_peak REAL",
        "ALTER TABLE tracks ADD COLUMN replaygain_album_db REAL",
        "ALTER TABLE tracks ADD COLUMN replaygain_album_peak REAL",
        "UPDATE tracks SET modified_at = -1"
    )
}

internal object MetadataCompletenessMigration {
    const val VERSION = 13
    val STATEMENTS = listOf(
        "ALTER TABLE tracks ADD COLUMN track_total INTEGER",
        "ALTER TABLE tracks ADD COLUMN disc_total INTEGER",
        "ALTER TABLE tracks ADD COLUMN comment TEXT",
        "UPDATE tracks SET modified_at = -1"
    )
}

class LibraryDatabase(private val appContext: Context) : SQLiteOpenHelper(
    appContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
    BackupCorruptionHandler(appContext.applicationContext)
) {
    private var writeAheadLoggingRequested = false

    init {
        if (appContext.getDatabasePath(DATABASE_NAME).isFile) {
            requestWriteAheadLogging()
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createTracks(db)
        createPlayback(db)
        createDiagnostics(db)
        createUserLibrary(db)
    }

    fun ensureOpen() {
        writableDatabase
        requestWriteAheadLogging()
    }

    @Synchronized
    // Without WAL, MediaTek's API-19 reader pool reproducibly hands a reader a
    // connection still inside the scanner's write transaction.
    private fun requestWriteAheadLogging() {
        if (writeAheadLoggingRequested) return
        setWriteAheadLoggingEnabled(true)
        writeAheadLoggingRequested = true
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        backupDatabase(appContext, db.path, "pre-migration-v$oldVersion-to-v$newVersion")
        var version = oldVersion
        db.transaction {
            if (version < 2) {
                execSQL("ALTER TABLE tracks ADD COLUMN available INTEGER NOT NULL DEFAULT 1")
                execSQL("ALTER TABLE tracks ADD COLUMN scan_error TEXT")
                execSQL("ALTER TABLE tracks ADD COLUMN codec TEXT")
                execSQL("ALTER TABLE tracks ADD COLUMN sample_rate INTEGER")
                execSQL("ALTER TABLE tracks ADD COLUMN bit_depth INTEGER")
                execSQL("ALTER TABLE tracks ADD COLUMN channels INTEGER")
                execSQL("ALTER TABLE tracks ADD COLUMN added_at INTEGER NOT NULL DEFAULT 0")
                execSQL("ALTER TABLE tracks ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                execSQL("UPDATE tracks SET added_at = modified_at WHERE added_at = 0")
                createDiagnostics(this)
                version = 2
            }
            if (version < 3) {
                createUserLibrary(this)
                version = 3
            }
            if (version < 4) {
                execSQL(
                    "CREATE TABLE IF NOT EXISTS format_probe (extension TEXT PRIMARY KEY, success INTEGER NOT NULL, message TEXT NOT NULL, tested_at INTEGER NOT NULL)"
                )
                version = 4
            }
            if (version < 5) {
                execSQL("ALTER TABLE playback_session ADD COLUMN play_order TEXT")
                version = 5
            }
            if (version < 6) {
                execSQL("DROP INDEX IF EXISTS tracks_artist_idx")
                execSQL("DROP INDEX IF EXISTS tracks_album_idx")
                execSQL("DROP INDEX IF EXISTS tracks_volume_path_idx")
                execSQL("DROP INDEX IF EXISTS tracks_available_idx")
                createTrackIndexes(this)
                version = 6
            }
            if (version < 7) {
                if (!hasColumn(this, "playlists", "source_path")) {
                    execSQL("ALTER TABLE playlists ADD COLUMN source_path TEXT")
                }
                execSQL("CREATE UNIQUE INDEX IF NOT EXISTS playlists_source_path_idx ON playlists(source_path)")
                version = 7
            }
            if (version < 8) {
                execSQL("ALTER TABLE tracks ADD COLUMN playback_error TEXT")
                version = 8
            }
            if (version < 9) {
                DecoderBackendMigration.RESET_STATEMENTS.forEach(::execSQL)
                version = DecoderBackendMigration.VERSION
            }
            if (version < 10) {
                execSQL("DROP TABLE IF EXISTS format_probe")
                version = 10
            }
            if (version < 11) {
                FfmpegMetadataMigration.STATEMENTS.forEach(::execSQL)
                version = FfmpegMetadataMigration.VERSION
            }
            if (version < 12) {
                createVolumeRelativePathIndex(this)
                version = 12
            }
            if (version < 13) {
                MetadataCompletenessMigration.STATEMENTS.forEach(::execSQL)
                version = MetadataCompletenessMigration.VERSION
            }
            if (version < 14) {
                AudiobookProgressMigration.STATEMENTS.forEach(::execSQL)
                version = AudiobookProgressMigration.VERSION
            }
            if (version < 15) {
                QueueModelMigration.STATEMENTS.forEach(::execSQL)
                version = QueueModelMigration.VERSION
            }
            if (version != newVersion) error("No migration exists from $oldVersion to $newVersion")
        }
    }

    fun loadTracks(): List<Track> {
        val stringPool = HashMap<String, String>(256)
        return readableDatabase.query(
            "tracks", TRACK_COLUMNS, "available = 1", null, null, null, "title COLLATE NOCASE"
        ).use { cursor ->
            val columns = TrackColumns(cursor)
            buildList { while (cursor.moveToNext()) cursor.toTrack(columns, stringPool)?.let(::add) }
        }
    }

    fun countTracks(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM tracks", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    fun findTrack(id: Long): Track? = readableDatabase.query(
        "tracks", TRACK_COLUMNS, "id = ?", arrayOf(id.toString()), null, null, null, "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toTrack(TrackColumns(cursor), null) else null }

    fun loadTrackFingerprints(volumeId: String, absolutePaths: List<String>): Map<String, TrackFingerprint> {
        if (absolutePaths.isEmpty()) return emptyMap()
        val placeholders = absolutePaths.joinToString(",") { "?" }
        val args = arrayOf(volumeId, *absolutePaths.toTypedArray())
        return readableDatabase.rawQuery(
            "SELECT absolute_path, file_size, modified_at FROM tracks WHERE volume_id = ? AND absolute_path IN ($placeholders)",
            args
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), TrackFingerprint(cursor.getLong(1), cursor.getLong(2)))
            }
        }
    }

    fun applyScanBatch(
        volumeId: String,
        scanToken: Long,
        files: List<ScannedFile>,
        profiler: ScanProfiler? = null
    ) {
        if (files.isEmpty()) return
        val database = writableDatabase
        val beginStarted = profiler?.start() ?: 0L
        database.beginTransaction()
        profiler?.stop(ScanPhase.DATABASE_BEGIN, beginStarted)
        try {
            val unchanged = ArrayList<String>(files.size)
            files.forEach { scanned ->
                val draft = scanned.changedDraft
                if (draft == null) {
                    unchanged += scanned.absolutePath
                } else {
                    var statementStarted = profiler?.start() ?: 0L
                    var updated = database.update("tracks", draft.toValues(scanToken, includeAddedAt = false), "absolute_path = ?", arrayOf(draft.absolutePath))
                    profiler?.stop(ScanPhase.DATABASE_ABSOLUTE_UPDATE, statementStarted)
                    if (updated == 0) {
                        statementStarted = profiler?.start() ?: 0L
                        updated = database.update(
                            "tracks",
                            draft.toValues(scanToken, includeAddedAt = false),
                            "volume_id = ? AND relative_path = ? COLLATE NOCASE",
                            arrayOf(draft.volumeId, draft.relativePath)
                        )
                        profiler?.stop(ScanPhase.DATABASE_RELATIVE_UPDATE, statementStarted)
                    }
                    if (updated == 0) {
                        statementStarted = profiler?.start() ?: 0L
                        database.insertOrThrow("tracks", null, draft.toValues(scanToken, includeAddedAt = true))
                        profiler?.stop(ScanPhase.DATABASE_INSERT, statementStarted)
                    }
                }
            }
            unchanged.chunked(SEEN_UPDATE_CHUNK).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val args = arrayOfNulls<Any>(chunk.size + 2)
                args[0] = scanToken
                args[1] = volumeId
                chunk.forEachIndexed { index, path -> args[index + 2] = path }
                val statementStarted = profiler?.start() ?: 0L
                database.execSQL(
                    "UPDATE tracks SET last_seen_scan = ?, available = 1 WHERE volume_id = ? AND absolute_path IN ($placeholders)",
                    args
                )
                profiler?.stop(ScanPhase.DATABASE_UNCHANGED_UPDATE, statementStarted)
            }
            database.setTransactionSuccessful()
        } finally {
            val commitStarted = profiler?.start() ?: 0L
            database.endTransaction()
            profiler?.stop(ScanPhase.DATABASE_COMMIT, commitStarted)
        }
    }

    fun finishScan(volumeId: String, scanToken: Long) {
        writableDatabase.update(
            "tracks",
            ContentValues().apply { put("available", 0) },
            "volume_id = ? AND last_seen_scan <> ?",
            arrayOf(volumeId, scanToken.toString())
        )
    }

    fun markVolumeUnavailable(volumeId: String) {
        writableDatabase.update(
            "tracks",
            ContentValues().apply { put("available", 0) },
            "volume_id = ?",
            arrayOf(volumeId)
        )
    }

    fun setPlaybackError(trackId: Long, reason: String?) {
        writableDatabase.update(
            "tracks",
            ContentValues().apply { putNullable("playback_error", reason) },
            "id = ?",
            arrayOf(trackId.toString())
        )
    }

    fun setFavorite(trackId: Long, favorite: Boolean) {
        writableDatabase.update(
            "tracks",
            ContentValues().apply { put("favorite", if (favorite) 1 else 0) },
            "id = ?",
            arrayOf(trackId.toString())
        )
    }

    fun saveQueueState(entries: List<QueueEntry>, session: PersistedPlaybackSession) {
        writableDatabase.transaction {
            replaceQueueRows(this, entries)
            savePlaybackSessionRow(this, session)
        }
    }

    fun loadQueue(): List<QueueEntry> = readableDatabase.query(
        "queue_items", arrayOf("entry_id", "track_id", "origin", "source_order"),
        null, null, null, null, "position", QueueController.MAX_QUEUE_ITEMS.toString()
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(QueueEntry(
            id = cursor.getLong(0),
            trackId = cursor.getLong(1),
            origin = QueueOrigin.fromStorage(cursor.getString(2)),
            sourceOrder = if (cursor.isNull(3)) null else cursor.getInt(3)
        ))
    } }

    fun validTrackIds(trackIds: Collection<Long>): Set<Long> {
        if (trackIds.isEmpty()) return emptySet()
        val result = HashSet<Long>()
        trackIds.asSequence().filter { it > 0 }.distinct().chunked(QUERY_ID_BATCH).forEach { batch ->
            val placeholders = batch.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                """
                SELECT id FROM tracks
                WHERE id IN ($placeholders)
                  AND absolute_path IS NOT NULL AND LENGTH(TRIM(absolute_path)) > 0
                  AND relative_path IS NOT NULL AND LENGTH(TRIM(relative_path)) > 0
                  AND duration_ms >= 0 AND file_size >= 0
                """.trimIndent(),
                batch.map(Long::toString).toTypedArray()
            ).use { cursor -> while (cursor.moveToNext()) result += cursor.getLong(0) }
        }
        return result
    }

    fun savePlaybackSession(session: PersistedPlaybackSession) {
        savePlaybackSessionRow(writableDatabase, session)
    }

    fun updatePlaybackPosition(positionMs: Long) {
        writableDatabase.update(
            "playback_session",
            ContentValues().apply { put("position_ms", positionMs.coerceAtLeast(0)) },
            "id = 1",
            null
        )
    }

    fun loadPlaybackSession(): PersistedPlaybackSession? = readableDatabase.query(
        "playback_session",
        arrayOf("current_entry_id", "current_index", "position_ms", "repeat_mode", "shuffle_enabled", "shuffle_seed", "play_order"),
        "id = 1", null, null, null, null
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        PersistedPlaybackSession(
            currentEntryId = if (cursor.isNull(0)) null else cursor.getLong(0),
            legacyCurrentIndex = if (cursor.isNull(1)) null else cursor.getInt(1),
            positionMs = cursor.getLong(2).coerceAtLeast(0),
            repeatMode = RepeatMode.fromStorage(cursor.getString(3)),
            shuffleEnabled = cursor.getInt(4) != 0,
            shuffleSeed = cursor.getLong(5),
            legacyPlayOrder = if (cursor.isNull(6)) null else decodePlayOrder(cursor.getString(6))
        )
    }

    fun createPlaylist(): PlaylistSummary {
        val existingNames = readableDatabase.query(
            "playlists", arrayOf("name"), null, null, null, null, null
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        var number = 1
        while ("Playlist $number" in existingNames) number += 1
        val name = "Playlist $number"
        val id = writableDatabase.insertOrThrow("playlists", null, ContentValues().apply {
            put("name", name)
            put("created_at", System.currentTimeMillis())
        })
        return PlaylistSummary(id, name, 0)
    }

    fun loadPlaylists(): List<PlaylistSummary> = readableDatabase.rawQuery(
        """
        SELECT p.id, p.name, COUNT(pt.track_id)
        FROM playlists p
        LEFT JOIN playlist_tracks pt ON pt.playlist_id = p.id
        GROUP BY p.id, p.name
        ORDER BY p.created_at, p.id
        """.trimIndent(), null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(PlaylistSummary(cursor.getLong(0), cursor.getString(1), cursor.getInt(2)))
        }
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        writableDatabase.transaction {
            val position = rawQuery(
                "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = ?",
                arrayOf(playlistId.toString())
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            insertWithOnConflict(
                "playlist_tracks",
                null,
                ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("position", position)
                    put("track_id", trackId)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        writableDatabase.delete(
            "playlist_tracks",
            "playlist_id = ? AND track_id = ?",
            arrayOf(playlistId.toString(), trackId.toString())
        )
        compactPlaylist(playlistId)
    }

    fun deletePlaylist(playlistId: Long) {
        writableDatabase.delete("playlists", "id = ?", arrayOf(playlistId.toString()))
    }

    fun loadPlaylistTrackIds(playlistId: Long): List<Long> = readableDatabase.query(
        "playlist_tracks", arrayOf("track_id"), "playlist_id = ?", arrayOf(playlistId.toString()),
        null, null, "position"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    fun loadAllPlaylistTrackIds(): Map<Long, List<Long>> = readableDatabase.query(
        "playlist_tracks", arrayOf("playlist_id", "track_id"), null, null, null, null, "playlist_id, position"
    ).use { cursor ->
        val result = LinkedHashMap<Long, MutableList<Long>>()
        while (cursor.moveToNext()) result.getOrPut(cursor.getLong(0)) { ArrayList() }.add(cursor.getLong(1))
        result
    }

    fun findTrackIdsByAbsolutePaths(paths: List<String>): Map<String, Long> {
        if (paths.isEmpty()) return emptyMap()
        val placeholders = paths.joinToString(",") { "?" }
        val requestedByKey = paths.associateBy(PathIdentity::key)
        return readableDatabase.rawQuery(
            "SELECT absolute_path, id FROM tracks WHERE available = 1 AND absolute_path COLLATE NOCASE IN ($placeholders)",
            paths.toTypedArray()
        ).use { cursor -> buildMap {
            while (cursor.moveToNext()) {
                val stored = cursor.getString(0)
                put(requestedByKey[PathIdentity.key(stored)] ?: stored, cursor.getLong(1))
            }
        } }
    }

    fun upsertImportedPlaylist(sourcePath: String, preferredName: String, trackIds: Collection<Long>): PlaylistSummary {
        return writableDatabase.transactionResult {
            val existing = query(
                "playlists", arrayOf("id", "name"), "source_path = ?", arrayOf(sourcePath), null, null, null, "1"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null }
            val id: Long
            val name: String
            if (existing == null) {
                name = uniquePlaylistName(this, preferredName)
                id = insertOrThrow("playlists", null, ContentValues().apply {
                    put("name", name)
                    put("created_at", System.currentTimeMillis())
                    put("source_path", sourcePath)
                })
            } else {
                id = existing.first
                name = existing.second
            }
            delete("playlist_tracks", "playlist_id = ?", arrayOf(id.toString()))
            val statement = compileStatement(
                "INSERT INTO playlist_tracks(playlist_id, position, track_id) VALUES(?, ?, ?)"
            )
            try {
                trackIds.forEachIndexed { index, trackId ->
                    statement.clearBindings()
                    statement.bindLong(1, id)
                    statement.bindLong(2, index.toLong())
                    statement.bindLong(3, trackId)
                    statement.executeInsert()
                }
            } finally {
                statement.close()
            }
            PlaylistSummary(id, name, trackIds.size)
        }
    }

    fun recordRecentlyPlayed(trackId: Long) {
        writableDatabase.execSQL(
            """
            INSERT OR REPLACE INTO recently_played(track_id, last_played, play_count)
            VALUES(?, ?, COALESCE((SELECT play_count FROM recently_played WHERE track_id = ?), 0) + 1)
            """.trimIndent(),
            arrayOf(trackId, System.currentTimeMillis(), trackId)
        )
    }

    fun saveAudiobookProgress(folderKey: String, trackId: Long, positionMs: Long) {
        writableDatabase.insertWithOnConflict(
            AudiobookProgressTable.NAME,
            null,
            ContentValues().apply {
                put("folder_key", folderKey)
                put("track_id", trackId)
                put("position_ms", positionMs.coerceAtLeast(0))
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun loadAudiobookProgress(folderKey: String): AudiobookProgress? = readableDatabase.query(
        AudiobookProgressTable.NAME,
        arrayOf("folder_key", "track_id", "position_ms", "updated_at"),
        "folder_key = ?", arrayOf(folderKey), null, null, null, "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        AudiobookProgress(
            folderKey = cursor.getString(0),
            trackId = cursor.getLong(1),
            positionMs = cursor.getLong(2).coerceAtLeast(0),
            updatedAt = cursor.getLong(3)
        )
    }

    fun loadAllAudiobookProgress(): Map<String, AudiobookProgress> = readableDatabase.query(
        AudiobookProgressTable.NAME,
        arrayOf("folder_key", "track_id", "position_ms", "updated_at"),
        null, null, null, null, null
    ).use { cursor ->
        val progress = HashMap<String, AudiobookProgress>(cursor.count * 4 / 3 + 1)
        while (cursor.moveToNext()) {
            val key = cursor.getString(0) ?: continue
            progress[key] = AudiobookProgress(
                folderKey = key,
                trackId = cursor.getLong(1),
                positionMs = cursor.getLong(2).coerceAtLeast(0),
                updatedAt = cursor.getLong(3)
            )
        }
        progress
    }

    fun deleteAudiobookProgress(folderKey: String) {
        writableDatabase.delete(AudiobookProgressTable.NAME, "folder_key = ?", arrayOf(folderKey))
    }

    fun loadRecentlyPlayedIds(limit: Int = 100): List<Long> = readableDatabase.query(
        "recently_played", arrayOf("track_id"), null, null, null, null, "last_played DESC", limit.toString()
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    /** Reads only user-owned state, translating every track reference to a portable identity. */
    fun exportPortableUserData(): PortableUserData = readableDatabase.transactionResult {
        val identities = query(
            "tracks", arrayOf("id", "volume_id", "relative_path"),
            null, null, null, null, null
        ).use { cursor ->
            buildMap<Long, PortableMediaIdentity> {
                while (cursor.moveToNext()) {
                    runCatching {
                        PortableMediaIdentity(cursor.getString(1), cursor.getString(2))
                    }.getOrNull()?.let { put(cursor.getLong(0), it) }
                }
            }
        }
        val favorites = query(
            "tracks", arrayOf("id"), "favorite = 1", null, null, null, "id"
        ).use { cursor -> buildList { while (cursor.moveToNext()) identities[cursor.getLong(0)]?.let(::add) } }
        val playlistRows = query(
            "playlists", arrayOf("id", "name"), "source_path IS NULL", null, null, null, "created_at, id"
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1)) } }
        val playlistTracks = query(
            "playlist_tracks", arrayOf("playlist_id", "track_id"),
            "playlist_id IN (SELECT id FROM playlists WHERE source_path IS NULL)",
            null, null, null, "playlist_id, position"
        ).use { cursor ->
            buildMap<Long, MutableList<PortableMediaIdentity>> {
                while (cursor.moveToNext()) {
                    identities[cursor.getLong(1)]?.let { getOrPut(cursor.getLong(0)) { ArrayList() }.add(it) }
                }
            }
        }
        val playlists = playlistRows.map { (id, name) -> PortablePlaylist(name, playlistTracks[id].orEmpty()) }
        val audiobook = query(
            AudiobookProgressTable.NAME,
            arrayOf("track_id", "position_ms", "updated_at"), null, null, null, null, "updated_at, track_id"
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) identities[cursor.getLong(0)]?.let { identity ->
                add(PortableAudiobookProgress(identity, cursor.getLong(1).coerceAtLeast(0), cursor.getLong(2).coerceAtLeast(0)))
            }
        } }
        val recent = query(
            "recently_played", arrayOf("track_id", "last_played", "play_count"),
            null, null, null, null, "last_played DESC, track_id"
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) identities[cursor.getLong(0)]?.let { identity ->
                add(PortableRecentTrack(identity, cursor.getLong(1).coerceAtLeast(0), cursor.getInt(2).coerceAtLeast(1)))
            }
        } }
        val queueRecords = query(
            "queue_items", arrayOf("entry_id", "track_id", "origin", "source_order"),
            null, null, null, null, "position", QueueController.MAX_QUEUE_ITEMS.toString()
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) identities[cursor.getLong(1)]?.let { identity ->
                add(Pair(
                    QueueEntry(
                        cursor.getLong(0), cursor.getLong(1), QueueOrigin.fromStorage(cursor.getString(2)),
                        if (cursor.isNull(3)) null else cursor.getInt(3)
                    ),
                    identity
                ))
            }
        } }
        val session = query(
            "playback_session",
            arrayOf("current_entry_id", "current_index", "position_ms", "repeat_mode", "shuffle_enabled", "shuffle_seed", "play_order"),
            "id = 1", null, null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else PersistedPlaybackSession(
                currentEntryId = if (cursor.isNull(0)) null else cursor.getLong(0),
                legacyCurrentIndex = if (cursor.isNull(1)) null else cursor.getInt(1),
                positionMs = cursor.getLong(2).coerceAtLeast(0),
                repeatMode = RepeatMode.fromStorage(cursor.getString(3)),
                shuffleEnabled = cursor.getInt(4) != 0,
                shuffleSeed = cursor.getLong(5),
                legacyPlayOrder = if (cursor.isNull(6)) null else decodePlayOrder(cursor.getString(6))
            )
        }
        val validLegacyOrder = session?.legacyPlayOrder?.takeIf { order ->
            order.size == queueRecords.size && order.toSet() == queueRecords.indices.toSet()
        }
        val orderedRecords = validLegacyOrder?.map(queueRecords::get) ?: queueRecords
        val legacyCurrentEntryId = session?.legacyCurrentIndex?.let(queueRecords::getOrNull)?.first?.id
        val currentEntryId = session?.currentEntryId ?: legacyCurrentEntryId
        val queue = if (session == null && orderedRecords.isEmpty()) null else PortableQueue(
            tracks = orderedRecords.map { it.second },
            currentIndex = currentEntryId?.let { id -> orderedRecords.indexOfFirst { it.first.id == id }.takeIf { it >= 0 } },
            positionMs = session?.positionMs ?: 0,
            repeatMode = (session?.repeatMode ?: RepeatMode.OFF).storageId,
            shuffleEnabled = session?.shuffleEnabled ?: false,
            shuffleSeed = session?.shuffleSeed ?: 0,
            origins = orderedRecords.map { it.first.origin },
            sourceOrders = orderedRecords.map { it.first.sourceOrder }
        )
        PortableUserData(favorites, playlists, audiobook, recent, queue)
    }

    /**
     * Replaces all user-bearing database rows in one transaction. The external
     * callback lets synchronously staged preferences/history participate in the
     * same rollback decision without putting rebuildable track rows in the backup.
     */
    fun replacePortableUserData(data: ResolvedUserData, applyExternal: () -> Unit = {}) {
        writableDatabase.transaction {
            update("tracks", ContentValues().apply { put("favorite", 0) }, null, null)
            val favorite = compileStatement("UPDATE tracks SET favorite = 1 WHERE id = ?")
            try {
                data.favoriteTrackIds.forEach { id ->
                    favorite.clearBindings()
                    favorite.bindLong(1, id)
                    favorite.executeUpdateDelete()
                }
            } finally {
                favorite.close()
            }

            // Card-backed M3U rows are scanner-owned and rebuildable. Preserve
            // them across import; only Y2Player-created playlists belong to the
            // backup payload.
            delete(
                "playlist_tracks",
                "playlist_id IN (SELECT id FROM playlists WHERE source_path IS NULL)",
                null
            )
            delete("playlists", "source_path IS NULL", null)
            data.playlists.forEachIndexed { playlistIndex, playlist ->
                val playlistId = insertOrThrow("playlists", null, ContentValues().apply {
                    put("name", uniquePlaylistName(this@transaction, playlist.name))
                    put("created_at", System.currentTimeMillis() + playlistIndex)
                    putNull("source_path")
                })
                val insertTrack = compileStatement(
                    "INSERT INTO playlist_tracks(playlist_id, position, track_id) VALUES(?, ?, ?)"
                )
                try {
                    playlist.trackIds.forEachIndexed { index, trackId ->
                        insertTrack.clearBindings()
                        insertTrack.bindLong(1, playlistId)
                        insertTrack.bindLong(2, index.toLong())
                        insertTrack.bindLong(3, trackId)
                        insertTrack.executeInsert()
                    }
                } finally {
                    insertTrack.close()
                }
            }

            delete(AudiobookProgressTable.NAME, null, null)
            data.audiobookProgress.forEach { progress ->
                insertOrThrow(AudiobookProgressTable.NAME, null, ContentValues().apply {
                    put("folder_key", progress.folderKey)
                    put("track_id", progress.trackId)
                    put("position_ms", progress.positionMs)
                    put("updated_at", progress.updatedAtUtcMs)
                })
            }

            delete("recently_played", null, null)
            data.recentlyPlayed.forEach { recent ->
                insertOrThrow("recently_played", null, ContentValues().apply {
                    put("track_id", recent.trackId)
                    put("last_played", recent.lastPlayedUtcMs)
                    put("play_count", recent.playCount)
                })
            }

            replaceQueueRows(this, data.queueEntries)
            delete("playback_session", null, null)
            data.playbackSession?.let { savePlaybackSessionRow(this, it) }
            applyExternal()
        }
    }

    fun recordScanStart(volumeId: String): Long = writableDatabase.insertOrThrow(
        "scan_runs", null, ContentValues().apply {
            put("volume_id", volumeId)
            put("started_at", System.currentTimeMillis())
            put("status", "RUNNING")
            put("files", 0)
        }
    )

    fun recordScanEnd(id: Long, status: String, files: Int, error: String? = null) {
        writableDatabase.update("scan_runs", ContentValues().apply {
            put("finished_at", System.currentTimeMillis())
            put("status", status)
            put("files", files)
            if (error == null) putNull("error") else put("error", error)
        }, "id = ?", arrayOf(id.toString()))
    }

    fun resetLibrary() {
        writableDatabase.transaction {
            delete("playlist_tracks", null, null)
            delete("playlists", null, null)
            delete("recently_played", null, null)
            delete("queue_items", null, null)
            delete("playback_session", null, null)
            delete("tracks", null, null)
            delete("scan_runs", null, null)
            delete(AudiobookProgressTable.NAME, null, null)
        }
    }

    private fun replaceQueueRows(db: SQLiteDatabase, entries: List<QueueEntry>) {
        db.delete("queue_items", null, null)
        val statement = db.compileStatement(
            "INSERT INTO queue_items(position, entry_id, track_id, origin, source_order) VALUES(?, ?, ?, ?, ?)"
        )
        try {
            entries.forEachIndexed { index, entry ->
                statement.clearBindings()
                statement.bindLong(1, index.toLong())
                statement.bindLong(2, entry.id)
                statement.bindLong(3, entry.trackId)
                statement.bindString(4, entry.origin.storageId)
                entry.sourceOrder?.let { statement.bindLong(5, it.toLong()) } ?: statement.bindNull(5)
                statement.executeInsert()
            }
        } finally {
            statement.close()
        }
    }

    private fun savePlaybackSessionRow(db: SQLiteDatabase, session: PersistedPlaybackSession) {
        db.insertWithOnConflict(
            "playback_session",
            null,
            ContentValues().apply {
                put("id", 1)
                session.currentEntryId?.let { put("current_entry_id", it) } ?: putNull("current_entry_id")
                putNull("current_index")
                put("position_ms", session.positionMs.coerceAtLeast(0))
                put("repeat_mode", session.repeatMode.storageId)
                put("shuffle_enabled", if (session.shuffleEnabled) 1 else 0)
                put("shuffle_seed", session.shuffleSeed)
                putNull("play_order")
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun compactPlaylist(playlistId: Long) {
        val ids = loadPlaylistTrackIds(playlistId)
        writableDatabase.transaction {
            delete("playlist_tracks", "playlist_id = ?", arrayOf(playlistId.toString()))
            ids.forEachIndexed { index, trackId ->
                insertOrThrow("playlist_tracks", null, ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("position", index)
                    put("track_id", trackId)
                })
            }
        }
    }

    private fun TrackDraft.toValues(scanToken: Long, includeAddedAt: Boolean): ContentValues = ContentValues().apply {
        put("volume_id", volumeId)
        put("absolute_path", absolutePath)
        put("relative_path", relativePath)
        put("title", title)
        putNullable("artist", artist)
        putNullable("album", album)
        putNullable("album_artist", albumArtist)
        putNullable("track_number", trackNumber)
        putNullable("track_total", trackTotal)
        putNullable("disc_number", discNumber)
        putNullable("disc_total", discTotal)
        put("duration_ms", durationMs)
        put("file_size", fileSize)
        put("modified_at", modifiedAt)
        put("last_seen_scan", scanToken)
        put("available", 1)
        putNullable("scan_error", scanError)
        putNullable("playback_error", playbackError)
        putNullable("codec", codec)
        putNullable("container", container)
        putNullable("sample_rate", sampleRate)
        putNullable("bit_depth", bitDepth)
        putNullable("channels", channels)
        putNullable("comment", comment)
        putNullable("composer", composer)
        putNullable("genre", genre)
        putNullable("date", date)
        putNullable("year", year)
        putNullable("bitrate", bitrate)
        put("has_artwork", if (hasArtwork) 1 else 0)
        putNullable("replaygain_track_db", replayGainTrackDb)
        putNullable("replaygain_track_peak", replayGainTrackPeak)
        putNullable("replaygain_album_db", replayGainAlbumDb)
        putNullable("replaygain_album_peak", replayGainAlbumPeak)
        if (includeAddedAt) {
            put("added_at", System.currentTimeMillis())
            put("favorite", 0)
        }
    }

    private class TrackColumns(cursor: Cursor) {
        val id = cursor.getColumnIndexOrThrow("id")
        val volumeId = cursor.getColumnIndexOrThrow("volume_id")
        val absolutePath = cursor.getColumnIndexOrThrow("absolute_path")
        val relativePath = cursor.getColumnIndexOrThrow("relative_path")
        val title = cursor.getColumnIndexOrThrow("title")
        val artist = cursor.getColumnIndexOrThrow("artist")
        val album = cursor.getColumnIndexOrThrow("album")
        val albumArtist = cursor.getColumnIndexOrThrow("album_artist")
        val trackNumber = cursor.getColumnIndexOrThrow("track_number")
        val trackTotal = cursor.getColumnIndexOrThrow("track_total")
        val discNumber = cursor.getColumnIndexOrThrow("disc_number")
        val discTotal = cursor.getColumnIndexOrThrow("disc_total")
        val durationMs = cursor.getColumnIndexOrThrow("duration_ms")
        val fileSize = cursor.getColumnIndexOrThrow("file_size")
        val modifiedAt = cursor.getColumnIndexOrThrow("modified_at")
        val available = cursor.getColumnIndexOrThrow("available")
        val scanError = cursor.getColumnIndexOrThrow("scan_error")
        val codec = cursor.getColumnIndexOrThrow("codec")
        val container = cursor.getColumnIndexOrThrow("container")
        val sampleRate = cursor.getColumnIndexOrThrow("sample_rate")
        val bitDepth = cursor.getColumnIndexOrThrow("bit_depth")
        val channels = cursor.getColumnIndexOrThrow("channels")
        val comment = cursor.getColumnIndexOrThrow("comment")
        val composer = cursor.getColumnIndexOrThrow("composer")
        val genre = cursor.getColumnIndexOrThrow("genre")
        val date = cursor.getColumnIndexOrThrow("date")
        val year = cursor.getColumnIndexOrThrow("year")
        val bitrate = cursor.getColumnIndexOrThrow("bitrate")
        val hasArtwork = cursor.getColumnIndexOrThrow("has_artwork")
        val replayGainTrackDb = cursor.getColumnIndexOrThrow("replaygain_track_db")
        val replayGainTrackPeak = cursor.getColumnIndexOrThrow("replaygain_track_peak")
        val replayGainAlbumDb = cursor.getColumnIndexOrThrow("replaygain_album_db")
        val replayGainAlbumPeak = cursor.getColumnIndexOrThrow("replaygain_album_peak")
        val addedAt = cursor.getColumnIndexOrThrow("added_at")
        val favorite = cursor.getColumnIndexOrThrow("favorite")
        val playbackError = cursor.getColumnIndexOrThrow("playback_error")
    }

    private fun Cursor.toTrack(columns: TrackColumns, stringPool: MutableMap<String, String>?): Track? {
        val absolutePath = nullableString(columns.absolutePath)?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val relativePath = nullableString(columns.relativePath)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: java.io.File(absolutePath).name
        val title = nullableString(columns.title)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: java.io.File(absolutePath).nameWithoutExtension.ifBlank { "Unknown track" }
        return Track(
        id = getLong(columns.id).takeIf { it > 0 } ?: return null,
        volumeId = (nullableString(columns.volumeId)?.trim().takeUnless { it.isNullOrEmpty() } ?: "unknown").pooled(stringPool),
        absolutePath = absolutePath,
        relativePath = relativePath,
        title = title,
        artist = nullableString(columns.artist)?.pooled(stringPool),
        album = nullableString(columns.album)?.pooled(stringPool),
        albumArtist = nullableString(columns.albumArtist)?.pooled(stringPool),
        trackNumber = nullableInt(columns.trackNumber),
        trackTotal = nullableInt(columns.trackTotal),
        discNumber = nullableInt(columns.discNumber),
        discTotal = nullableInt(columns.discTotal),
        durationMs = getLong(columns.durationMs).coerceAtLeast(0),
        fileSize = getLong(columns.fileSize).coerceAtLeast(0),
        modifiedAt = getLong(columns.modifiedAt).coerceAtLeast(0),
        available = getInt(columns.available) != 0,
        scanError = nullableString(columns.scanError),
        codec = nullableString(columns.codec),
        container = nullableString(columns.container),
        sampleRate = nullableInt(columns.sampleRate),
        bitDepth = nullableInt(columns.bitDepth),
        channels = nullableInt(columns.channels),
        comment = nullableString(columns.comment),
        composer = nullableString(columns.composer)?.pooled(stringPool),
        genre = nullableString(columns.genre)?.pooled(stringPool),
        date = nullableString(columns.date)?.pooled(stringPool),
        year = nullableInt(columns.year),
        bitrate = nullableLong(columns.bitrate),
        hasArtwork = getInt(columns.hasArtwork) != 0,
        replayGainTrackDb = nullableFloat(columns.replayGainTrackDb),
        replayGainTrackPeak = nullableFloat(columns.replayGainTrackPeak),
        replayGainAlbumDb = nullableFloat(columns.replayGainAlbumDb),
        replayGainAlbumPeak = nullableFloat(columns.replayGainAlbumPeak),
        addedAt = getLong(columns.addedAt).coerceAtLeast(0),
        favorite = getInt(columns.favorite) != 0,
        playbackError = nullableString(columns.playbackError)
    )
    }

    private fun Cursor.nullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun String.pooled(pool: MutableMap<String, String>?): String =
        if (pool == null) this else pool.poolString(this)

    private fun Cursor.nullableInt(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun Cursor.nullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun Cursor.nullableFloat(index: Int): Float? =
        if (isNull(index)) null else getFloat(index)

    private fun decodePlayOrder(raw: String): List<Int>? {
        if (raw.length > MAX_PLAY_ORDER_CHARS) return null
        val result = ArrayList<Int>()
        raw.splitToSequence(',').take(MAX_PLAY_ORDER_ITEMS + 1).forEach { token ->
            if (result.size >= MAX_PLAY_ORDER_ITEMS) return null
            result += token.toIntOrNull() ?: return null
        }
        return result
    }

    private fun ContentValues.putNullable(key: String, value: String?) { if (value == null) putNull(key) else put(key, value) }
    private fun ContentValues.putNullable(key: String, value: Int?) { if (value == null) putNull(key) else put(key, value) }
    private fun ContentValues.putNullable(key: String, value: Long?) { if (value == null) putNull(key) else put(key, value) }
    private fun ContentValues.putNullable(key: String, value: Float?) { if (value == null) putNull(key) else put(key, value) }

    private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try { block(); setTransactionSuccessful() } finally { endTransaction() }
    }

    private inline fun <T> SQLiteDatabase.transactionResult(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try {
            val result = block()
            setTransactionSuccessful()
            result
        } finally {
            endTransaction()
        }
    }

    private fun MutableMap<String, String>.poolString(value: String): String =
        this[value] ?: if (size < MAX_POOLED_STRINGS) value.also { put(it, it) } else value

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) if (nameIndex >= 0 && cursor.getString(nameIndex) == column) return@use true
            false
        }

    private fun uniquePlaylistName(db: SQLiteDatabase, preferredName: String): String {
        val existing = db.query("playlists", arrayOf("name"), null, null, null, null, null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        if (preferredName !in existing) return preferredName
        var suffix = 2
        while ("$preferredName ($suffix)" in existing) suffix += 1
        return "$preferredName ($suffix)"
    }

    private fun createTracks(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                volume_id TEXT NOT NULL,
                absolute_path TEXT NOT NULL UNIQUE,
                relative_path TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT,
                album TEXT,
                album_artist TEXT,
                track_number INTEGER,
                track_total INTEGER,
                disc_number INTEGER,
                disc_total INTEGER,
                duration_ms INTEGER NOT NULL,
                file_size INTEGER NOT NULL,
                modified_at INTEGER NOT NULL,
                last_seen_scan INTEGER NOT NULL,
                playback_error TEXT,
                available INTEGER NOT NULL DEFAULT 1,
                scan_error TEXT,
                codec TEXT,
                container TEXT,
                sample_rate INTEGER,
                bit_depth INTEGER,
                channels INTEGER,
                comment TEXT,
                composer TEXT,
                genre TEXT,
                date TEXT,
                year INTEGER,
                bitrate INTEGER,
                has_artwork INTEGER NOT NULL DEFAULT 0,
                replaygain_track_db REAL,
                replaygain_track_peak REAL,
                replaygain_album_db REAL,
                replaygain_album_peak REAL,
                added_at INTEGER NOT NULL,
                favorite INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        createTrackIndexes(db)
    }

    private fun createTrackIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS tracks_title_idx ON tracks(title COLLATE NOCASE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS tracks_artist_idx ON tracks(artist COLLATE NOCASE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS tracks_album_idx ON tracks(album COLLATE NOCASE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS tracks_volume_path_idx ON tracks(volume_id, absolute_path)")
        db.execSQL("CREATE INDEX IF NOT EXISTS tracks_available_title_idx ON tracks(available, title COLLATE NOCASE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS tracks_favorite_title_idx ON tracks(favorite, title COLLATE NOCASE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS tracks_seen_idx ON tracks(volume_id, last_seen_scan)")
        createVolumeRelativePathIndex(db)
    }

    private fun createVolumeRelativePathIndex(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS tracks_volume_relative_nocase_idx " +
                "ON tracks(volume_id, relative_path COLLATE NOCASE)"
        )
    }

    private fun createPlayback(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE queue_items (position INTEGER PRIMARY KEY, entry_id INTEGER NOT NULL, " +
                "track_id INTEGER NOT NULL, origin TEXT NOT NULL, source_order INTEGER, " +
                "FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE TABLE playback_session (id INTEGER PRIMARY KEY CHECK(id = 1), current_entry_id INTEGER, " +
                "current_index INTEGER, position_ms INTEGER NOT NULL, repeat_mode TEXT NOT NULL, " +
                "shuffle_enabled INTEGER NOT NULL, shuffle_seed INTEGER NOT NULL, play_order TEXT)"
        )
    }

    private fun createDiagnostics(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS scan_runs (id INTEGER PRIMARY KEY AUTOINCREMENT, volume_id TEXT NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER, status TEXT NOT NULL, files INTEGER NOT NULL, error TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS diagnostic_events (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, level TEXT NOT NULL, category TEXT NOT NULL, message TEXT NOT NULL)")
    }

    private fun createUserLibrary(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL, source_path TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS playlist_tracks (playlist_id INTEGER NOT NULL, position INTEGER NOT NULL, track_id INTEGER NOT NULL, PRIMARY KEY(playlist_id, track_id), FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE, FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS playlist_tracks_position_idx ON playlist_tracks(playlist_id, position)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS playlists_source_path_idx ON playlists(source_path)")
        db.execSQL("CREATE TABLE IF NOT EXISTS recently_played (track_id INTEGER PRIMARY KEY, last_played INTEGER NOT NULL, play_count INTEGER NOT NULL, FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE)")
        db.execSQL(AudiobookProgressTable.CREATE)
    }

    companion object {
        private const val DATABASE_NAME = "y2player.db"
        private const val DATABASE_VERSION = LibrarySchema.VERSION
        private const val MAX_POOLED_STRINGS = 1_024
        private const val MAX_PLAY_ORDER_ITEMS = 50_000
        private const val MAX_PLAY_ORDER_CHARS = 300_000
        private const val QUERY_ID_BATCH = 192
        private const val SEEN_UPDATE_CHUNK = 400
        private val TRACK_COLUMNS = arrayOf(
            "id", "volume_id", "absolute_path", "relative_path", "title", "artist", "album", "album_artist",
            "track_number", "track_total", "disc_number", "disc_total", "duration_ms", "file_size", "modified_at", "available", "scan_error",
            "codec", "container", "sample_rate", "bit_depth", "channels", "comment", "composer", "genre", "date", "year", "bitrate",
            "has_artwork", "replaygain_track_db", "replaygain_track_peak", "replaygain_album_db",
            "replaygain_album_peak", "added_at", "favorite", "playback_error"
        )

        private fun backupDatabase(context: Context, path: String?, reason: String): java.io.File? = runCatching {
            val source = path?.takeUnless { it == ":memory:" }?.let { java.io.File(it) }
                ?.takeIf { it.isFile } ?: return@runCatching null
            val directory = java.io.File(context.filesDir, "database-recovery").apply { mkdirs() }
            val safeReason = reason.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val destination = java.io.File(directory, "y2player-${System.currentTimeMillis()}-$safeReason.db")
            source.copyTo(destination, overwrite = false)
            destination
        }.onFailure { Log.e("Y2PlayerDb", "Database backup failed for $reason", it) }.getOrNull()
    }

    private class BackupCorruptionHandler(private val context: Context) : DatabaseErrorHandler {
        override fun onCorruption(dbObj: SQLiteDatabase) {
            val path = dbObj.path
            val backup = backupDatabase(context, path, "corrupt")
            Log.e("Y2PlayerDb", "Corrupt database backed up to ${backup?.absolutePath ?: "backup failed"}")
            runCatching { dbObj.close() }
            path?.takeUnless { it == ":memory:" }?.let { runCatching { SQLiteDatabase.deleteDatabase(java.io.File(it)) } }
        }
    }
}
