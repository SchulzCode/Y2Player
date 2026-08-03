package com.schulzcode.y2player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderBackendMigrationTest {
    @Test
    fun ffmpegMigrationClearsFrameworkVerdictsAndProbeResults() {
        assertEquals(9, DecoderBackendMigration.VERSION)
        assertTrue(
            DecoderBackendMigration.RESET_STATEMENTS.any {
                it.contains("playback_error = NULL")
            }
        )
        assertTrue(
            DecoderBackendMigration.RESET_STATEMENTS.any {
                it == "DELETE FROM format_probe"
            }
        )
    }

    @Test fun metadataMigrationSchedulesExactlyOneFingerprintRefresh() {
        assertEquals(11, FfmpegMetadataMigration.VERSION)
        assertEquals(14, LibrarySchema.VERSION)
        assertEquals(
            1,
            FfmpegMetadataMigration.STATEMENTS.count {
                it == "UPDATE tracks SET modified_at = -1"
            }
        )
        assertEquals(11, FfmpegMetadataMigration.STATEMENTS.count { it.startsWith("ALTER TABLE") })
    }

    @Test fun completenessMigrationPersistsCommentsAndNumberingTotals() {
        assertEquals(13, MetadataCompletenessMigration.VERSION)
        assertEquals(3, MetadataCompletenessMigration.STATEMENTS.count { it.startsWith("ALTER TABLE") })
        assertEquals(1, MetadataCompletenessMigration.STATEMENTS.count { it == "UPDATE tracks SET modified_at = -1" })
    }

    @Test fun audiobookProgressMigrationIsAdditiveAndTriggersNoRescan() {
        assertEquals(14, AudiobookProgressMigration.VERSION)
        assertEquals(1, AudiobookProgressMigration.STATEMENTS.size)
        assertTrue(AudiobookProgressMigration.STATEMENTS.none { it.contains("modified_at") })
        assertTrue(AudiobookProgressMigration.STATEMENTS.none { it.startsWith("ALTER TABLE tracks") })
        assertTrue(AudiobookProgressMigration.STATEMENTS.none { it.startsWith("DROP") })
        assertTrue(AudiobookProgressMigration.STATEMENTS.single().startsWith("CREATE TABLE IF NOT EXISTS"))
    }

    @Test fun audiobookProgressIsOneRowPerBookWithNoCascadingDelete() {
        val create = AudiobookProgressTable.CREATE
        assertEquals("audiobook_progress", AudiobookProgressTable.NAME)
        assertTrue(create.contains("folder_key TEXT PRIMARY KEY"))
        assertTrue(create.contains("track_id INTEGER NOT NULL"))
        assertTrue(create.contains("position_ms INTEGER NOT NULL"))
        assertTrue(create.contains("updated_at INTEGER NOT NULL"))
        assertTrue(!create.contains("FOREIGN KEY"))
        assertTrue(!create.contains("ON DELETE CASCADE"))
    }

    @Test fun libraryResetAlsoForgetsAudiobookProgress() {
        val source = java.io.File(repositoryRoot(), "app/src/main/kotlin/com/schulzcode/y2player/library/LibraryDatabase.kt")
            .readText()
        val reset = source.substringAfter("fun resetLibrary()").substringBefore("\n    }")
        assertTrue(reset.contains("AudiobookProgressTable.NAME"))
    }

    @Test fun libraryResetWaitsForScannerAndQueuedStateWrites() {
        val source = java.io.File(
            repositoryRoot(),
            "app/src/main/kotlin/com/schulzcode/y2player/library/LibraryRepository.kt"
        ).readText()
        val reset = source.substringAfter("fun resetLibrary(").substringBefore("\n    fun findTrack")

        assertTrue(reset.indexOf("scanExecutor.execute") < reset.indexOf("stateExecutor.execute"))
        assertTrue(reset.indexOf("stateExecutor.execute") < reset.indexOf("database.resetLibrary()"))
        assertTrue(reset.indexOf("database.resetLibrary()") < reset.indexOf("scan(ScanReason.MANUAL)"))
    }

    @Test fun freshInstallCreatesTheAudiobookTable() {
        val source = java.io.File(repositoryRoot(), "app/src/main/kotlin/com/schulzcode/y2player/library/LibraryDatabase.kt")
            .readText()
        val create = source.substringAfter("private fun createUserLibrary").substringBefore("\n    }")
        assertTrue(create.contains("AudiobookProgressTable.CREATE"))
        assertTrue(source.contains("if (version < 14)"))
    }

    @Test fun scanSchemaCreatesTheMeasuredRelativePathLookupIndex() {
        val source = java.io.File(repositoryRoot(), "app/src/main/kotlin/com/schulzcode/y2player/library/LibraryDatabase.kt")
            .readText()
        assertTrue(source.contains("tracks_volume_relative_nocase_idx"))
        assertTrue(source.contains("ON tracks(volume_id, relative_path COLLATE NOCASE)"))
        assertTrue(source.contains("if (version < 12)"))
    }

    private fun repositoryRoot(): java.io.File {
        var directory: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (java.io.File(directory, "app/src/main").isDirectory) return directory
            directory = directory.parentFile
        }
        throw AssertionError("repository root not found")
    }
}
