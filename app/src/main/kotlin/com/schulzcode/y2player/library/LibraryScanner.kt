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

enum class CoverageGap { ROOT_UNREADABLE, DIRECTORY_UNREADABLE, FILE_LIMIT }

data class ScanCost(
    val filesRead: Int = 0,
    val bytesRead: Long = 0,
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
    val complete: Boolean get() = !cancelled && coverageGap == null
}

class LibraryScanner(private val metadataReader: MetadataReader = MetadataReader()) {
    fun scan(
        root: StorageRoot,
        fingerprintLookup: (List<String>) -> Map<String, TrackFingerprint>,
        cancellation: ScanCancellation,
        onBatch: (List<ScannedFile>) -> Unit,
        onProgress: (path: String, processed: Int) -> Unit,
        playbackActive: () -> Boolean = { false },
        profiler: ScanProfiler = ScanProfiler(enabled = false)
    ): ScanOutcome {
        val stack = ArrayDeque<File>()
        val visited = HashSet<String>()
        val seenFiles = HashSet<String>()
        val rootPrefixLength = rootCanonicalPrefixLength(root)
        var audioBuffer = ArrayList<File>(BATCH_SIZE)
        val playlists = ArrayList<File>()
        var processed = 0
        var coverageGap: CoverageGap? = null
        var recoverableErrors = 0
        var limitReached = false
        var costFilesRead = 0
        var costBytesRead = 0L
        var costMetadataMs = 0L
        var costYieldMs = 0L
        var costYields = 0
        var groupFiles = 0
        var groupNanos = 0L
        val rootSetupStarted = profiler.start()
        val rootCanonical = try {
            root.directory.canonicalPath.trimEnd(File.separatorChar)
        } catch (_: IOException) {
            profiler.stop(ScanPhase.ROOT_SETUP, rootSetupStarted)
            return ScanOutcome(
                processedFiles = 0,
                cancelled = false,
                playlistFiles = emptyList(),
                recoverableErrors = 1,
                coverageGap = CoverageGap.ROOT_UNREADABLE
            )
        } catch (_: SecurityException) {
            profiler.stop(ScanPhase.ROOT_SETUP, rootSetupStarted)
            return ScanOutcome(
                processedFiles = 0,
                cancelled = false,
                playlistFiles = emptyList(),
                recoverableErrors = 1,
                coverageGap = CoverageGap.ROOT_UNREADABLE
            )
        }
        stack.add(root.directory)
        profiler.stop(ScanPhase.ROOT_SETUP, rootSetupStarted)

        fun flush() {
            if (audioBuffer.isEmpty() || cancellation.isCancelled()) return
            val files = audioBuffer
            audioBuffer = ArrayList(BATCH_SIZE)
            val pathStarted = profiler.start()
            val paths = ArrayList<String>(files.size)
            files.forEach { paths.add(it.absolutePath) }
            profiler.stop(ScanPhase.PATH_BUILD, pathStarted)
            val fingerprintStarted = profiler.start()
            val known = fingerprintLookup(paths)
            profiler.stop(ScanPhase.FINGERPRINT_QUERY, fingerprintStarted)
            val batch = ArrayList<ScannedFile>(files.size)
            files.forEachIndexed { index, file ->
                if (cancellation.isCancelled()) return@forEachIndexed
                val path = paths[index]
                val statStarted = profiler.start()
                if (!file.isFile || !file.canRead()) {
                    profiler.stop(ScanPhase.FILE_STAT_COMPARE, statStarted)
                    recoverableErrors += 1
                    return@forEachIndexed
                }
                val fileSize = file.length()
                if (fileSize <= 0L) {
                    profiler.stop(ScanPhase.FILE_STAT_COMPARE, statStarted)
                    recoverableErrors += 1
                    return@forEachIndexed
                }
                val modifiedAt = file.lastModified()
                val cached = known[path]
                val changed = cached == null || cached.fileSize != fileSize || cached.modifiedAt != modifiedAt
                profiler.stop(ScanPhase.FILE_STAT_COMPARE, statStarted)
                if (!changed) {
                    batch += ScannedFile(path, null)
                    processed += 1
                    return@forEachIndexed
                }
                var draft: TrackDraft? = null
                var failed = false
                val startedAt = System.nanoTime()
                try {
                    draft = metadataReader.read(root, file, fileSize, modifiedAt, profiler)
                } catch (_: Exception) {
                    recoverableErrors += 1
                    failed = true
                }
                val elapsed = System.nanoTime() - startedAt
                groupNanos += elapsed
                groupFiles += 1
                costFilesRead += 1
                costBytesRead += draft?.metadataBytesRead ?: 0L
                costMetadataMs += elapsed / NANOS_PER_MS
                if (groupFiles >= YIELD_FILE_INTERVAL) {
                    val yieldStarted = profiler.start()
                    val yieldedMs = yieldToPlayback(cancellation, playbackActive, groupNanos)
                    if (yieldedMs >= 0L) {
                        profiler.stop(ScanPhase.PLAYBACK_YIELD, yieldStarted)
                        costYieldMs += yieldedMs
                        costYields += 1
                    }
                    groupFiles = 0
                    groupNanos = 0L
                }
                if (failed) return@forEachIndexed
                batch += ScannedFile(path, draft)
                processed += 1
            }
            if (batch.isNotEmpty() && !cancellation.isCancelled()) onBatch(batch)
        }

        while (stack.isNotEmpty() && !cancellation.isCancelled() && !limitReached) {
            val directory = stack.removeLast()
            val canonicalStarted = profiler.start()
            val canonical = try {
                directory.canonicalPath
            } catch (error: IOException) {
                profiler.stop(ScanPhase.DIRECTORY_CANONICAL, canonicalStarted)
                if (cancellation.isCancelled()) break
                coverageGap = CoverageGap.DIRECTORY_UNREADABLE
                recoverableErrors += 1
                continue
            } catch (error: SecurityException) {
                profiler.stop(ScanPhase.DIRECTORY_CANONICAL, canonicalStarted)
                if (cancellation.isCancelled()) break
                coverageGap = CoverageGap.DIRECTORY_UNREADABLE
                recoverableErrors += 1
                continue
            }
            profiler.stop(ScanPhase.DIRECTORY_CANONICAL, canonicalStarted)
            if (canonical != rootCanonical &&
                !canonical.startsWith(rootCanonical + File.separator)
            ) {
                recoverableErrors += 1
                continue
            }
            if (!visited.add(canonical)) continue
            val progressStarted = profiler.start()
            onProgress(canonical, processed)
            profiler.stop(ScanPhase.PROGRESS_CALLBACK, progressStarted)
            val listStarted = profiler.start()
            val children = directory.listFiles()
            profiler.stop(ScanPhase.DIRECTORY_LIST, listStarted)
            if (children == null) {
                if (cancellation.isCancelled()) break
                coverageGap = CoverageGap.DIRECTORY_UNREADABLE
                recoverableErrors += 1
                continue
            }
            var discoveryStarted = profiler.start()
            for (child in children) {
                if (cancellation.isCancelled()) break
                if (child.isDirectory) {
                    if (!shouldSkipDirectory(child)) stack.add(child)
                    continue
                }
                if (!child.isFile) continue
                if (isHiddenFile(child.name)) continue
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
                        if (audioBuffer.size >= BATCH_SIZE) {
                            profiler.stop(ScanPhase.DISCOVERY_FILTER, discoveryStarted)
                            flush()
                            discoveryStarted = profiler.start()
                        }
                        if ((processed + audioBuffer.size) % 25 == 0) {
                            val itemProgressStarted = profiler.start()
                            onProgress(child.absolutePath, processed + audioBuffer.size)
                            profiler.stop(ScanPhase.PROGRESS_CALLBACK, itemProgressStarted)
                        }
                    }
                }
            }
            profiler.stop(ScanPhase.DISCOVERY_FILTER, discoveryStarted)
        }
        flush()
        val cancelled = cancellation.isCancelled()
        return ScanOutcome(
            processedFiles = processed,
            cancelled = cancelled,
            playlistFiles = playlists,
            recoverableErrors = recoverableErrors,
            coverageGap = coverageGap,
            cost = ScanCost(
                filesRead = costFilesRead,
                bytesRead = costBytesRead,
                metadataMs = costMetadataMs,
                yieldMs = costYieldMs,
                yields = costYields
            )
        )
    }

    private fun yieldToPlayback(
        cancellation: ScanCancellation,
        playbackActive: () -> Boolean,
        workNanos: Long
    ): Long {
        if (cancellation.isCancelled()) return NO_YIELD
        if (!runCatching { playbackActive() }.getOrDefault(false)) return NO_YIELD
        val target = (workNanos / YIELD_WORK_DIVISOR / NANOS_PER_MS)
            .coerceIn(MIN_YIELD_MS, MAX_YIELD_MS)
        val startedAt = System.nanoTime()
        runCatching { Thread.sleep(target) }
            .onFailure { if (it is InterruptedException) Thread.currentThread().interrupt() }
        return (System.nanoTime() - startedAt) / NANOS_PER_MS
    }

    private fun isHiddenFile(name: String): Boolean = name.startsWith('.')

    private fun shouldSkipDirectory(file: File): Boolean {
        val name = file.name
        return name.startsWith('.') || name.equals("Android", true) || name.equals("LOST.DIR", true) ||
            name.equals("System Volume Information", true) || name.equals("Y2Player", true)
    }

    private fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val length = name.length - dot - 1
        if (length > MAX_EXTENSION_LENGTH) return null
        return name.substring(dot + 1).lowercase()
    }

    private fun rootCanonicalPrefixLength(root: StorageRoot): Int =
        root.directory.absolutePath.trimEnd(File.separatorChar).length + 1

    companion object {
        // 401 and 402 bindings, inside SQLite's 999-variable limit on API 19.
        const val BATCH_SIZE = 400

        private const val NANOS_PER_MS = 1_000_000L
        private const val NO_YIELD = -1L

        internal const val YIELD_FILE_INTERVAL = 8

        private const val YIELD_WORK_DIVISOR = 4

        internal const val MIN_YIELD_MS = 5L

        private const val MAX_YIELD_MS = 150L
        private const val MAX_PLAYLIST_FILES = 1_000
        const val MAX_AUDIO_FILES = 100_000
        private const val MAX_EXTENSION_LENGTH = 5
        val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")
        val SUPPORTED_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "wave", "ogg", "oga", "opus",
            "m4a", "m4r", "aac", "alac", "aif", "aiff", "aifc"
        )
    }
}

internal object PathIdentity {
    fun key(path: String): String = path.replace('\\', '/').trimEnd('/').lowercase(Locale.US)
}
