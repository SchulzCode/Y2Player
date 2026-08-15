package com.schulzcode.y2player.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLoggerClearTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun clearRemovesTextRotationsAndNativeCrashButNotExportedFiles() {
        val directory = temporaryFolder.newFolder("diagnostics")
        val exported = temporaryFolder.newFolder("exports").resolve("old-export.txt").apply { writeText("keep") }
        directory.resolve("y2player.1.log").writeText("old rotation")
        directory.resolve("y2-native-crash.log").writeText("old crash")
        val logger = DiagnosticLogger(directory)
        logger.warn("Test", "before clear")

        assertTrue(logger.clear())
        logger.warn("Test", "after clear")
        val current = logger.exportTo(temporaryFolder.newFolder("current")).readText()

        assertFalse(current.contains("before clear"))
        assertTrue(current.contains("after clear"))
        assertFalse(directory.resolve("y2player.1.log").exists())
        assertFalse(directory.resolve("y2-native-crash.log").exists())
        assertTrue("an already exported file is outside reset ownership", exported.exists())
    }

    @Test fun exportedReportAfterClearContainsOnlyTheNewGeneration() {
        val logger = DiagnosticLogger(temporaryFolder.newFolder("fresh"))
        logger.warn("Test", "obsolete text marker")
        assertTrue(logger.clear())
        logger.warn("Test", "fresh text marker")

        val report = logger.exportTo(temporaryFolder.newFolder("report")).readText()
        assertFalse(report.contains("obsolete text marker"))
        assertTrue(report.contains("fresh text marker"))
    }
}
