package com.schulzcode.y2player.playback

import android.media.MediaMetadataRetriever
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test

class LegacyRemoteControlControllerTest {
    @Test
    fun y2AvrcpReceivesTrackArtistUnderArtistAndAlbumArtistKeys() {
        val strings = mutableMapOf<Int, String>()
        val longs = mutableMapOf<Int, Long>()
        val track = Track(
            id = 7,
            volumeId = "sdcard",
            absolutePath = "/music/track.flac",
            relativePath = "track.flac",
            title = "Track title",
            artist = "Track artist",
            album = "Album title",
            albumArtist = "Compilation artist",
            trackNumber = 1,
            discNumber = 1,
            durationMs = 123_456,
            fileSize = 1_024,
            modifiedAt = 1,
        )

        putLegacyRemoteMetadata(
            track = track,
            durationMs = track.durationMs,
            putString = strings::put,
            putLong = longs::put
        )

        assertEquals("Track title", strings[MediaMetadataRetriever.METADATA_KEY_TITLE])
        assertEquals("Track artist", strings[MediaMetadataRetriever.METADATA_KEY_ARTIST])
        assertEquals("Track artist", strings[MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST])
        assertEquals("Album title", strings[MediaMetadataRetriever.METADATA_KEY_ALBUM])
        assertEquals(123_456L, longs[MediaMetadataRetriever.METADATA_KEY_DURATION])
    }

    @Test
    fun cachedArtworkSurvivesSong1Song2Song1Navigation() {
        val song1 = FakeArtwork(trackId = 1)
        val song2 = FakeArtwork(trackId = 2)
        val remote = RecyclingRemote()

        fun publish(source: FakeArtwork) {
            publishDetachedArtwork(source, FakeArtwork::detachedCopy, remote::apply)
        }

        publish(song1)
        publish(song2)
        publish(song1)

        assertFalse(song1.recycled)
        assertFalse(song2.recycled)
        assertEquals(1, remote.current?.trackId)
        assertNotSame(song1, remote.current)
    }

    private class FakeArtwork(val trackId: Int) {
        var recycled = false
        fun detachedCopy(): FakeArtwork = FakeArtwork(trackId)
    }

    private class RecyclingRemote {
        var current: FakeArtwork? = null

        fun apply(artwork: FakeArtwork) {
            current?.recycled = true
            current = artwork
        }
    }
}
