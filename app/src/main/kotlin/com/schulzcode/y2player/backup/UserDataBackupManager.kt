package com.schulzcode.y2player.backup

import com.schulzcode.y2player.core.state.PlayerPreferencesState
import com.schulzcode.y2player.library.LibraryDatabase
import com.schulzcode.y2player.settings.AppPreferences
import com.schulzcode.y2player.storage.StorageRoot
import com.schulzcode.y2player.storage.preferredWritableRoot
import java.io.File

class UserDataBackupManager(
    private val database: LibraryDatabase,
    private val preferences: AppPreferences,
    private val history: HistoryStore,
    private val appVersion: String,
    private val rootsProvider: () -> List<StorageRoot>
) {
    interface HistoryStore {
        fun records(): List<String>
        fun replace(records: List<String>): Boolean
    }

    data class Preview(
        val file: File,
        val document: BackupDocument,
        val settings: PlayerPreferencesState,
        val resolved: ResolvedUserData
    ) {
        val summary: String get() = buildString {
            append(document.userData.playlists.size).append(" playlists, ")
            append(document.userData.favorites.size).append(" favorites, ")
            append(document.userData.audiobookProgress.size).append(" book positions, ")
            append(document.userData.recentlyPlayed.size).append(" recent tracks")
            if (resolved.unresolvedReferences > 0) append(" · ${resolved.unresolvedReferences} tracks currently missing")
        }
    }

    data class ExportResult(val file: File, val references: Int, val historyRecords: Int)
    data class ImportResult(val restoredReferences: Int, val unresolvedReferences: Int, val historyRecords: Int)

    fun export(nowUtcMs: Long = System.currentTimeMillis()): Result<ExportResult> = runCatching {
        val file = backupFile()
        val userData = database.exportPortableUserData()
        val historyRecords = history.records()
        val document = BackupDocument(
            appVersion = appVersion,
            createdAtUtcMs = nowUtcMs,
            settings = PreferenceBackup.encode(preferences.snapshot()),
            userData = userData,
            listeningHistory = historyRecords
        )
        BackupFormat.writeAtomic(file, document)
        ExportResult(file, referenceCount(userData), historyRecords.size)
    }

    fun preview(): Result<Preview> = runCatching {
        val file = backupFile(requireExisting = true)
        val document = BackupFormat.read(file)
        val settings = PreferenceBackup.decode(document.settings)
        val resolved = PortableUserDataResolver.resolve(document.userData, database.loadTracks())
        Preview(file, document, settings, resolved)
    }

    fun import(preview: Preview): Result<ImportResult> = runCatching {
        // Validate the card file again immediately before mutation.
        val current = BackupFormat.read(preview.file)
        require(current == preview.document) { "Backup changed after validation; select Import Backup again" }
        val settings = PreferenceBackup.decode(current.settings)
        val resolved = PortableUserDataResolver.resolve(current.userData, database.loadTracks())
        val oldSettings = preferences.snapshot()
        val oldHistory = history.records()
        ImportTransactionCoordinator.apply(
            databaseTransaction = { external -> database.replacePortableUserData(resolved, external) },
            applyHistory = { history.replace(current.listeningHistory) },
            applySettings = { preferences.restore(settings) },
            rollbackHistory = { history.replace(oldHistory) },
            rollbackSettings = { preferences.restore(oldSettings) }
        )
        ImportResult(resolved.restoredReferences, resolved.unresolvedReferences, current.listeningHistory.size)
    }

    private fun backupFile(requireExisting: Boolean = false): File {
        val root = preferredWritableRoot(rootsProvider()) ?: error("No writable storage is mounted")
        val file = File(root.directory, "Y2Player/Backups/${BackupFormat.FILE_NAME}")
        if (requireExisting && !file.isFile) error("No backup found at ${file.absolutePath}")
        return file
    }

    private fun referenceCount(data: PortableUserData): Int =
        data.favorites.size + data.playlists.sumOf { it.tracks.size } + data.audiobookProgress.size +
            data.recentlyPlayed.size + (data.queue?.tracks?.size ?: 0)
}
