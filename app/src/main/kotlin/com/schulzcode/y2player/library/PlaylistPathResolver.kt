package com.schulzcode.y2player.library

import java.io.File
import java.net.URI

internal object PlaylistPathResolver {
    fun resolve(baseDirectory: File, rawEntry: String): String? {
        val normalized = rawEntry.removePrefix("\uFEFF").trim().replace('\\', '/')
        if (normalized.isEmpty() || normalized.length > MAX_PATH_CHARS || normalized.indexOf('\u0000') >= 0 || normalized.startsWith('#')) return null

        val candidate = when {
            normalized.startsWith("file:", ignoreCase = true) -> fileFromUri(normalized)
            URI_SCHEME.matches(normalized.substringBefore('/')) -> null
            else -> File(normalized).takeIf { it.isAbsolute } ?: File(baseDirectory, normalized)
        } ?: return null
        return runCatching { candidate.canonicalPath }.getOrNull()
    }

    private fun fileFromUri(value: String): File? = runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("file", ignoreCase = true)) return@runCatching null
        File(uri)
    }.getOrNull()

    private val URI_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*:")
    private const val MAX_PATH_CHARS = 4_096
}
