package com.schulzcode.y2player.library

import com.schulzcode.y2player.storage.StorageRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(CoverageGap.DIRECTORY_UNREADABLE, outcome.coverageGap)
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
            // Cancelling is not a coverage fault, so it must not raise the alert.
            assertNull(outcome.coverageGap)
        } finally { root.deleteRecursively() }
    }

    /**
     * The reported bug: a card with one zero-byte file reported every rescan as
     * incomplete forever, so "Library needs attention" could never be cleared —
     * and `finishScan` never ran, so deleted files also stayed in the library.
     *
     * A file-level problem is recoverable. It must be counted and skipped, and the
     * volume's verdict must stay clean.
     */
    @Test fun zeroByteFileIsRecoverableAndDoesNotSpoilTheVerdict() {
        val root = File(System.getProperty("java.io.tmpdir"), "y2-empty-file-${System.nanoTime()}")
        val music = File(root, "Music").apply { mkdirs() }
        try {
            File(music, "truncated.mp3").createNewFile()
            val batches = ArrayList<ScannedFile>()
            val outcome = LibraryScanner().scan(
                StorageRoot("sdcard", root), { emptyMap() }, ScanCancellation(), { batches += it }, { _, _ -> }
            )

            assertTrue("One bad file must not make the scan incomplete", outcome.complete)
            assertNull(outcome.coverageGap)
            assertEquals(1, outcome.recoverableErrors)
            assertEquals(0, outcome.processedFiles)
            assertTrue("The unreadable file must not be indexed", batches.isEmpty())
        } finally { root.deleteRecursively() }
    }

    /**
     * Copying an album from macOS leaves an AppleDouble sidecar per track:
     * `._01. Foreword.flac` beside `01. Foreword.flac`. They end in `.flac` but are
     * a few KB of extended attributes, so indexing them produced a duplicate album
     * with no metadata that failed at `setDataSource` — invisible on the desktop and
     * on the device, so undeletable, and immune to reindexing.
     *
     * They must be skipped outright, and not counted as errors: nothing went wrong.
     */
    @Test fun appleDoubleSidecarsAreNotIndexed() {
        val root = File(System.getProperty("java.io.tmpdir"), "y2-appledouble-${System.nanoTime()}")
        val album = File(root, "Music/Meteora").apply { mkdirs() }
        try {
            File(album, "._01. Foreword.flac").writeBytes(ByteArray(4_096) { 1 })
            File(album, "._13. Numb.flac").writeBytes(ByteArray(4_096) { 1 })
            File(album, ".DS_Store").writeBytes(ByteArray(64))
            File(album, "._cover.m3u").writeBytes(ByteArray(32))
            val batches = ArrayList<ScannedFile>()
            val outcome = LibraryScanner().scan(
                StorageRoot("internal", root), { emptyMap() }, ScanCancellation(), { batches += it }, { _, _ -> }
            )

            assertTrue(batches.isEmpty())
            assertEquals(0, outcome.processedFiles)
            // Skipping a hidden file is not a fault, so it must not be counted as one.
            assertEquals(0, outcome.recoverableErrors)
            assertTrue(outcome.complete)
            assertTrue("A hidden .m3u must not be imported either", outcome.playlistFiles.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyVolumeIsACompleteScan() {
        val root = File(System.getProperty("java.io.tmpdir"), "y2-empty-${System.nanoTime()}").apply { mkdirs() }
        try {
            val outcome = LibraryScanner().scan(
                StorageRoot("sdcard", root), { emptyMap() }, ScanCancellation(), {}, { _, _ -> }
            )
            assertTrue(outcome.complete)
            assertEquals(0, outcome.recoverableErrors)
        } finally { root.deleteRecursively() }
    }
}
