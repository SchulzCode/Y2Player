package com.schulzcode.y2player.library

import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal object PlaylistTextReader {
    fun charsetFor(file: File, forceUtf8: Boolean): Charset {
        val prefix = ByteArray(PREFIX_BYTES)
        val count = runCatching { file.inputStream().use { it.read(prefix) } }.getOrDefault(0)
        if (count >= 3 && prefix[0] == 0xef.toByte() && prefix[1] == 0xbb.toByte() && prefix[2] == 0xbf.toByte()) {
            return Charsets.UTF_8
        }
        if (count >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte()) return Charsets.UTF_16LE
        if (count >= 2 && prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte()) return Charsets.UTF_16BE
        if (forceUtf8 || isValidUtf8Prefix(prefix, count)) return Charsets.UTF_8
        return Charset.forName("windows-1252")
    }

    fun forEachLine(
        file: File,
        charset: Charset,
        maxLines: Int,
        maxLineChars: Int,
        consume: (String) -> Unit
    ) {
        file.bufferedReader(charset).use { reader ->
            val buffer = CharArray(CHAR_BUFFER_SIZE)
            val line = StringBuilder(256)
            var lines = 0
            var overflow = false
            var done = false
            while (!done && lines < maxLines) {
                val count = reader.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    if (lines >= maxLines) {
                        done = true
                        break
                    }
                    when (val character = buffer[index]) {
                        '\n' -> {
                            if (!overflow) consume(line.toString())
                            line.setLength(0)
                            overflow = false
                            lines += 1
                        }
                        '\r' -> Unit
                        else -> if (!overflow) {
                            if (line.length < maxLineChars) line.append(character)
                            else {
                                line.setLength(0)
                                overflow = true
                            }
                        }
                    }
                }
            }
            if (!done && lines < maxLines && (line.isNotEmpty() || overflow) && !overflow) consume(line.toString())
        }
    }

    private fun isValidUtf8Prefix(bytes: ByteArray, count: Int): Boolean {
        if (count <= 0) return true
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, 0, count)
        val output = CharBuffer.allocate(count)
        return !decoder.decode(input, output, false).isError
    }

    private const val PREFIX_BYTES = 4 * 1024
    private const val CHAR_BUFFER_SIZE = 4 * 1024
}
