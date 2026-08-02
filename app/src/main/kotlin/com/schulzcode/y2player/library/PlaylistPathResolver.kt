package com.schulzcode.y2player.library

import java.io.File
import java.net.URI
import java.util.Locale

internal class PlaylistPathResolver(baseDirectory: File) {
    private val baseDirectory = runCatching { baseDirectory.canonicalFile }.getOrDefault(baseDirectory)
    private val portableDirectoryMappings = HashMap<String, File>()

    fun resolve(rawEntry: String): String? {
        val normalized = rawEntry.removePrefix("\uFEFF").trim().replace('\\', '/')
        if (normalized.isEmpty() || normalized.length > MAX_PATH_CHARS || normalized.indexOf('\u0000') >= 0 || normalized.startsWith('#')) return null

        val candidate = when {
            normalized.startsWith("file:", ignoreCase = true) -> resolveFileUri(normalized)
            WINDOWS_ABSOLUTE.matches(normalized) -> resolveWindowsPath(normalized)
            URI_SCHEME.matches(normalized.substringBefore('/')) -> null
            else -> {
                val direct = File(normalized)
                if (!normalized.startsWith('/') && !direct.isAbsolute) File(baseDirectory, normalized)
                else existingFile(direct) ?: relocateForeignPath(normalized) ?: direct
            }
        } ?: return null
        return runCatching { candidate.canonicalPath }.getOrNull()
    }

    private fun relocateForeignPath(normalized: String): File? {
        val sourceDirectory = normalized.substringBeforeLast('/', missingDelimiterValue = "")
        val fileName = normalized.substringAfterLast('/')
        if (sourceDirectory.isEmpty() || fileName.isEmpty()) return null

        val windowsStyle = WINDOWS_ABSOLUTE.matches(normalized) || WINDOWS_URI_PATH.matches(normalized)
        val mappingKey = if (windowsStyle) sourceDirectory.lowercase(Locale.US) else sourceDirectory
        portableDirectoryMappings[mappingKey]?.let { mappedDirectory ->
            return existingFile(File(mappedDirectory, fileName))
        }

        val pathWithoutRoot = when {
            WINDOWS_ABSOLUTE.matches(normalized) -> normalized.substring(WINDOWS_PREFIX_CHARS)
            WINDOWS_URI_PATH.matches(normalized) -> normalized.substring(WINDOWS_URI_PREFIX_CHARS)
            else -> normalized
        }
        val components = pathWithoutRoot
            .trimStart('/')
            .split('/')
            .filter { it.isNotEmpty() }
        if (components.isEmpty()) return null

        val anchors = ArrayList<File>(MAX_DEVICE_ANCESTORS)
        var anchor: File? = baseDirectory
        while (anchor != null && anchors.size < MAX_DEVICE_ANCESTORS) {
            anchors += anchor
            anchor = anchor.parentFile
        }

        val longestSuffix = components.size.coerceAtMost(MAX_PORTABLE_SUFFIX_COMPONENTS)
        for (componentCount in longestSuffix downTo 1) {
            val suffix = components.takeLast(componentCount).joinToString(File.separator)
            anchors.forEach { deviceDirectory ->
                val found = existingFile(File(deviceDirectory, suffix)) ?: return@forEach
                found.parentFile?.let { portableDirectoryMappings[mappingKey] = it }
                return found
            }
        }
        return null
    }

    private fun resolveWindowsPath(normalized: String): File {
        val direct = File(normalized)
        return existingFile(direct)
            ?: relocateForeignPath(normalized)
            ?: direct.takeIf { it.isAbsolute }
            ?: File(baseDirectory, normalized)
    }

    private fun resolveFileUri(value: String): File? {
        val direct = fileFromUri(value) ?: return null
        val normalizedPath = direct.path.replace('\\', '/')
        return existingFile(direct) ?: relocateForeignPath(normalizedPath) ?: direct
    }

    private fun existingFile(file: File): File? = runCatching {
        file.canonicalFile.takeIf { it.isFile && it.canRead() }
    }.getOrNull()

    private fun fileFromUri(value: String): File? = runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("file", ignoreCase = true)) return@runCatching null
        File(uri)
    }.getOrNull()

    companion object {
        private val URI_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*:")
        private val WINDOWS_ABSOLUTE = Regex("[A-Za-z]:/.*")
        private val WINDOWS_URI_PATH = Regex("/[A-Za-z]:/.*")
        private const val WINDOWS_PREFIX_CHARS = 2
        private const val WINDOWS_URI_PREFIX_CHARS = 3
        private const val MAX_PORTABLE_SUFFIX_COMPONENTS = 8
        private const val MAX_DEVICE_ANCESTORS = 4
        private const val MAX_PATH_CHARS = 4_096
    }
}
