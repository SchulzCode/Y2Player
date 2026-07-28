package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGainTest {
    private val tagged = ReplayGainMetadata(
        trackGainDb = -6f,
        trackPeak = 0.9f,
        albumGainDb = -3f,
        albumPeak = 0.8f
    )

    @Test fun disabledIsExactlyUnityEvenWhenMetadataExists() {
        assertEquals(ReplayGainAdjustment(), ReplayGain.resolve(ReplayGainMode.OFF, true, tagged))
    }

    @Test fun albumAndTrackModesSelectTheirNamedMetadata() {
        val album = ReplayGain.resolve(ReplayGainMode.ALBUM, false, tagged)
        val track = ReplayGain.resolve(ReplayGainMode.TRACK, false, tagged)

        assertEquals(ReplayGainSource.ALBUM, album.source)
        assertEquals(-3f, album.gainDb)
        assertEquals(ReplayGainSource.TRACK, track.source)
        assertEquals(-6f, track.gainDb)
    }

    @Test fun hybridUsesAlbumNormallyAndTrackWhileShuffling() {
        assertEquals(
            ReplayGainSource.ALBUM,
            ReplayGain.resolve(ReplayGainMode.TRACK_WHEN_SHUFFLING, false, tagged).source
        )
        assertEquals(
            ReplayGainSource.TRACK,
            ReplayGain.resolve(ReplayGainMode.TRACK_WHEN_SHUFFLING, true, tagged).source
        )
    }

    @Test fun missingPreferredValueFallsBackAndMissingAllLeavesUnity() {
        val trackOnly = ReplayGainMetadata(trackGainDb = -4f, trackPeak = 0.75f)
        assertEquals(
            ReplayGainSource.TRACK,
            ReplayGain.resolve(ReplayGainMode.ALBUM, false, trackOnly).source
        )
        assertEquals(
            ReplayGainAdjustment(),
            ReplayGain.resolve(ReplayGainMode.TRACK, false, ReplayGainMetadata())
        )
    }

    @Test fun peakCapsPositiveGainBeforeItCanClip() {
        val adjustment = ReplayGain.resolve(
            ReplayGainMode.TRACK,
            false,
            ReplayGainMetadata(trackGainDb = 6f, trackPeak = 0.8f)
        )

        assertEquals(1.25f, adjustment.linearGain, 0.0001f)
        assertTrue(adjustment.clippingPrevented)
    }

    @Test fun positiveGainWithoutPeakIsAppliedButPcmStillSaturatesSafely() {
        val adjustment = ReplayGain.resolve(
            ReplayGainMode.TRACK,
            false,
            ReplayGainMetadata(trackGainDb = 6f)
        )
        assertTrue(adjustment.linearGain > 1f)
        assertFalse(adjustment.clippingPrevented)

        val pcm = shortArrayOf(20_000, -20_000)
        PcmGain.apply(pcm, 0, pcm.size, adjustment.linearGain, AudioBalance.CENTRE)
        assertEquals(Short.MAX_VALUE, pcm[0])
        assertEquals(Short.MIN_VALUE, pcm[1])
    }

    @Test fun storageValuesRoundTripAndUnknownValuesDisableReplayGain() {
        ReplayGainMode.entries.forEach { mode ->
            assertEquals(mode, ReplayGainMode.fromStorage(mode.storageId))
        }
        assertEquals(ReplayGainMode.OFF, ReplayGainMode.fromStorage("future-mode"))
    }
}
