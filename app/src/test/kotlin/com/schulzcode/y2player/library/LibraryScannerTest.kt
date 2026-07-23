package com.schulzcode.y2player.library

import com.schulzcode.y2player.storage.StorageRoot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryScannerTest {
    @Test
    fun missingRootDoesNotCompleteAsASuccessfulScan() {
        val missing = File(System.getProperty("java.io.tmpdir"), "y2-missing-${System.nanoTime()}")
        val outcome = LibraryScanner().scan(
            root = StorageRoot("sdcard", missing),
            fingerprintLookup = { emptyMap() },
            cancellation = ScanCancellation(),
            onBatch = {},
            onProgress = { _, _ -> }
        )

        assertFalse("An unreadable root must prevent finishScan from running", outcome.complete)
        assertTrue(outcome.recoverableErrors > 0)
    }

    @Test fun cancellationIsNeverReportedAsComplete() {
        val root = File(System.getProperty("java.io.tmpdir"), "y2-cancel-${System.nanoTime()}").apply { mkdirs() }
        try {
            val cancellation = ScanCancellation().apply { cancel() }
            val outcome = LibraryScanner().scan(
                StorageRoot("sdcard", root), { emptyMap() }, cancellation, {}, { _, _ -> }
            )
            assertTrue(outcome.cancelled)
            assertFalse(outcome.complete)
        } finally { root.deleteRecursively() }
    }
}
