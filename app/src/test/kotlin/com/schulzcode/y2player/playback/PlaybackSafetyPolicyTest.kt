package com.schulzcode.y2player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSafetyPolicyTest {
    @Test fun losingWiredRoutePausesByDefault() {
        val policy = PlaybackSafetyPolicy()
        policy.onExplicitPlaybackRequest(PrivateRouteSnapshot(wired = true))

        assertTrue(policy.onRoutesChanged(PrivateRouteSnapshot(), becomingNoisy = false, speakerFallbackAllowed = false))
        assertTrue(policy.isRouteLossLatched())
        assertFalse(policy.canAutomaticallyStart())
    }

    @Test fun explicitSpeakerFallbackOptionAllowsWiredRouteChange() {
        val policy = PlaybackSafetyPolicy()
        policy.onExplicitPlaybackRequest(PrivateRouteSnapshot(wired = true))

        assertFalse(policy.onRoutesChanged(PrivateRouteSnapshot(), becomingNoisy = false, speakerFallbackAllowed = true))
    }

    @Test fun bluetoothLossCannotOptIntoSpeakerFallback() {
        val policy = PlaybackSafetyPolicy()
        policy.onExplicitPlaybackRequest(PrivateRouteSnapshot(bluetooth = true))

        assertTrue(policy.onRoutesChanged(PrivateRouteSnapshot(), becomingNoisy = false, speakerFallbackAllowed = true))
        assertTrue(policy.isRouteLossLatched())
    }

    @Test fun duplicateBluetoothLossBroadcastIsConsumedOnce() {
        val policy = PlaybackSafetyPolicy()
        policy.onExplicitPlaybackRequest(PrivateRouteSnapshot(bluetooth = true))

        assertTrue(policy.onRoutesChanged(PrivateRouteSnapshot(bluetooth = true), becomingNoisy = true, speakerFallbackAllowed = false))
        assertFalse(policy.onRoutesChanged(PrivateRouteSnapshot(), becomingNoisy = false, speakerFallbackAllowed = false))
    }

    @Test fun bluetoothReconnectNeverResumesAfterSafetyPause() {
        val policy = PlaybackSafetyPolicy()
        policy.onExplicitPlaybackRequest(PrivateRouteSnapshot(bluetooth = true))
        policy.onTransientFocusLoss(wasPlaying = true)
        policy.onRoutesChanged(PrivateRouteSnapshot(), becomingNoisy = true, speakerFallbackAllowed = false)
        policy.onRoutesChanged(PrivateRouteSnapshot(bluetooth = true), becomingNoisy = false, speakerFallbackAllowed = false)

        assertFalse(policy.consumeFocusResume())
        assertFalse(policy.canAutomaticallyStart())
    }

    @Test fun transientFocusLossResumesOnlyWhenPreviouslyPlaying() {
        val active = PlaybackSafetyPolicy().apply { onExplicitPlaybackRequest(PrivateRouteSnapshot()) }
        assertTrue(active.onTransientFocusLoss(wasPlaying = true))
        assertTrue(active.consumeFocusResume())

        val paused = PlaybackSafetyPolicy().apply { onExplicitPlaybackRequest(PrivateRouteSnapshot()) }
        assertFalse(paused.onTransientFocusLoss(wasPlaying = false))
        assertFalse(paused.consumeFocusResume())
    }

    @Test fun manualPauseCancelsFocusResume() {
        val policy = PlaybackSafetyPolicy()
        policy.onExplicitPlaybackRequest(PrivateRouteSnapshot())
        policy.onTransientFocusLoss(wasPlaying = true)
        policy.onManualPause()

        assertFalse(policy.consumeFocusResume())
    }

    @Test fun restoredPausedPrivateRouteIsStillProtectedWithoutAutoplay() {
        val policy = PlaybackSafetyPolicy()
        policy.onRestoredPausedSession(PrivateRouteSnapshot(wired = true))

        assertTrue(policy.onRoutesChanged(PrivateRouteSnapshot(), becomingNoisy = false, speakerFallbackAllowed = false))
        assertFalse(policy.canAutomaticallyStart())
    }
}
