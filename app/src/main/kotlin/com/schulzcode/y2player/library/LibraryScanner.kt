package com.schulzcode.y2player.library

import com.schulzcode.y2player.core.model.TrackDraft
import com.schulzcode.y2player.storage.StorageRoot
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScanCancellation {
    private val cancelled = AtomicBoolean(false)
    fun cancel() = cancelled.set(true)
    fun isCancelled(): Boolean = cancelled.get()
}

data class TrackFingerprint(val fileSize: Long, val modifiedAt: Long)
data class ScannedFile(val absolutePath: String, val changedDraft: TrackDraft?)

/**
 * Why a scan cannot promise it visited every file on the volume.
 *
 * Deliberately not the same thing as [ScanOutcome.recoverableErrors]. A gap means
 * tracks that are still present on the card may not have been seen, so
 * `finishScan` must not run for this volume — it marks everything unseen as
 * unavailable, which would hide files that are perfectly fine. A recoverable
 * error is the opposite case: a specific file was visited and found to be
 * unreadable, and letting `finishScan` mark *that* row unavailable is correct.
 *
 * Conflating the two is what made a single zero-byte file on a card report every
 * subsequent rescan as incomplete, permanently.
 */
enum class CoverageGap { ROOT_UNREADABLE, DIRECTORY_UNREADABLE, FILE_LIMIT }

data class ScanOutcome(
    val processedFiles: Int,
    val cancelled: Boolean,
    val playlistFiles: List<File>,
    val recoverableErrors: Int = 0,
    val coverageGap: CoverageGap? = null
) {
    /** Derived, so it can never disagree with [coverageGap]. */
    val complete: Boolean get() = !cancelled && coverageGap == null
}

class LibraryScanner(private val metadataReader: MetadataReader = MetadataReader()) {
    /** Frees the shared retriever's native resources; call after a scan completes. */
    fun releaseMetadata() = metadataReader.release()

