package com.schulzcode.y2player.backup

import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.core.model.QueueOrigin
import com.schulzcode.y2player.core.state.PlayerPreferencesState
import com.schulzcode.y2player.playback.VolumeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupFormatTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val internal = PortableMediaIdentity("internal", "Music/Artist/One Song.flac")
    private val card = PortableMediaIdentity("sdcard", "AUDIOBOOKS/Ångström Book/01 – Start.mp3")

    private fun document() = BackupDocument(
        appVersion = "2.2.1-test",
        createdAtUtcMs = 1_777_777_777_777,
        settings = PreferenceBackup.encode(PlayerPreferencesState(
            lightTheme = true,
            wrapLists = false,
            volumeMode = VolumeMode.PERCEPTUAL,
            volumeLevel = 7,
            equalizerBandLevelsMb = listOf(-300, 0, 600)
        )),
        userData = PortableUserData(
            favorites = listOf(internal),
            playlists = listOf(PortablePlaylist("Road trip 日本語", listOf(card, internal))),
            audiobookProgress = listOf(PortableAudiobookProgress(card, 42_000, 1_700_000_000_000)),
            recentlyPlayed = listOf(PortableRecentTrack(internal, 1_700_000_100_000, 3)),
            queue = PortableQueue(
                listOf(card, internal), 1, 12_345, "all", true, 99,
                origins = listOf(QueueOrigin.UP_NEXT, QueueOrigin.CONTINUATION),
                sourceOrders = listOf(null, 0)
            )
        ),
        listeningHistory = listOf("{\"event\":\"listen\",\"path\":\"One Song.flac\"}")
    )

    @Test fun completeBackupRoundTripPreservesEverySection() {
        val source = document()
        val decoded = BackupFormat.decode(BackupFormat.encode(source))
        assertEquals(source, decoded)
        assertEquals(
            PlayerPreferencesState(
                lightTheme = true,
                wrapLists = false,
                volumeMode = VolumeMode.PERCEPTUAL,
                volumeLevel = 7,
                equalizerBandLevelsMb = listOf(-300, 0, 600)
            ),
            PreferenceBackup.decode(decoded.settings)
        )
    }

    @Test fun atomicFileRoundTripUsesTheVersionedName() {
        val directory = temporaryFolder.newFolder("card", "Y2Player", "Backups")
        val destination = java.io.File(directory, BackupFormat.FILE_NAME)
        BackupFormat.writeAtomic(destination, document())
        assertEquals(document(), BackupFormat.read(destination))
        assertFalse(java.io.File(directory, ".${BackupFormat.FILE_NAME}.tmp").exists())
        assertFalse(java.io.File(directory, ".${BackupFormat.FILE_NAME}.previous").exists())
    }

    @Test fun corruptChecksumIsRejectedBeforePayloadUse() {
        val bytes = BackupFormat.encode(document())
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x55).toByte()
        val error = assertThrows(BackupFormatException::class.java) { BackupFormat.decode(bytes) }
        assertTrue(error.message.orEmpty().contains("checksum"))
    }

    @Test fun truncatedBackupIsRejectedClearly() {
        val bytes = BackupFormat.encode(document())
        val error = assertThrows(BackupFormatException::class.java) {
            BackupFormat.decode(bytes.copyOf(bytes.size / 2))
        }
        assertTrue(error.message.orEmpty().contains("truncated"))
    }

    @Test fun unsupportedSchemaIsRejected() {
        val bytes = BackupFormat.encode(document())
        val formatBytes = BackupFormat.FORMAT_ID.toByteArray(Charsets.UTF_8)
        val versionOffset = "Y2PLAYER-BACKUP\u0000".toByteArray(Charsets.US_ASCII).size + 4 + formatBytes.size
        bytes[versionOffset + 3] = 2
        val error = assertThrows(BackupFormatException::class.java) { BackupFormat.decode(bytes) }
        assertTrue(error.message.orEmpty().contains("Unsupported backup version"))
    }

    @Test fun unknownFutureSectionsAndSettingsAreIgnoredSafely() {
        val source = document().copy(settings = document().settings + ("future_safe_field" to "value"))
        val bytes = BackupFormat.encode(source, mapOf("future-section" to byteArrayOf(1, 2, 3)))
        val decoded = BackupFormat.decode(bytes)
        assertEquals("value", decoded.settings["future_safe_field"])
        assertEquals(PlayerPreferencesState(
            lightTheme = true,
            wrapLists = false,
            volumeMode = VolumeMode.PERCEPTUAL,
            volumeLevel = 7,
            equalizerBandLevelsMb = listOf(-300, 0, 600)
        ), PreferenceBackup.decode(decoded.settings))
    }

    @Test fun emptyAndLargeCollectionsRoundTripWithinBounds() {
        val identities = (0 until 8_000).map { PortableMediaIdentity("sdcard", "Music/Large List/Track $it.mp3") }
        val history = (0 until 400).map { index -> "{\"i\":$index,\"padding\":\"${"x".repeat(900)}\"}" }
        val source = document().copy(
            userData = PortableUserData(playlists = listOf(
                PortablePlaylist("Empty", emptyList()),
                PortablePlaylist("Large", identities)
            )),
            listeningHistory = history
        )
        assertEquals(source, BackupFormat.decode(BackupFormat.encode(source)))
    }

    @Test fun traversalPathsAreRejectedAndSpacesUnicodeSurvive() {
        assertEquals("AUDIOBOOKS/Ångström Book/01 – Start.mp3", card.relativePath)
        val unsafe = document().copy(userData = PortableUserData(
            favorites = listOf(PortableMediaIdentity("sdcard", "Music/../secret.mp3"))
        ))
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.encode(unsafe) }
    }

    @Test fun changedDatabaseIdsMountAliasesAndMissingTracksResolvePortably() {
        val source = PortableUserData(
            favorites = listOf(PortableMediaIdentity("sdcard2", "Music/A Song.mp3")),
            playlists = listOf(PortablePlaylist("Mixed", listOf(
                PortableMediaIdentity("internal", "Music/Missing.mp3"),
                PortableMediaIdentity("sdcard", "Music/A Song.mp3")
            ))),
            queue = PortableQueue(
                tracks = listOf(
                    PortableMediaIdentity("internal", "Music/Missing.mp3"),
                    PortableMediaIdentity("extSdCard", "Music/A Song.mp3")
                ),
                currentIndex = 1,
                positionMs = 555,
                repeatMode = "off",
                shuffleEnabled = false,
                shuffleSeed = 1,
                legacyPlayOrder = listOf(0, 1)
            )
        )
        val resolved = PortableUserDataResolver.resolve(source, listOf(track(9_999, "sdcard", "Music/A Song.mp3")))
        assertEquals(listOf(9_999L), resolved.favoriteTrackIds)
        assertEquals(listOf(9_999L), resolved.playlists.single().trackIds)
        assertEquals(listOf(9_999L), resolved.queueEntries.map { it.trackId })
        assertEquals(resolved.queueEntries.single().id, resolved.playbackSession?.currentEntryId)
        assertEquals(555L, resolved.playbackSession?.positionMs)
        assertTrue(resolved.unresolvedReferences > 0)
    }

    private fun track(id: Long, volume: String, path: String) = Track(
        id = id,
        volumeId = volume,
        absolutePath = "/storage/$volume/$path",
        relativePath = path,
        title = path.substringAfterLast('/'),
        artist = null,
        album = null,
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        durationMs = 60_000,
        fileSize = 1_000,
        modifiedAt = 1
    )
}
