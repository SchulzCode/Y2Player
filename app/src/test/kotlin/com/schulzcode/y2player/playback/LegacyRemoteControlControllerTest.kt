package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test

class LegacyRemoteControlControllerTest {
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