    /**
     * @param playbackActive whether audio is playing right now. When it is, the
     *   scan pauses briefly after any batch that actually read metadata — see
     *   [yieldToPlayback].
     */
    fun scan(
        root: StorageRoot,
        fingerprintLookup: (List<String>) -> Map<String, TrackFingerprint>,
        cancellation: ScanCancellation,
        onBatch: (List<ScannedFile>) -> Unit,
        onProgress: (path: String, processed: Int) -> Unit,
        playbackActive: () -> Boolean = { false }
    ): ScanOutcome {
        val stack = ArrayDeque<File>()
        val visited = HashSet<String>()
        // Keyed on the path *below the volume root*, not the absolute path. Both
        // are unique within one volume scan, but at the 100k-file ceiling the
        // shorter key is several megabytes less long-lived heap on a device
        // whose application heap is not large.
        val seenFiles = HashSet<String>()
        val rootPrefixLength = rootCanonicalPrefixLength(root)
        val audioBuffer = ArrayList<File>(BATCH_SIZE)
        val playlists = ArrayList<File>()
        var processed = 0
        // "I cannot guarantee I saw every file on this volume." Only directory-
        // level failures and an early stop set this, because it gates finishScan,
        // which marks everything not seen in this pass as unavailable. A problem
        // with an individual *file* is not a coverage gap and must not set it.
        var coverageGap: CoverageGap? = null
        var recoverableErrors = 0
        var limitReached = false
        val rootCanonical = try {
            root.directory.canonicalPath.trimEnd(File.separatorChar)
        } catch (_: IOException) {
            return ScanOutcome(
                processedFiles = 0,
                cancelled = false,
                playlistFiles = emptyList(),
                recoverableErrors = 1,
                coverageGap = CoverageGap.ROOT_UNREADABLE
            )
        } catch (_: SecurityException) {
            return ScanOutcome(
                processedFiles = 0,
                cancelled = false,
                playlistFiles = emptyList(),
                recoverableErrors = 1,
                coverageGap = CoverageGap.ROOT_UNREADABLE
            )
        }
        stack.add(root.directory)

        fun flush() {
            if (audioBuffer.isEmpty() || cancellation.isCancelled()) return
            val files = audioBuffer.toList()
            audioBuffer.clear()
            val known = fingerprintLookup(files.map { it.absolutePath })
            val batch = ArrayList<ScannedFile>(files.size)
            var readMetadata = false
            files.forEach { file ->
                if (cancellation.isCancelled()) return@forEach
                if (!file.isFile || !file.canRead() || file.length() <= 0L) {
                    // One unreadable or zero-byte file is not a failed scan. It
                    // simply does not get its seen-token refreshed, so finishScan
                    // marks it unavailable — the correct outcome for a file that
                    // cannot be read.
                    recoverableErrors += 1
                    return@forEach
                }
                val cached = known[file.absolutePath]
                val changed = cached == null || cached.fileSize != file.length() || cached.modifiedAt != file.lastModified()
                val draft = if (changed) try {
                    readMetadata = true
                    metadataReader.read(root, file)
                } catch (_: Exception) {
                    // Same reasoning as an unreadable file: one bad decode says
                    // nothing about whether the walk covered the volume.
                    recoverableErrors += 1
                    return@forEach
                } else null
                batch += ScannedFile(file.absolutePath, draft)
                processed += 1
            }
            // Re-check immediately before handing the batch over: cancellation is
            // cooperative, and a volume that was removed mid-batch must not have
            // its rows written back as available by a lagging write.
            if (batch.isNotEmpty() && !cancellation.isCancelled()) onBatch(batch)
            if (readMetadata) yieldToPlayback(cancellation, playbackActive)
        }

        while (stack.isNotEmpty() && !cancellation.isCancelled() && !limitReached) {
            val directory = stack.removeLast()
            val canonical = try {
                directory.canonicalPath
            } catch (error: IOException) {
                if (cancellation.isCancelled()) break
                coverageGap = CoverageGap.DIRECTORY_UNREADABLE
                recoverableErrors += 1
                continue
            } catch (error: SecurityException) {
                if (cancellation.isCancelled()) break
                coverageGap = CoverageGap.DIRECTORY_UNREADABLE
                recoverableErrors += 1
                continue
            }
            // File.isDirectory follows symlinks. Canonical visited paths stop
            // loops, and this boundary additionally prevents a card symlink
            // from walking the internal filesystem as if it belonged to the
            // removable volume.
            if (canonical != rootCanonical &&
                !canonical.startsWith(rootCanonical + File.separator)
            ) {
                // Refusing to follow a link out of the volume is policy, not
                // failure. Anything beyond it has a path outside this root, so it
                // was never indexed as belonging here and finishScan cannot
                // mis-judge it. Counted for visibility only.
                recoverableErrors += 1
                continue
            }
            if (!visited.add(canonical)) continue
            onProgress(canonical, processed)
            val children = directory.listFiles()
            if (children == null) {
                // A directory that cannot be listed is a real gap: its tracks
                // would be wrongly marked unavailable by finishScan.
                if (cancellation.isCancelled()) break
                coverageGap = CoverageGap.DIRECTORY_UNREADABLE
                recoverableErrors += 1
                continue
            }
            for (child in children) {
                if (cancellation.isCancelled()) break
                if (child.isDirectory) {
                    if (!shouldSkipDirectory(child)) stack.add(child)
                    continue
                }
                if (!child.isFile) continue
                if (isHiddenFile(child.name)) continue
                // Resolved without allocating for the many files that have no
                // extension at all, or whose extension is neither audio nor a
                // playlist — the common case on a card with artwork and stray
                // documents in it.
                val extension = extensionOf(child.name) ?: continue
                when {
                    extension in PLAYLIST_EXTENSIONS -> if (playlists.size < MAX_PLAYLIST_FILES) playlists += child
                    extension in SUPPORTED_EXTENSIONS -> {
                        val path = child.absolutePath
                        val key = PathIdentity.key(
                            if (path.length > rootPrefixLength) path.substring(rootPrefixLength) else path
                        )
                        if (!seenFiles.add(key)) continue
                        if (processed + audioBuffer.size >= MAX_AUDIO_FILES) {
                            coverageGap = CoverageGap.FILE_LIMIT
                            limitReached = true
                            break
                        }
                        audioBuffer += child
                        if (audioBuffer.size >= BATCH_SIZE) flush()
                        if ((processed + audioBuffer.size) % 25 == 0) onProgress(child.absolutePath, processed + audioBuffer.size)
                    }
                }
            }
        }
        flush()
        val cancelled = cancellation.isCancelled()
        return ScanOutcome(
            processedFiles = processed,
            cancelled = cancelled,
            playlistFiles = playlists,
            recoverableErrors = recoverableErrors,
            coverageGap = coverageGap
        )
    }

