package com.schulzcode.y2player.playback

import com.schulzcode.y2player.core.model.PlaybackStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackWakeLockTest {
    @Test fun activePlaybackAndPreparationKeepTheCpuAwake() {
        assertTrue(PlaybackWakeLockPolicy.shouldHold(PlaybackStatus.PREPARING))
        assertTrue(PlaybackWakeLockPolicy.shouldHold(PlaybackStatus.PLAYING))
    }

    @Test fun inactiveStatesReleaseTheServiceWakeLock() {
        listOf(PlaybackStatus.IDLE, PlaybackStatus.PAUSED, PlaybackStatus.ERROR).forEach { status ->
            assertFalse("$status must not retain the service wake lock", PlaybackWakeLockPolicy.shouldHold(status))
        }
    }
}
