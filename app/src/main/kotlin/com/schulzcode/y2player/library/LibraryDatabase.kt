package com.schulzcode.y2player.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.core.model.TrackDraft
import com.schulzcode.y2player.diagnostics.FormatProbeResult
import com.schulzcode.y2player.queue.PersistedPlaybackSession
import com.schulzcode.y2player.queue.QueueController

class LibraryDatabase(private val appContext: Context) : SQLiteOpenHelper(
    appContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
    BackupCorruptionHandler(appContext.applicationContext)
) {
    private var cachedPlayOrderSource: List<Int>? = null
    private var cachedPlayOrderText: String? = null

    init {
        // WAL gives readers a snapshot without blocking the writer and enables the
        // read-connection pool on API 19. Without it, a findTrack() issued by the
        // playback thread for the next transition can stall behind a scan-batch
        // commit or the post-scan full reload on the single rollback-journal
        // connection — an audible gap on slow eMMC.
        setWriteAheadLoggingEnabled(true)
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
            if (version != newVersion) error("No migration exists from $oldVersion to $newVersion")
        }
    }

    /**
     * Loads only playable rows: unavailable tracks (removed volume) stay DB-only and
     * are re-materialized by the next successful scan. Keeping them out of RAM saves
     * several MB of long-lived Dalvik heap for libraries with a removed SD card.
     * Uses tracks_available_title_idx for the filtered, pre-sorted walk.
     */
    fun loadTracks(): List<Track> {
        val stringPool = HashMap<String, String>(256)
        return readableDatabase.query(
            "tracks", TRACK_COLUMNS, "available = 1", null, null, null, "title COLLATE NOCASE"
        ).use { cursor ->
            val columns = TrackColumns(cursor)
            buildList { while (cursor.moveToNext()) cursor.toTrack(columns, stringPool)?.let(::add) }
        }
    }

    /** Total indexed rows including unavailable ones (About screen bookkeeping). */
    fun countTracks(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM tracks", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    /**
     * Single row, so no string pool: pooling exists to share repeated artist and
     * album strings across a whole library walk, and a one-row map could only
     * ever hold values already unique to this track.
     */
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

    fun applyScanBatch(volumeId: String, scanToken: Long, files: List<ScannedFile>) {
        if (files.isEmpty()) return
        writableDatabase.transaction {
            // Unchanged files only need their seen-token refreshed; one statement per
            // chunk instead of one UPDATE per file removes ~30k statement executions
            // from every rescan. Chunk size stays far below SQLite's 999-variable cap.
            val unchanged = ArrayList<String>(files.size)
            files.forEach { scanned ->
                val draft = scanned.changedDraft
                if (draft == null) {
                    unchanged += scanned.absolutePath
                } else {
                    var updated = update("tracks", draft.toValues(scanToken, includeAddedAt = false), "absolute_path = ?", arrayOf(draft.absolutePath))
                    if (updated == 0) {
                        updated = update(
                            "tracks",
                            draft.toValues(scanToken, includeAddedAt = false),
                            "volume_id = ? AND relative_path = ? COLLATE NOCASE",
                            arrayOf(draft.volumeId, draft.relativePath)
                        )
                    }
                    if (updated == 0) insertOrThrow("tracks", null, draft.toValues(scanToken, includeAddedAt = true))
                }
            }
            unchanged.chunked(SEEN_UPDATE_CHUNK).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val args = arrayOfNulls<Any>(chunk.size + 2)
                args[0] = scanToken
                args[1] = volumeId
                chunk.forEachIndexed { index, path -> args[index + 2] = path }
                execSQL(
                    "UPDATE tracks SET last_seen_scan = ?, available = 1 WHERE volume_id = ? AND absolute_path IN ($placeholders)",
                    args
                )
            }
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

    fun setFavorite(trackId: Long, favorite: Boolean) {
        writableDatabase.update(
            "tracks",
            ContentValues().apply { put("favorite", if (favorite) 1 else 0) },
            "id = ?",
            arrayOf(trackId.toString())
        )
    }


    /** Stores queue structure and its matching session atomically. */
    fun saveQueueState(trackIds: List<Long>, session: PersistedPlaybackSession) {
        writableDatabase.transaction {
            replaceQueueRows(this, trackIds)
            savePlaybackSessionRow(this, session)
        }
    }

    fun loadQueue(): List<Long> = readableDatabase.query(
        "queue_items", arrayOf("track_id"), null, null, null, null, "position", QueueController.MAX_QUEUE_ITEMS.toString()
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

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
        arrayOf("current_index", "position_ms", "repeat_mode", "shuffle_enabled", "shuffle_seed", "play_order"),
        "id = 1", null, null, null, null
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        PersistedPlaybackSession(
            currentIndex = if (cursor.isNull(0)) null else cursor.getInt(0),
            positionMs = cursor.getLong(1).coerceAtLeast(0),
            repeatMode = RepeatMode.fromStorage(cursor.getString(2)),
            shuffleEnabled = cursor.getInt(3) != 0,
            shuffleSeed = cursor.getLong(4),
            playOrder = if (cursor.isNull(5)) null else decodePlayOrder(cursor.getString(5))
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

    fun loadRecentlyPlayedIds(limit: Int = 100): List<Long> = readableDatabase.query(
        "recently_played", arrayOf("track_id"), null, null, null, null, "last_played DESC", limit.toString()
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    fun saveFormatProbe(results: List<FormatProbeResult>) {
        writableDatabase.transaction {
            results.forEach { result ->
                insertWithOnConflict(
                    "format_probe", null, ContentValues().apply {
                        put("extension", result.extension)
                        put("success", if (result.success) 1 else 0)
                        put("message", result.message)
                        put("tested_at", result.testedAt)
                    }, SQLiteDatabase.CONFLICT_REPLACE
                )
            }
        }
    }

    fun loadFormatProbe(): List<FormatProbeResult> = readableDatabase.query(
        "format_probe", arrayOf("extension", "success", "message", "tested_at"),
        null, null, null, null, "extension"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(FormatProbeResult(cursor.getString(0), cursor.getInt(1) != 0, cursor.getString(2), cursor.getLong(3)))
            }
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
            delete("format_probe", null, null)
        }
    }


    private fun replaceQueueRows(db: SQLiteDatabase, trackIds: List<Long>) {
        db.delete("queue_items", null, null)
        val statement = db.compileStatement("INSERT INTO queue_items(position, track_id) VALUES(?, ?)")
        try {
            trackIds.forEachIndexed { index, id ->
                statement.clearBindings()
                statement.bindLong(1, index.toLong())
                statement.bindLong(2, id)
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
                session.currentIndex?.let { put("current_index", it) } ?: putNull("current_index")
                put("position_ms", session.positionMs.coerceAtLeast(0))
                put("repeat_mode", session.repeatMode.storageId)
                put("shuffle_enabled", if (session.shuffleEnabled) 1 else 0)
                put("shuffle_seed", session.shuffleSeed)
                session.playOrder?.let { put("play_order", encodePlayOrder(it)) } ?: putNull("play_order")
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
        putNullable("disc_number", discNumber)
        put("duration_ms", durationMs)
        put("file_size", fileSize)
        put("modified_at", modifiedAt)
        put("last_seen_scan", scanToken)
        put("available", 1)
        putNullable("scan_error", scanError)
        putNullable("codec", codec)
        putNullable("sample_rate", sampleRate)
        putNullable("bit_depth", bitDepth)
        putNullable("channels", channels)
        if (includeAddedAt) {
            put("added_at", System.currentTimeMillis())
            put("favorite", 0)
        }
    }

    /**
     * Column indices for one cursor, resolved once.
     *
     * Resolving them by name inside the row loop meant twenty-one string-keyed
     * lookups per track: on a 30k-track library that was over half a million
     * redundant lookups per full load, all of it on the library thread ahead of
     * the first usable screen.
     */
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
        val discNumber = cursor.getColumnIndexOrThrow("disc_number")
        val durationMs = cursor.getColumnIndexOrThrow("duration_ms")
        val fileSize = cursor.getColumnIndexOrThrow("file_size")
        val modifiedAt = cursor.getColumnIndexOrThrow("modified_at")
        val available = cursor.getColumnIndexOrThrow("available")
        val scanError = cursor.getColumnIndexOrThrow("scan_error")
        val codec = cursor.getColumnIndexOrThrow("codec")
        val sampleRate = cursor.getColumnIndexOrThrow("sample_rate")
        val bitDepth = cursor.getColumnIndexOrThrow("bit_depth")
        val channels = cursor.getColumnIndexOrThrow("channels")
        val addedAt = cursor.getColumnIndexOrThrow("added_at")
        val favorite = cursor.getColumnIndexOrThrow("favorite")
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
        discNumber = nullableInt(columns.discNumber),
        durationMs = getLong(columns.durationMs).coerceAtLeast(0),
        fileSize = getLong(columns.fileSize).coerceAtLeast(0),
        modifiedAt = getLong(columns.modifiedAt).coerceAtLeast(0),
        available = getInt(columns.available) != 0,
        scanError = nullableString(columns.scanError),
        codec = nullableString(columns.codec),
        sampleRate = nullableInt(columns.sampleRate),
        bitDepth = nullableInt(columns.bitDepth),
        channels = nullableInt(columns.channels),
        addedAt = getLong(columns.addedAt).coerceAtLeast(0),
        favorite = getInt(columns.favorite) != 0
    )
    }

    private fun Cursor.nullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun String.pooled(pool: MutableMap<String, String>?): String =
        if (pool == null) this else pool.poolString(this)

    private fun Cursor.nullableInt(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun encodePlayOrder(order: List<Int>): String {
        if (cachedPlayOrderSource === order) return cachedPlayOrderText.orEmpty()
        return order.joinToString(",").also {
            cachedPlayOrderSource = order
            cachedPlayOrderText = it
        }
    }

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
                disc_number INTEGER,
                duration_ms INTEGER NOT NULL,
                file_size INTEGER NOT NULL,
                modified_at INTEGER NOT NULL,
                last_seen_scan INTEGER NOT NULL,
                available INTEGER NOT NULL DEFAULT 1,
                scan_error TEXT,
                codec TEXT,
                sample_rate INTEGER,
                bit_depth INTEGER,
                channels INTEGER,
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
    }

    private fun createPlayback(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE queue_items (position INTEGER PRIMARY KEY, track_id INTEGER NOT NULL, FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE)")
        db.execSQL(
            "CREATE TABLE playback_session (id INTEGER PRIMARY KEY CHECK(id = 1), current_index INTEGER, position_ms INTEGER NOT NULL, repeat_mode TEXT NOT NULL, shuffle_enabled INTEGER NOT NULL, shuffle_seed INTEGER NOT NULL, play_order TEXT)"
        )
    }

    private fun createDiagnostics(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS scan_runs (id INTEGER PRIMARY KEY AUTOINCREMENT, volume_id TEXT NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER, status TEXT NOT NULL, files INTEGER NOT NULL, error TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS diagnostic_events (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, level TEXT NOT NULL, category TEXT NOT NULL, message TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS format_probe (extension TEXT PRIMARY KEY, success INTEGER NOT NULL, message TEXT NOT NULL, tested_at INTEGER NOT NULL)")
    }

    private fun createUserLibrary(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL, source_path TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS playlist_tracks (playlist_id INTEGER NOT NULL, position INTEGER NOT NULL, track_id INTEGER NOT NULL, PRIMARY KEY(playlist_id, track_id), FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE, FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS playlist_tracks_position_idx ON playlist_tracks(playlist_id, position)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS playlists_source_path_idx ON playlists(source_path)")
        db.execSQL("CREATE TABLE IF NOT EXISTS recently_played (track_id INTEGER PRIMARY KEY, last_played INTEGER NOT NULL, play_count INTEGER NOT NULL, FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE)")
    }

    companion object {
        private const val DATABASE_NAME = "y2player.db"
        private const val DATABASE_VERSION = 7
        private const val MAX_POOLED_STRINGS = 1_024
        private const val MAX_PLAY_ORDER_ITEMS = 50_000
        private const val MAX_PLAY_ORDER_CHARS = 300_000
        private const val QUERY_ID_BATCH = 192
        private const val SEEN_UPDATE_CHUNK = 192
        private val TRACK_COLUMNS = arrayOf(
            "id", "volume_id", "absolute_path", "relative_path", "title", "artist", "album", "album_artist",
            "track_number", "disc_number", "duration_ms", "file_size", "modified_at", "available", "scan_error",
            "codec", "sample_rate", "bit_depth", "channels", "added_at", "favorite"
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
