package com.schulzcode.y2player.library

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryScanWakeLockArchitectureTest {
    @Test fun brandNewSchemaCommitsBeforeWalIsEnabled() {
        val databaseSource = File(
            repositoryRoot(),
            "app/src/main/kotlin/com/schulzcode/y2player/library/LibraryDatabase.kt"
        ).readText()
        val repositorySource = File(
            repositoryRoot(),
            "app/src/main/kotlin/com/schulzcode/y2player/library/LibraryRepository.kt"
        ).readText()
        val ensureOpen = databaseSource.substring(
            databaseSource.indexOf("fun ensureOpen()"),
            databaseSource.indexOf("override fun onUpgrade")
        )

        assertTrue(databaseSource.contains("if (appContext.getDatabasePath(DATABASE_NAME).isFile)"))
        assertTrue(ensureOpen.indexOf("writableDatabase") >= 0)
        assertTrue(ensureOpen.indexOf("requestWriteAheadLogging()") > ensureOpen.indexOf("writableDatabase"))
        assertTrue(repositorySource.contains("database.ensureOpen()\n                loadState("))
    }

    @Test fun scanOwnsAPartialWakeLockForTheWholeWorkerTask() {
        val root = repositoryRoot()
        val source = File(
            root,
            "app/src/main/kotlin/com/schulzcode/y2player/library/LibraryRepository.kt"
        ).readText()
        val taskWrapper = source.substring(
            source.indexOf("private fun executeScan"),
            source.indexOf("private fun acquireScanWakeLock")
        )
        val acquire = taskWrapper.indexOf("val wakeLockAcquired = acquireScanWakeLock()")
        val submit = taskWrapper.indexOf("scanExecutor.execute {", acquire)
        val guardedWork = taskWrapper.indexOf("try {", submit)
        val cleanup = taskWrapper.indexOf("finally {", guardedWork)
        val release = taskWrapper.indexOf("releaseScanWakeLock(wakeLockAcquired)", cleanup)

        assertTrue(source.contains("PowerManager.PARTIAL_WAKE_LOCK"))
        assertTrue(source.contains("setReferenceCounted(true)"))
        assertTrue(acquire >= 0)
        assertTrue(submit > acquire)
        assertTrue(guardedWork > submit)
        assertTrue(cleanup > guardedWork)
        assertTrue(release > cleanup)
    }

    @Test fun manifestGrantsWakeLockPermission() {
        val manifest = File(repositoryRoot(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.WAKE_LOCK"))
    }

    private fun repositoryRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "app/src/main/AndroidManifest.xml").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("repository root not found")
    }
}
