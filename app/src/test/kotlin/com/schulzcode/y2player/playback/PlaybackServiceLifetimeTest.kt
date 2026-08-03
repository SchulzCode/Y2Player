package com.schulzcode.y2player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceLifetimeTest {
    @Test fun pausedLoadedTrackSurvivesUiUnbindForAnInSessionResume() {
        assertFalse(
            shouldStopPlaybackService(
                hasBoundClient = false,
                isActive = false,
                hasPendingFocusResume = false,
                hasResumablePausedTrack = true
            )
        )
    }

    @Test fun completedOrEmptyPlayerStopsAfterUiUnbind() {
        assertTrue(
            shouldStopPlaybackService(
                hasBoundClient = false,
                isActive = false,
                hasPendingFocusResume = false,
                hasResumablePausedTrack = false
            )
        )
    }

    @Test fun activeBoundOrFocusResumablePlayerNeverStops() {
        assertFalse(shouldStopPlaybackService(true, false, false, false))
        assertFalse(shouldStopPlaybackService(false, true, false, false))
        assertFalse(shouldStopPlaybackService(false, false, true, false))
    }
}
