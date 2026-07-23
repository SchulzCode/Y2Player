package com.schulzcode.y2player.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PathIdentityTest {
    @Test fun normalizesPlaylistPathIdentityForFatStorage() {
        assertEquals("/storage/sdcard1/music/song.flac", PathIdentity.key("\\storage\\sdcard1\\Music\\SONG.FLAC/"))
    }
}
