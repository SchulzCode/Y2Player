package com.schulzcode.y2player.library

import com.schulzcode.y2player.storage.StorageRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryScannerTest {
    private fun scanner() = LibraryScanner(MetadataReader {
        FfmpegMetadata(errorCategory = 3, errorDetail = "invalid test fixture", bytesRead = 512)
    })

    @Test fun scanBatchStaysBelowApi19SqliteVariableLimit() {
        // Fingerprint SELECT binds volume + paths; seen-token UPDATE binds token,
        // volume + paths. The latter is the limiting statement.
        assertTrue(LibraryScanner.BATCH_SIZE + 2 < 999)
    }

    @Test
    fun missingRootDoesNotCompleteAsASuccessfulScan() {
        val missing = File(System.getProperty("java.io.tmpdir"), "y2-missing-${System.nanoTime()}")
        val outcome = scanner().scan(
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
            val outcome = scanner().scan(
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
            val outcome = scanner().scan(
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
            val outcome = scanner().scan(
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

    /**
     * Writes [count] files that look like audio but are not, so the metadata read is
     * attempted and fails fast. What is under test is the accounting and the pacing
     * around that read, not the read itself.
     */
    private fun volumeOf(count: Int, bytes: Int = 1_024): File {
        val root = File(System.getProperty("java.io.tmpdir"), "y2-cost-${System.nanoTime()}")
        File(root, "Music").apply { mkdirs() }.let { dir ->
            repeat(count) { File(dir, "track$it.mp3").writeBytes(ByteArray(bytes) { 7 }) }
        }
        return root
    }

    private fun scan(root: File, playing: Boolean) = scanner().scan(
        StorageRoot("internal", root), { emptyMap() }, ScanCancellation(), {}, { _, _ -> },
        playbackActive = { playing }
    )

    /** Nothing is playing, so there is nothing to yield to and the scan runs flat out. */
    @Test fun noBackOffWhenNothingIsPlaying() {
        val root = volumeOf(LibraryScanner.YIELD_FILE_INTERVAL * 2)
        try {
            val cost = scan(root, playing = false).cost
            assertEquals(0L, cost.yieldMs)
            assertEquals(0, cost.yields)
            assertEquals(LibraryScanner.YIELD_FILE_INTERVAL * 2, cost.filesRead)
        } finally { root.deleteRecursively() }
    }

    /**
     * The reported symptom at ~10k tracks was choppy audio for the whole scan. The
     * back-off existed, but fired once per 64-file database batch, which left about
     * three seconds of uninterrupted extraction between gaps. It has to be driven by
     * a count of its own, well below BATCH_SIZE.
     */
    @Test fun backsOffOncePerFileIntervalRatherThanOncePerBatch() {
        val groups = 3
        val root = volumeOf(LibraryScanner.YIELD_FILE_INTERVAL * groups)
        try {
            val cost = scan(root, playing = true).cost
            assertEquals("one yield per $groups groups within a single batch", groups, cost.yields)
            assertTrue(
                "each yield must actually pause",
                cost.yieldMs >= LibraryScanner.MIN_YIELD_MS * groups - 1
            )
            assertTrue("the interval must be well under a database batch", LibraryScanner.YIELD_FILE_INTERVAL < LibraryScanner.BATCH_SIZE)
        } finally { root.deleteRecursively() }
    }

    /** A partial group must not yield, or the interval would mean nothing. */
    @Test fun aPartialGroupDoesNotBackOff() {
        val root = volumeOf(LibraryScanner.YIELD_FILE_INTERVAL - 1)
        try {
            assertEquals(0, scan(root, playing = true).cost.yields)
        } finally { root.deleteRecursively() }
    }

    @Test fun costAccountsBytesAndFilesRead() {
        val root = volumeOf(4, bytes = 2_048)
        try {
            val cost = scan(root, playing = false).cost
            assertEquals(4, cost.filesRead)
            assertEquals("count actual probe I/O, not source file sizes", 4L * 512, cost.bytesRead)
        } finally { root.deleteRecursively() }
    }

    /** An unchanged library reads no metadata, so the back-off costs it nothing. */
    @Test fun unchangedFilesAreNeitherReadNorPacedAgainst() {
        val root = volumeOf(LibraryScanner.YIELD_FILE_INTERVAL * 2)
        try {
            val fingerprints = File(root, "Music").listFiles()!!.associate {
                it.absolutePath to TrackFingerprint(it.length(), it.lastModified())
            }
            val outcome = scanner().scan(
                StorageRoot("internal", root), { fingerprints }, ScanCancellation(), {}, { _, _ -> },
                playbackActive = { true }
            )
            assertEquals(0, outcome.cost.filesRead)
            assertEquals(0, outcome.cost.yields)
            assertEquals(0L, outcome.cost.yieldMs)
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyVolumeIsACompleteScan() {
        val root = File(System.getProperty("java.io.tmpdir"), "y2-empty-${System.nanoTime()}").apply { mkdirs() }
        try {
            val outcome = scanner().scan(
                StorageRoot("sdcard", root), { emptyMap() }, ScanCancellation(), {}, { _, _ -> }
            )
            assertTrue(outcome.complete)
            assertEquals(0, outcome.recoverableErrors)
        } finally { root.deleteRecursively() }
    }
}
