package com.schulzcode.y2player.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class ArtworkSourceResolverTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun embeddedArtworkTakesPrecedenceOverExternalArtwork() {
        val folder = temporaryFolder.newFolder("precedence")
        val track = File(folder, "song.flac").apply { writeText("audio") }
        File(folder, "folder.jpg").writeText("external")
        val resolver = resolverWithEmbedded("embedded")

        assertEquals("embedded", resolver.resolve(key(track), 256, ::decodeText))
    }

    @Test fun supportedFilenamesMatchCaseInsensitivelyInDeterministicOrder() {
        val names = listOf("folder.jpg", "folder.jpeg", "folder.png", "cover.jpg", "cover.jpeg", "cover.png")
        names.forEachIndexed { index, name ->
            val folder = temporaryFolder.newFolder("matching-$index")
            val track = File(folder, "song.flac").apply { writeText("audio") }
            File(folder, name.uppercase(Locale.US)).writeText(name)
            assertEquals(name, resolverWithEmbedded(null).resolve(key(track), 256, ::decodeText))
        }

        val orderedFolder = temporaryFolder.newFolder("ordered")
        val orderedTrack = File(orderedFolder, "song.flac").apply { writeText("audio") }
        names.reversed().forEach { name -> File(orderedFolder, name.uppercase(Locale.US)).writeText(name) }
        assertEquals("folder.jpg", resolverWithEmbedded(null).resolve(key(orderedTrack), 256, ::decodeText))
    }

    @Test fun missingAndCorruptExternalArtworkFailSafely() {
        val missingFolder = temporaryFolder.newFolder("missing")
        val missingTrack = File(missingFolder, "song.flac").apply { writeText("audio") }
        assertNull(resolverWithEmbedded(null).resolve(key(missingTrack), 256, ::decodeImage))

        val corruptFolder = temporaryFolder.newFolder("corrupt")
        val corruptTrack = File(corruptFolder, "song.flac").apply { writeText("audio") }
        File(corruptFolder, "cover.png").writeText("not an image")
        assertNull(resolverWithEmbedded(null).resolve(key(corruptTrack), 256, ::decodeImage))
    }

    @Test fun sourceAndBitmapCacheKeysInvalidateOnlyForRelevantChanges() {
        val folder = temporaryFolder.newFolder("cache")
        val track = File(folder, "song.flac").apply { writeText("audio") }
        val external = File(folder, "folder.jpg").apply { writeText("external") }
        var directoryReads = 0
        val resolver = ArtworkSourceResolver(
            maximumBytes = 1024,
            readEmbedded = { _, _ -> null },
            listFiles = { directory -> directoryReads += 1; directory.listFiles() }
        )
        val original = key(track, modifiedAt = 10, libraryRevision = 20)

        assertEquals("external", resolver.resolve(original, 256, ::decodeText))
        assertEquals("external", resolver.resolve(original, 512, ::decodeText))
        assertEquals(1, directoryReads)

        external.writeText("external changed")
        assertEquals("external changed", resolver.resolve(original, 768, ::decodeText))
        assertEquals(2, directoryReads)

        val timestampChanged = original.copy(trackModifiedAt = 11)
        val libraryChanged = original.copy(libraryRevision = 21)
        assertEquals("external changed", resolver.resolve(timestampChanged, 256, ::decodeText))
        assertEquals("external changed", resolver.resolve(libraryChanged, 256, ::decodeText))
        assertEquals(4, directoryReads)

        val bitmapKey = ArtworkRequestKey(original, 256)
        assertEquals(bitmapKey, ArtworkRequestKey(original, 256))
        assertNotEquals(bitmapKey, ArtworkRequestKey(timestampChanged, 256))
        assertNotEquals(bitmapKey, ArtworkRequestKey(libraryChanged, 256))
        assertNotEquals(bitmapKey, ArtworkRequestKey(original, 512))
    }

    private fun resolverWithEmbedded(value: String?): ArtworkSourceResolver = ArtworkSourceResolver(
        maximumBytes = 1024,
        readEmbedded = { _, _ -> value?.toByteArray() }
    )

    private fun key(track: File, modifiedAt: Long = 1, libraryRevision: Long = 1) = ArtworkSourceKey(
        trackPath = track.absolutePath,
        trackModifiedAt = modifiedAt,
        libraryRevision = libraryRevision
    )

    private fun decodeText(bytes: ByteArray, targetSize: Int): String {
        check(targetSize > 0)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun decodeImage(bytes: ByteArray, targetSize: Int): String? {
        check(targetSize > 0)
        return bytes.toString(Charsets.UTF_8).takeIf { it.startsWith("valid image") }
    }
}
