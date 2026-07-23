package com.schulzcode.y2player.diagnostics

/**
 * Minimal NDJSON serialization for [EventLog].
 *
 * Hand-rolled rather than pulling in a JSON library: the schema is closed, the
 * device is a 2014 dual-core with 1 GB of RAM, and adding a dependency for
 * twelve fields would be the wrong trade. Everything here is pure and runs on
 * the writer thread only, never on an input, playback or draw path.
 */
object EventJson {

    /** Values longer than this are truncated — logs must never carry payloads. */
    const val MAX_VALUE_CHARS = 200

    /**
     * Escapes a string for JSON and clamps its length.
     *
     * Control characters are escaped rather than dropped so a corrupt tag can
     * never produce a malformed line and break a whole log file for a parser.
     */
    fun escape(value: String, builder: StringBuilder) {
        val text = if (value.length > MAX_VALUE_CHARS) value.take(MAX_VALUE_CHARS) + "…" else value
        builder.append('"')
        for (element in text) {
            when (element) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                else ->
                    if (element < ' ') builder.append(String.format("\\u%04x", element.code))
                    else builder.append(element)
            }
        }
        builder.append('"')
    }

    /** Appends a JSON value for the supported primitive types. */
    fun appendValue(value: Any?, builder: StringBuilder) {
        when (value) {
            null -> builder.append("null")
            is Boolean -> builder.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> builder.append(value.toString())
            is Float, is Double -> {
                val number = (value as Number).toDouble()
                // JSON has no NaN/Infinity; emit null rather than an invalid line.
                if (number.isNaN() || number.isInfinite()) builder.append("null")
                else builder.append(value.toString())
            }
            is Enum<*> -> escape(value.name, builder)
            else -> escape(value.toString(), builder)
        }
    }

    /**
     * Removes identifying and volatile parts of a filesystem path.
     *
     * Logs go to a removable card and may be shared for support, so absolute
     * paths are reduced to volume + filename. A stable track id carries the same
     * diagnostic value without the user's directory structure.
     */
    fun sanitizePath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val normalized = path.replace('\\', '/').trimEnd('/')
        val name = normalized.substringAfterLast('/', normalized)
        val volume = when {
            normalized.startsWith("/storage/sdcard1") -> "sdcard1"
            normalized.startsWith("/storage/sdcard0") -> "sdcard0"
            normalized.startsWith("/mnt/") -> "mnt"
            normalized.startsWith("/data") -> "data"
            else -> "other"
        }
        return "$volume:$name"
    }
}
