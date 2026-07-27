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

/**
 * What a scan actually cost, so the next stutter report can be answered instead of
 * estimated.
 *
 * [bytesRead] is the discriminator worth having: metadata extraction is supposed to
 * read a bounded header, so cost should track the *number* of files. If it tracks
 * their size instead, the platform extractor is reading file bodies — which for a
 * library of large VBR MP3s would dwarf everything else and would change what is
 * worth optimising next.
 */
data class ScanCost(
    /** Files whose metadata was actually extracted, not merely stat-ed. */
    val filesRead: Int = 0,
    val bytesRead: Long = 0,
    /** Wall time inside [MetadataReader.read], the part that runs in the media server. */
    val metadataMs: Long = 0,
    val yieldMs: Long = 0,
    val yields: Int = 0
) {
    operator fun plus(other: ScanCost) = ScanCost(
        filesRead = filesRead + other.filesRead,
        bytesRead = bytesRead + other.bytesRead,
        metadataMs = metadataMs + other.metadataMs,
        yieldMs = yieldMs + other.yieldMs,
        yields = yields + other.yields
    )
}

data class ScanOutcome(
    val processedFiles: Int,
    val cancelled: Boolean,
    val playlistFiles: List<File>,
    val recoverableErrors: Int = 0,
    val coverageGap: CoverageGap? = null,
    val cost: ScanCost = ScanCost()
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
        var cost = ScanCost()
        // Carried across batches, not reset per batch: the back-off interval is
        // deliberately independent of BATCH_SIZE, which exists to size database
        // transactions and has no business deciding how often audio gets the
        // media server back.
        var groupFiles = 0
        var groupNanos = 0L
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
                if (!changed) {
                    batch += ScannedFile(file.absolutePath, null)
                    processed += 1
                    return@forEach
                }
                var draft: TrackDraft? = null
                var failed = false
                val startedAt = System.nanoTime()
                try {
                    draft = metadataReader.read(root, file)
                } catch (_: Exception) {
                    // Same reasoning as an unreadable file: one bad decode says
                    // nothing about whether the walk covered the volume.
                    recoverableErrors += 1
                    failed = true
                }
                // Counted whether or not it succeeded: a file that took time and
                // then threw still spent that time inside the media server.
                val elapsed = System.nanoTime() - startedAt
                groupNanos += elapsed
                groupFiles += 1
                cost = cost.copy(
                    filesRead = cost.filesRead + 1,
                    bytesRead = cost.bytesRead + file.length(),
                    metadataMs = cost.metadataMs + elapsed / NANOS_PER_MS
                )
                if (groupFiles >= YIELD_FILE_INTERVAL) {
                    cost += yieldToPlayback(cancellation, playbackActive, groupNanos)
                    groupFiles = 0
                    groupNanos = 0L
                }
                if (failed) return@forEach
                batch += ScannedFile(file.absolutePath, draft)
                processed += 1
            }
            // Re-check immediately before handing the batch over: cancellation is
            // cooperative, and a volume that was removed mid-batch must not have
            // its rows written back as available by a lagging write.
            if (batch.isNotEmpty() && !cancellation.isCancelled()) onBatch(batch)
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
            coverageGap = coverageGap,
            cost = cost
        )
    }

    /**
     * Leaves a gap in the scan's I/O while audio is playing.
     *
     * Background thread priority caps the scan's *CPU* share, but the contention
     * that produces audible stutter is elsewhere: metadata extraction runs inside
     * the media server that is decoding the audio, and reads from the same card
     * the native playback decoder is streaming from. A short pause hands both back.
     *
     * Two things about the shape of this matter more than the length of the pause:
     *
     * The pause is proportional to what the preceding files actually cost, not a
     * constant. Per-file cost swings by an order of magnitude between a small MP3
     * and a 24-bit FLAC, so any fixed number is either useless on a slow library or
     * wasteful on a fast one. A quarter of measured cost gives a predictable duty
     * cycle instead, clamped at both ends so one pathological file cannot stall the
     * scan and a trivial group still yields something.
     *
     * And it fires every [YIELD_FILE_INTERVAL] files rather than once per batch.
     * Total relief was never the problem: at 10k tracks a per-batch pause left
     * roughly three seconds of uninterrupted extraction between gaps, which is far
     * more than enough to starve the playback PCM buffer, while amounting to under 1%
     * of the scan. It is the length of the uninterrupted block that is audible.
     *
     * Still free in the common case: a rescan of an unchanged library reads no
     * metadata, so it never reaches here.
     */
    private fun yieldToPlayback(
        cancellation: ScanCancellation,
        playbackActive: () -> Boolean,
        workNanos: Long
    ): ScanCost {
        if (cancellation.isCancelled()) return ScanCost()
        if (!runCatching { playbackActive() }.getOrDefault(false)) return ScanCost()
        val target = (workNanos / YIELD_WORK_DIVISOR / NANOS_PER_MS)
            .coerceIn(MIN_YIELD_MS, MAX_YIELD_MS)
        val startedAt = System.nanoTime()
        // Interruption is how shutdownNow stops this thread; treat it as a
        // cancellation rather than swallowing it.
        runCatching { Thread.sleep(target) }
            .onFailure { if (it is InterruptedException) Thread.currentThread().interrupt() }
        return ScanCost(yieldMs = (System.nanoTime() - startedAt) / NANOS_PER_MS, yields = 1)
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

        private const val NANOS_PER_MS = 1_000_000L

        /**
         * How many newly-read files may pass before audio gets a gap.
         *
         * Deliberately much smaller than [BATCH_SIZE]: that constant sizes database
         * transactions, and using it here coupled the back-off interval to something
         * with no relationship to audio. Eight files is a few hundred milliseconds
         * of extraction on the Y2, short enough not to drain a playback buffer.
         */
        internal const val YIELD_FILE_INTERVAL = 8

        /** Pause for a quarter of what the preceding files cost: a ~20% duty cycle. */
        private const val YIELD_WORK_DIVISOR = 4

        /** A group this cheap still yields, so the interval cannot become a no-op. */
        internal const val MIN_YIELD_MS = 5L

        /** One slow file must not turn into a visible stall in scan progress. */
        private const val MAX_YIELD_MS = 150L
        private const val MAX_PLAYLIST_FILES = 1_000
        const val MAX_AUDIO_FILES = 100_000
        /** Longest extension in [SUPPORTED_EXTENSIONS] / [PLAYLIST_EXTENSIONS] is 4. */
        private const val MAX_EXTENSION_LENGTH = 5
        val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")
        /**
         * What the scanner will index.
         *
         * This is the real gate — an extension absent here is never seen by the
         * library at all, whatever the codec tables say. It is deliberately
         * narrower than "every file": a card full of artwork, playlists exported
         * as text and stray documents must not turn into broken track rows.
         *
         * Kept in agreement with [AudioCodecSupport] by
         * `FfmpegBuildCapabilitiesTest`, which fails if this list and the
         * playable-extension list disagree in either direction. `aif/aiff/aifc`
         * were missing while the FFmpeg build already carried the aiff demuxer
         * and the big-endian PCM decoders, so AIFF was labelled playable and
         * then never indexed.
         *
         * `mp4` is intentionally absent: it is overwhelmingly a video container,
         * and `m4a`/`m4r` are the audio conventions for the same demuxer.
         */
        val SUPPORTED_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "wave", "ogg", "oga", "opus",
            "m4a", "m4r", "aac", "alac", "aif", "aiff", "aifc"
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
