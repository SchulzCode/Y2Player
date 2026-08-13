package com.schulzcode.y2player.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

data class BackupDocument(
    val appVersion: String,
    val createdAtUtcMs: Long,
    val settings: Map<String, String>,
    val userData: PortableUserData,
    val listeningHistory: List<String>
)

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BackupFormat {
    const val FORMAT_ID = "com.schulzcode.y2player.user-backup"
    const val SCHEMA_VERSION = 1
    const val FILE_NAME = "Y2Player-backup-v1.y2backup"
    const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

    fun encode(document: BackupDocument): ByteArray = encode(document, emptyMap())

    internal fun encode(document: BackupDocument, extraSections: Map<String, ByteArray>): ByteArray {
        validateDocument(document)
        val sections = linkedMapOf(
            "settings" to (1 to section { writeStringMap(this, document.settings) }),
            "user-data" to (2 to section { writeUserData(this, document.userData) }),
            "listening-history" to (1 to section { writeStringList(this, document.listeningHistory) })
        )
        extraSections.forEach { (name, bytes) ->
            require(name.matches(Regex("[a-z0-9-]{1,64}"))) { "Invalid section name" }
            require(bytes.size <= MAX_SECTION_BYTES) { "Section is too large" }
            sections[name] = 1 to bytes
        }
        val payload = section {
            writeInt(sections.size)
            sections.forEach { (name, value) ->
                val (version, bytes) = value
                writeString(this, name)
                writeInt(version)
                writeInt(bytes.size)
                write(bytes)
            }
        }
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
        return section {
            write(MAGIC)
            writeString(this, FORMAT_ID)
            writeInt(SCHEMA_VERSION)
            writeString(this, document.appVersion)
            writeLong(document.createdAtUtcMs)
            writeInt(payload.size)
            write(payload)
            writeInt(checksum.size)
            write(checksum)
        }.also { require(it.size <= MAX_BACKUP_BYTES) { "Backup is too large" } }
    }

    fun decode(bytes: ByteArray): BackupDocument {
        if (bytes.size > MAX_BACKUP_BYTES) throw BackupFormatException("Backup is too large")
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw BackupFormatException("Not a Y2Player backup")
            if (readString(input, 128) != FORMAT_ID) throw BackupFormatException("Unsupported backup format")
            val schema = input.readInt()
            if (schema != SCHEMA_VERSION) throw BackupFormatException("Unsupported backup version $schema")
            val appVersion = readString(input, 128)
            val createdAt = input.readLong()
            if (createdAt < 0) throw BackupFormatException("Invalid creation timestamp")
            val payloadLength = checkedLength(input.readInt(), MAX_BACKUP_BYTES, "payload")
            val payload = ByteArray(payloadLength)
            input.readFully(payload)
            val checksumLength = checkedLength(input.readInt(), 64, "checksum")
            if (checksumLength != SHA256_BYTES) throw BackupFormatException("Unsupported checksum")
            val expected = ByteArray(checksumLength)
            input.readFully(expected)
            if (input.available() != 0) throw BackupFormatException("Unexpected trailing backup data")
            val actual = MessageDigest.getInstance("SHA-256").digest(payload)
            if (!MessageDigest.isEqual(expected, actual)) throw BackupFormatException("Backup checksum does not match")

            var settings: Map<String, String>? = null
            var userData: PortableUserData? = null
            var history: List<String>? = null
            val payloadInput = DataInputStream(ByteArrayInputStream(payload))
            val sectionCount = checkedCount(payloadInput.readInt(), MAX_SECTIONS, "sections")
            repeat(sectionCount) {
                val name = readString(payloadInput, 64)
                val version = payloadInput.readInt()
                val length = checkedLength(payloadInput.readInt(), MAX_SECTION_BYTES, "section")
                val sectionBytes = ByteArray(length)
                payloadInput.readFully(sectionBytes)
                val sectionInput = DataInputStream(ByteArrayInputStream(sectionBytes))
                val handled = when {
                    name == "settings" && version == 1 -> {
                        if (settings != null) throw BackupFormatException("Duplicate settings section")
                        settings = readStringMap(sectionInput)
                        true
                    }
                    name == "user-data" && version in 1..2 -> {
                        if (userData != null) throw BackupFormatException("Duplicate user-data section")
                        userData = readUserData(sectionInput, version)
                        true
                    }
                    name == "listening-history" && version == 1 -> {
                        if (history != null) throw BackupFormatException("Duplicate listening-history section")
                        history = readStringList(sectionInput, MAX_HISTORY_RECORDS, MAX_HISTORY_LINE_BYTES)
                        true
                    }
                    else -> false
                }
                if (handled && sectionInput.available() != 0) {
                    throw BackupFormatException("Invalid $name section")
                }
            }
            if (payloadInput.available() != 0) throw BackupFormatException("Invalid backup payload")
            return BackupDocument(
                appVersion = appVersion,
                createdAtUtcMs = createdAt,
                settings = settings ?: throw BackupFormatException("Settings are missing"),
                userData = userData ?: throw BackupFormatException("User data is missing"),
                listeningHistory = history ?: throw BackupFormatException("Listening history is missing")
            ).also(::validateDocument)
        } catch (error: BackupFormatException) {
            throw error
        } catch (error: EOFException) {
            throw BackupFormatException("Backup is truncated", error)
        } catch (error: IllegalArgumentException) {
            throw BackupFormatException(error.message ?: "Backup contains invalid data", error)
        } catch (error: Throwable) {
            throw BackupFormatException("Backup could not be read", error)
        }
    }

    fun writeAtomic(destination: File, document: BackupDocument): File {
        val bytes = encode(document)
        val directory = destination.parentFile ?: throw BackupFormatException("Backup location is invalid")
        if (!directory.isDirectory && !directory.mkdirs()) throw BackupFormatException("Backup folder could not be created")
        val temp = File(directory, ".${destination.name}.tmp")
        val previous = File(directory, ".${destination.name}.previous")
        if (temp.exists() && !temp.delete()) throw BackupFormatException("Stale backup temporary file could not be removed")
        if (previous.exists() && !previous.delete()) throw BackupFormatException("Stale backup rollback file could not be removed")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            val hadDestination = destination.exists()
            if (hadDestination && !destination.renameTo(previous)) throw BackupFormatException("Existing backup could not be staged")
            if (!temp.renameTo(destination)) {
                if (hadDestination) previous.renameTo(destination)
                throw BackupFormatException("Backup could not be installed atomically")
            }
            if (previous.exists() && !previous.delete()) previous.deleteOnExit()
            return destination
        } catch (error: Throwable) {
            runCatching { temp.delete() }
            if (!destination.exists() && previous.exists()) runCatching { previous.renameTo(destination) }
            if (error is BackupFormatException) throw error
            throw BackupFormatException("Backup could not be written", error)
        }
    }

    fun read(file: File): BackupDocument {
        if (!file.isFile || !file.canRead()) throw BackupFormatException("No backup was found")
        if (file.length() !in 1..MAX_BACKUP_BYTES.toLong()) throw BackupFormatException("Backup size is invalid")
        return decode(file.readBytes())
    }

    private fun validateDocument(document: BackupDocument) {
        require(document.appVersion.isNotBlank() && document.appVersion.length <= 128) { "Invalid Y2Player version" }
        require(document.createdAtUtcMs >= 0) { "Invalid creation timestamp" }
        require(document.settings.size <= MAX_SETTINGS) { "Too many settings" }
        document.settings.forEach { (key, value) ->
            require(key.matches(Regex("[a-z0-9_.-]{1,64}")) && value.length <= MAX_SETTING_VALUE_CHARS) {
                "Invalid setting"
            }
        }
        PortableUserDataResolver.validate(document.userData)
        require(document.listeningHistory.size <= MAX_HISTORY_RECORDS) { "Listening history is too large" }
        var historyBytes = 0L
        document.listeningHistory.forEach { line ->
            val bytes = line.toByteArray(Charsets.UTF_8).size
            historyBytes += bytes + 1L
            require(bytes <= MAX_HISTORY_LINE_BYTES && line.startsWith('{') && line.endsWith('}')) {
                "Invalid listening-history record"
            }
        }
        require(historyBytes <= com.schulzcode.y2player.diagnostics.PlaybackHistory.MAX_ARCHIVE_BYTES) {
            "Listening history is too large"
        }
    }

    private fun writeUserData(out: DataOutputStream, data: PortableUserData) {
        writeIdentityList(out, data.favorites)
        out.writeInt(data.playlists.size)
        data.playlists.forEach { playlist ->
            writeString(out, playlist.name)
            writeIdentityList(out, playlist.tracks)
        }
        out.writeInt(data.audiobookProgress.size)
        data.audiobookProgress.forEach {
            writeIdentity(out, it.track)
            out.writeLong(it.positionMs)
            out.writeLong(it.updatedAtUtcMs)
        }
        out.writeInt(data.recentlyPlayed.size)
        data.recentlyPlayed.forEach {
            writeIdentity(out, it.track)
            out.writeLong(it.lastPlayedUtcMs)
            out.writeInt(it.playCount)
        }
        out.writeBoolean(data.queue != null)
        data.queue?.let { queue ->
            writeIdentityList(out, queue.tracks)
            out.writeInt(queue.currentIndex ?: -1)
            out.writeLong(queue.positionMs)
            writeString(out, queue.repeatMode)
            out.writeBoolean(queue.shuffleEnabled)
            out.writeLong(queue.shuffleSeed)
            queue.tracks.indices.forEach { index ->
                out.writeByte(when (queue.origins?.getOrNull(index) ?: com.schulzcode.y2player.core.model.QueueOrigin.CONTINUATION) {
                    com.schulzcode.y2player.core.model.QueueOrigin.CONTINUATION -> 0
                    com.schulzcode.y2player.core.model.QueueOrigin.UP_NEXT -> 1
                })
                out.writeInt(queue.sourceOrders?.getOrNull(index) ?: -1)
            }
        }
    }

    private fun readUserData(input: DataInputStream, version: Int): PortableUserData {
        val favorites = readIdentityList(input, PortableUserDataResolver.MAX_FAVORITES)
        val playlists = buildList {
            repeat(checkedCount(input.readInt(), PortableUserDataResolver.MAX_PLAYLISTS, "playlists")) {
                add(PortablePlaylist(
                    readString(input, 256),
                    readIdentityList(input, PortableUserDataResolver.MAX_PLAYLIST_TRACKS)
                ))
            }
        }
        val progress = buildList {
            repeat(checkedCount(input.readInt(), PortableUserDataResolver.MAX_PROGRESS, "audiobook positions")) {
                add(PortableAudiobookProgress(readIdentity(input), input.readLong(), input.readLong()))
            }
        }
        val recent = buildList {
            repeat(checkedCount(input.readInt(), PortableUserDataResolver.MAX_RECENT, "recent history")) {
                add(PortableRecentTrack(readIdentity(input), input.readLong(), input.readInt()))
            }
        }
        val queue = if (!input.readBoolean()) null else {
            val tracks = readIdentityList(input, PortableUserDataResolver.MAX_QUEUE_TRACKS)
            val current = input.readInt().takeIf { it >= 0 }
            val position = input.readLong()
            val repeat = readString(input, 32)
            val shuffle = input.readBoolean()
            val seed = input.readLong()
            if (version == 1) {
                val order = if (!input.readBoolean()) null else buildList<Int> {
                    repeat(checkedCount(input.readInt(), PortableUserDataResolver.MAX_QUEUE_TRACKS, "shuffle order")) {
                        add(input.readInt())
                    }
                }
                PortableQueue(tracks, current, position, repeat, shuffle, seed, legacyPlayOrder = order)
            } else {
                val origins = ArrayList<com.schulzcode.y2player.core.model.QueueOrigin>(tracks.size)
                val sourceOrders = ArrayList<Int?>(tracks.size)
                repeat(tracks.size) {
                    origins += when (input.readUnsignedByte()) {
                        0 -> com.schulzcode.y2player.core.model.QueueOrigin.CONTINUATION
                        1 -> com.schulzcode.y2player.core.model.QueueOrigin.UP_NEXT
                        else -> throw BackupFormatException("Invalid queue origin")
                    }
                    sourceOrders += input.readInt().takeIf { it >= 0 }
                }
                PortableQueue(tracks, current, position, repeat, shuffle, seed, origins, sourceOrders)
            }
        }
        return PortableUserData(favorites, playlists, progress, recent, queue)
    }

    private fun writeIdentityList(out: DataOutputStream, values: List<PortableMediaIdentity>) {
        out.writeInt(values.size)
        values.forEach { writeIdentity(out, it) }
    }

    private fun readIdentityList(input: DataInputStream, maximum: Int): List<PortableMediaIdentity> = buildList {
        repeat(checkedCount(input.readInt(), maximum, "media references")) { add(readIdentity(input)) }
    }

    private fun writeIdentity(out: DataOutputStream, identity: PortableMediaIdentity) {
        writeString(out, PortableMediaIdentity.normalizeVolumeId(identity.volumeId))
        writeString(out, PortableMediaIdentity.normalizeRelativePath(identity.relativePath))
    }

    private fun readIdentity(input: DataInputStream) = PortableMediaIdentity(
        readString(input, 64),
        readString(input, 4_096)
    )

    private fun writeStringMap(out: DataOutputStream, values: Map<String, String>) {
        out.writeInt(values.size)
        values.toSortedMap().forEach { (key, value) ->
            writeString(out, key)
            writeString(out, value)
        }
    }

    private fun readStringMap(input: DataInputStream): Map<String, String> = buildMap {
        repeat(checkedCount(input.readInt(), MAX_SETTINGS, "settings")) {
            val key = readString(input, 64)
            if (containsKey(key)) throw BackupFormatException("Duplicate setting $key")
            put(key, readString(input, MAX_SETTING_VALUE_CHARS))
        }
    }

    private fun writeStringList(out: DataOutputStream, values: List<String>) {
        out.writeInt(values.size)
        values.forEach { writeString(out, it) }
    }

    private fun readStringList(input: DataInputStream, maximum: Int, maxChars: Int): List<String> = buildList {
        repeat(checkedCount(input.readInt(), maximum, "records")) { add(readString(input, maxChars)) }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream, maximumBytes: Int): String {
        val length = checkedLength(input.readInt(), maximumBytes, "text")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val text = String(bytes, Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) throw BackupFormatException("Invalid UTF-8 text")
        return text
    }

    private fun checkedLength(value: Int, maximum: Int, label: String): Int {
        if (value !in 0..maximum) throw BackupFormatException("Invalid $label length")
        return value
    }

    private fun checkedCount(value: Int, maximum: Int, label: String): Int {
        if (value !in 0..maximum) throw BackupFormatException("Invalid $label count")
        return value
    }

    private inline fun section(block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(block)
        return bytes.toByteArray()
    }

    private val MAGIC = "Y2PLAYER-BACKUP\u0000".toByteArray(Charsets.US_ASCII)
    private const val SHA256_BYTES = 32
    private const val MAX_SECTIONS = 64
    private const val MAX_SECTION_BYTES = 24 * 1024 * 1024
    private const val MAX_SETTINGS = 256
    private const val MAX_SETTING_VALUE_CHARS = 16_384
    private const val MAX_HISTORY_RECORDS = 100_000
    private const val MAX_HISTORY_LINE_BYTES = 128 * 1024
}
