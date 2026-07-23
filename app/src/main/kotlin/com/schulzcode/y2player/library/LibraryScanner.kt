package com.schulzcode.y2player.library

import com.schulzcode.y2player.core.model.TrackDraft
import com.schulzcode.y2player.storage.StorageRoot
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class ScanCancellation {
    private val cancelled = AtomicBoolean(false)
    fun cancel() = cancelled.set(true)
    fun isCancelled(): Boolean = cancelled.get()
}

data class TrackFingerprint(val fileSize: Long, val modifiedAt: Long)
data class ScannedFile(val absolutePath: String, val changedDraft: TrackDraft?)
data class ScanOutcome(
    val processedFiles: Int,
    val cancelled: Boolean,
    val complete: Boolean,
    val playlistFiles: List<File>,
    val recoverableErrors: Int = 0
)

class LibraryScanner(private val metadataReader: MetadataReader = MetadataReader()) {
    /** Frees the shared retriever's native resources; call after a scan completes. */
    fun releaseMetadata() = metadataReader.release()

    fun scan(
        root: StorageRoot,
        fingerprintLookup: (List<String>) -> Map<String, TrackFingerprint>,
        cancellation: ScanCancellation,
        onBatch: (List<ScannedFile>) -> Unit,
        onProgress: (path: String, processed: Int) -> Unit
    ): ScanOutcome {
        val stack = ArrayDeque<File>()
        val visited = HashSet<String>()
        val seenFiles = HashSet<String>()
        val audioBuffer = ArrayList<File>(BATCH_SIZE)
        val playlists = ArrayList<File>()
        var processed = 0
        var incomplete = false
        var recoverableErrors = 0
        var limitReached = false
        val rootCanonical = try {
            root.directory.canonicalPath.trimEnd(File.separatorChar)
        } catch (_: IOException) {
            return ScanOutcome(0, cancelled = false, complete = false, emptyList(), recoverableErrors = 1)
        } catch (_: SecurityException) {
            return ScanOutcome(0, cancelled = false, complete = false, emptyList(), recoverableErrors = 1)
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
                    incomplete = true
                    recoverableErrors += 1
                    return@forEach
                }
                val cached = known[file.absolutePath]
                val changed = cached == null || cached.fileSize != file.length() || cached.modifiedAt != file.lastModified()
                val draft = if (changed) try {
                    metadataReader.read(root, file)
                } catch (_: Exception) {
                    incomplete = true
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
        }

        while (stack.isNotEmpty() && !cancellation.isCancelled() && !limitReached) {
            val directory = stack.removeLast()
            val canonical = try {
                directory.canonicalPath
            } catch (error: IOException) {
                if (cancellation.isCancelled()) break
                incomplete = true
                recoverableErrors += 1
                continue
            } catch (error: SecurityException) {
                if (cancellation.isCancelled()) break
                incomplete = true
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
                incomplete = true
                recoverableErrors += 1
                continue
            }
            if (!visited.add(canonical)) continue
            onProgress(canonical, processed)
            val children = directory.listFiles()
            if (children == null) {
                if (cancellation.isCancelled()) break
                incomplete = true
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
                val extension = child.extension.lowercase()
                when {
                    extension in PLAYLIST_EXTENSIONS -> if (playlists.size < MAX_PLAYLIST_FILES) playlists += child
                    extension in SUPPORTED_EXTENSIONS -> {
                        if (!seenFiles.add(PathIdentity.key(child.absolutePath))) continue
                        if (processed + audioBuffer.size >= MAX_AUDIO_FILES) {
                            incomplete = true
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
        return ScanOutcome(processed, cancelled, complete = !cancelled && !incomplete, playlists, recoverableErrors)
    }

    private fun shouldSkipDirectory(file: File): Boolean {
        val name = file.name
        return name.startsWith('.') || name.equals("Android", true) || name.equals("LOST.DIR", true) ||
            name.equals("System Volume Information", true) || name.equals("Y2Player", true)
    }

    companion object {
        const val BATCH_SIZE = 64
        private const val MAX_PLAYLIST_FILES = 1_000
        private const val MAX_AUDIO_FILES = 100_000
        val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")
        val SUPPORTED_EXTENSIONS = setOf(
            "mp3", "mp2", "flac", "wav", "wave", "ogg", "oga", "opus", "m4a", "m4r", "aac",
            "ape", "wma", "amr", "wv", "aif", "aiff", "aifc", "ac3", "mka", "dsf", "dff"
        )
    }
}