    /**
     * Leaves a gap in the scan's I/O while audio is playing.
     *
     * Background thread priority caps the scan's *CPU* share, but the contention
     * that produces audible stutter is elsewhere: metadata extraction runs inside
     * the media server that is decoding the audio, and reads from the same card
     * MediaPlayer is streaming from. A short pause hands both back.
     *
     * Only after a batch that actually read metadata, which is what makes this
     * nearly free in the common case — a routine rescan of an unchanged library
     * costs a stat per file and no extraction at all, so it never pauses. The
     * cost lands on a first scan or a large import, at roughly
     * [PLAYBACK_YIELD_MS] per [BATCH_SIZE] newly-read files, and only while
     * something is playing.
     */
    private fun yieldToPlayback(cancellation: ScanCancellation, playbackActive: () -> Boolean) {
        if (cancellation.isCancelled()) return
        if (!runCatching { playbackActive() }.getOrDefault(false)) return
        // Interruption is how shutdownNow stops this thread; treat it as a
        // cancellation rather than swallowing it.
        runCatching { Thread.sleep(PLAYBACK_YIELD_MS) }
            .onFailure { if (it is InterruptedException) Thread.currentThread().interrupt() }
    }

    /**
     * Hidden files are never media, whatever their extension says.
     *
     * The case that forced this: copying an album to the player from macOS leaves
     * an AppleDouble sidecar next to every track — `._01. Foreword.flac` beside
     * `01. Foreword.flac`. It is a few KB of extended attributes, but it ends in
     * `.flac`, so the scanner indexed all thirteen of them. They carried no
     * metadata (nothing can parse them) and failed at `setDataSource`, so the user
     * saw a second, broken copy of the album that skipped silently when played —
     * and could not delete it, because the desktop and the device both hide
     * dot-files. Reindexing could not help either: the files are really there.
     *
     * Directories already skip on a leading dot; files did not. Android's own
     * MediaScanner applies the same rule, and no real music file is named this way.
     */
    private fun isHiddenFile(name: String): Boolean = name.startsWith('.')

    private fun shouldSkipDirectory(file: File): Boolean {
        val name = file.name
        return name.startsWith('.') || name.equals("Android", true) || name.equals("LOST.DIR", true) ||
            name.equals("System Volume Information", true) || name.equals("Y2Player", true)
    }

    /**
     * Lower-cased extension, or null when there is none worth looking up. Only
     * the extension is allocated, and only for files that actually have one.
     */
    private fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val length = name.length - dot - 1
        if (length > MAX_EXTENSION_LENGTH) return null
        return name.substring(dot + 1).lowercase()
    }

    /** Length of the volume root prefix that [scan] strips from dedupe keys. */
    private fun rootCanonicalPrefixLength(root: StorageRoot): Int =
        root.directory.absolutePath.trimEnd(File.separatorChar).length + 1

    companion object {
        const val BATCH_SIZE = 64

        /**
         * Pause per metadata-reading batch while audio plays. At 64 files a batch
         * this adds roughly 12 s to a 30k-file first scan — paid only when the
         * user is actually listening, which is the only time it buys anything.
         */
        private const val PLAYBACK_YIELD_MS = 25L
        private const val MAX_PLAYLIST_FILES = 1_000
        const val MAX_AUDIO_FILES = 100_000
        /** Longest extension in [SUPPORTED_EXTENSIONS] / [PLAYLIST_EXTENSIONS] is 4. */
        private const val MAX_EXTENSION_LENGTH = 5
        val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")
        val SUPPORTED_EXTENSIONS = setOf(
            "mp3", "mp2", "flac", "wav", "wave", "ogg", "oga", "opus", "m4a", "m4r", "aac",
            "ape", "wma", "amr", "wv", "aif", "aiff", "aifc", "ac3", "mka", "dsf", "dff"
        )
    }
}

/**
 * Case- and separator-insensitive path key.
 *
 * Lives beside its only caller: FAT cards cannot hold two files whose paths
 * differ only in case, so this is what makes "have I already seen this file"
 * answerable during a scan.
 */
internal object PathIdentity {
    fun key(path: String): String = path.replace('\\', '/').trimEnd('/').lowercase(Locale.US)
}
