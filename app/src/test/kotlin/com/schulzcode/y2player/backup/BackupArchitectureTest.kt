package com.schulzcode.y2player.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupArchitectureTest {
    @Test fun androidPersistenceUsesTransactionsSynchronousPreferencesAndPlaybackBarrier() {
        val database = source("library/LibraryDatabase.kt")
        val preferences = source("settings/AppPreferences.kt")
        val playback = source("playback/PlaybackService.kt")
        val format = source("backup/BackupFormat.kt")

        assertTrue(database.contains("fun replacePortableUserData"))
        assertTrue(database.contains("writableDatabase.transaction"))
        assertTrue(database.contains("applyExternal()"))
        assertTrue(database.contains("\"source_path IS NULL\""))
        assertTrue(database.contains("delete(\"playlists\", \"source_path IS NULL\", null)"))
        assertTrue(database.contains("uniquePlaylistName(this@transaction, playlist.name)"))
        assertTrue(preferences.contains("fun restore(value: PlayerPreferencesState): Boolean"))
        assertTrue(preferences.contains("}.commit()"))
        assertTrue(playback.contains("backupImportInProgress"))
        assertTrue(playback.contains("prepareBackupImport"))
        assertTrue(format.contains("output.fd.sync()"))
        assertTrue(format.contains("temp.renameTo(destination)"))
    }

    private fun source(relative: String): String = File("src/main/kotlin/com/schulzcode/y2player/$relative").readText()
}
