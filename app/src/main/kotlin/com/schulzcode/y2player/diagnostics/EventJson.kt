package com.schulzcode.y2player.diagnostics

object EventJson {
    const val MAX_VALUE_CHARS = 200

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

    fun appendValue(value: Any?, builder: StringBuilder) {
        when (value) {
            null -> builder.append("null")
            is Boolean -> builder.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> builder.append(value.toString())
            is Float, is Double -> {
                val number = (value as Number).toDouble()
                if (number.isNaN() || number.isInfinite()) builder.append("null")
                else builder.append(value.toString())
            }
            is Enum<*> -> escape(value.name, builder)
            else -> escape(value.toString(), builder)
        }
    }

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
