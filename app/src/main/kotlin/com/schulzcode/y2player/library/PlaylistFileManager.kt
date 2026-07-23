package com.schulzcode.y2player.library

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.storage.StorageRoot
import java.io.File

class PlaylistFileManager(private val database: LibraryDatabase) {
    data class ImportResult(val imported: Int, val matchedTracks: Int, val ignoredEntries: Int)
    data class ExportResult(val exported: Int, val directory: File?)

    fun importFiles(files: List<File>): ImportResult {
        var imported = 0
        var matched = 0
        var ignored = 0
        files.asSequence()
            .filter { it.isFile && it.canRead() && it.length() <= MAX_PLAYLIST_BYTES }
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .distinctBy { it.absolutePath }
            .take(MAX_PLAYLIST_FILES)
            .forEach { file ->
                val trackIds = LinkedHashSet<Long>()
                val resolvedCount = runCatching {
                    forEachResolvedBatch(file) { batch ->
                        val idsByPath = database.findTrackIdsByAbsolutePaths(batch)
                        batch.forEach { path -> idsByPath[path]?.let(trackIds::add) }
                    }
                }.getOrElse {
                    ignored += 1
                    return@forEach
                }
                ignored += (resolvedCount - trackIds.size).coerceAtLeast(0)
                database.upsertImportedPlaylist(
                    sourcePath = file.absolutePath,
                    preferredName = "M3U · ${file.nameWithoutExtension.ifBlank { "Playlist" }}",
                    trackIds = trackIds
                )
                imported += 1
                matched += trackIds.size
            }
        return ImportResult(imported, matched, ignored)
    }

    fun discover(root: File): List<File> {
        if (!root.isDirectory || !root.canRead()) return emptyList()
        val result = ArrayList<File>()
        val stack = java.util.ArrayDeque<File>()
        val visited = HashSet<String>()
        stack.add(root)
        while (stack.isNotEmpty() && result.size < MAX_PLAYLIST_FILES) {
            val directory = stack.removeLast()
            val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: continue
            if (!visited.add(canonical)) continue
            val children = directory.listFiles() ?: continue
            children.forEach { child ->
                if (result.size >= MAX_PLAYLIST_FILES) return@forEach
                when {
                    child.isDirectory && !child.name.startsWith('.') && !child.name.equals("Android", true) -> stack.add(child)
                    child.isFile && child.extension.lowercase() in LibraryScanner.PLAYLIST_EXTENSIONS -> result += child
                }
            }
        }
        return result
    }

    fun exportAll(state: LibraryState, roots: List<StorageRoot>): ExportResult {
        val root = roots.firstOrNull { it.id == "sdcard" && it.directory.canWrite() }
            ?: roots.firstOrNull { it.directory.canWrite() }
            ?: return ExportResult(0, null)
        val directory = File(root.directory, "Y2Player/Playlists")
        if (!directory.exists() && !directory.mkdirs()) return ExportResult(0, null)
        var exported = 0
        state.playlists.forEach { playlist ->
            val tracks = state.playlistTrackIds[playlist.id].orEmpty().mapNotNull(state.byId::get)
            val output = uniqueOutputFile(directory, sanitizeFilename(playlist.name), ".m3u8")
            val temp = File(directory, ".${output.name}.tmp")
            runCatching {
                temp.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.appendLine("#EXTM3U")
                    tracks.forEach { track ->
                        writer.appendLine("#EXTINF:${track.durationMs / 1_000},${track.displayArtist} - ${track.title}")
                        writer.appendLine(exportPath(track, root))
                    }
                }
                replaceWithBackup(temp, output)
                exported += 1
            }.onFailure { temp.delete() }
        }
        return ExportResult(exported, directory)
    }

    private fun forEachResolvedBatch(file: File, consume: (List<String>) -> Unit): Int {
        val charset = PlaylistTextReader.charsetFor(file, forceUtf8 = file.extension.equals("m3u8", true))
        val base = file.parentFile ?: File("/")
        val batch = ArrayList<String>(PATH_LOOKUP_BATCH)
        var resolvedCount = 0

        fun flush() {
            if (batch.isEmpty()) return
            consume(batch.toList())
            batch.clear()
        }

        PlaylistTextReader.forEachLine(file, charset, MAX_PLAYLIST_ENTRIES, MAX_PLAYLIST_LINE_CHARS) { raw ->
            PlaylistPathResolver.resolve(base, raw)?.let { path ->
                batch += path
                resolvedCount += 1
                if (batch.size >= PATH_LOOKUP_BATCH) flush()
            }
        }
        flush()
        return resolvedCount
    }

    private fun exportPath(track: Track, root: StorageRoot): String {
        if (track.volumeId != root.id) return track.absolutePath
        return runCatching { File(track.absolutePath).relativeTo(root.directory).path.replace('\\', '/') }
            .getOrElse { track.absolutePath }
    }

    private fun sanitizeFilename(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim().take(80).ifBlank { "Playlist" }

    private fun uniqueOutputFile(directory: File, base: String, extension: String): File {
        val first = File(directory, base + extension)
        if (!first.exists()) return first
        var suffix = 2
        while (true) {
            val candidate = File(directory, "$base ($suffix)$extension")
            if (!candidate.exists()) return candidate
            suffix += 1
        }
    }

    private fun replaceWithBackup(temp: File, output: File) {
        val backup = File(output.parentFile, ".${output.name}.bak")
        if (backup.exists() && !backup.delete()) error("Cannot clear stale backup ${backup.name}")
        val hadOutput = output.exists()
        if (hadOutput && !output.renameTo(backup)) error("Cannot back up ${output.name}")
        try {
            if (!temp.renameTo(output)) {
                temp.copyTo(output, overwrite = true)
                temp.delete()
            }
            if (backup.exists()) backup.delete()
        } catch (error: Throwable) {
            runCatching { if (output.exists()) output.delete() }
            if (hadOutput) runCatching { backup.renameTo(output) }
            throw error
        }
    }

    companion object {
        private const val PATH_LOOKUP_BATCH = 192
        private const val MAX_PLAYLIST_FILES = 1_000
        private const val MAX_PLAYLIST_ENTRIES = 100_000
        private const val MAX_PLAYLIST_BYTES = 8L * 1024L * 1024L
        private const val MAX_PLAYLIST_LINE_CHARS = 4_096
    }
}
