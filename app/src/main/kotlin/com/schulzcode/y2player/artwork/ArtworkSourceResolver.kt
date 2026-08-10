package com.schulzcode.y2player.artwork

import java.io.File
import java.io.FileInputStream
import java.util.LinkedHashMap
import java.util.Locale

internal data class ArtworkSourceKey(
    val trackPath: String,
    val trackModifiedAt: Long,
    val libraryRevision: Long
)

internal data class ArtworkRequestKey(
    val source: ArtworkSourceKey,
    val targetSize: Int
)

internal class ArtworkSourceResolver(
    private val maximumBytes: Int,
    private val readEmbedded: (String, Int) -> ByteArray?,
    private val listFiles: (File) -> Array<File>? = { directory -> directory.listFiles() }
) {
    private val sourceCache = LinkedHashMap<ArtworkSourceKey, Source>(SOURCE_CACHE_ENTRIES, 0.75f, true)

    fun <T> resolve(key: ArtworkSourceKey, targetSize: Int, decode: (ByteArray, Int) -> T?): T? {
        when (val cached = cachedSource(key)) {
            Source.Embedded -> decodeEmbedded(key.trackPath, targetSize, decode)?.let { return it }
            is Source.External -> decodeExternal(cached, targetSize, decode)?.let { return it }
            Source.Missing -> return null
            null -> Unit
        }

        decodeEmbedded(key.trackPath, targetSize, decode)?.let {
            cacheSource(key, Source.Embedded)
            return it
        }

        val external = findExternal(key.trackPath)?.let(::externalSource)
        if (external != null) {
            decodeExternal(external, targetSize, decode)?.let {
                cacheSource(key, external)
                return it
            }
        }
        cacheSource(key, Source.Missing)
        return null
    }

    fun clear() = synchronized(sourceCache) { sourceCache.clear() }

    private fun <T> decodeEmbedded(
        trackPath: String,
        targetSize: Int,
        decode: (ByteArray, Int) -> T?
    ): T? {
        val bytes = runCatching { readEmbedded(trackPath, maximumBytes) }.getOrNull()
            ?.takeIf { it.isNotEmpty() && it.size <= maximumBytes }
            ?: return null
        return runCatching { decode(bytes, targetSize) }.getOrNull()
    }

    private fun <T> decodeExternal(
        source: Source.External,
        targetSize: Int,
        decode: (ByteArray, Int) -> T?
    ): T? {
        val file = File(source.path)
        val unchanged = runCatching {
            file.isFile && file.lastModified() == source.modifiedAt && file.length() == source.length
        }.getOrDefault(false)
        if (!unchanged) return null
        val bytes = readBounded(file) ?: return null
        return runCatching { decode(bytes, targetSize) }.getOrNull()
    }

    private fun findExternal(trackPath: String): File? {
        val directory = File(trackPath).parentFile ?: return null
        val children = runCatching { listFiles(directory) }.getOrNull() ?: return null
        val matches = arrayOfNulls<File>(FILENAMES.size)
        children.forEach { child ->
            if (!runCatching { child.isFile }.getOrDefault(false)) return@forEach
            val index = FILENAMES.indexOf(child.name.lowercase(Locale.US))
            if (index >= 0 && (matches[index] == null || child.name < matches[index]!!.name)) {
                matches[index] = child
            }
        }
        return matches.firstOrNull { it != null }
    }

    private fun externalSource(file: File): Source.External? = runCatching {
        val length = file.length()
        if (length <= 0L || length > maximumBytes.toLong()) null
        else Source.External(file.absolutePath, file.lastModified(), length)
    }.getOrNull()

    private fun readBounded(file: File): ByteArray? {
        val length = runCatching { file.length() }.getOrNull() ?: return null
        if (length <= 0L || length > maximumBytes.toLong() || length > Int.MAX_VALUE.toLong()) return null
        return try {
            FileInputStream(file).use { input ->
                val bytes = ByteArray(length.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count < 0) break
                    offset += count
                }
                if (input.read() >= 0) return null
                if (offset == bytes.size) bytes else bytes.copyOf(offset).takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun cachedSource(key: ArtworkSourceKey): Source? = synchronized(sourceCache) { sourceCache[key] }

    private fun cacheSource(key: ArtworkSourceKey, source: Source) = synchronized(sourceCache) {
        sourceCache[key] = source
        while (sourceCache.size > SOURCE_CACHE_ENTRIES) {
            val eldest = sourceCache.entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
    }

    private sealed class Source {
        object Embedded : Source()
        data class External(val path: String, val modifiedAt: Long, val length: Long) : Source()
        object Missing : Source()
    }

    companion object {
        private const val SOURCE_CACHE_ENTRIES = 32
        private val FILENAMES = listOf(
            "folder.jpg",
            "folder.jpeg",
            "folder.png",
            "cover.jpg",
            "cover.jpeg",
            "cover.png"
        )
    }
}
