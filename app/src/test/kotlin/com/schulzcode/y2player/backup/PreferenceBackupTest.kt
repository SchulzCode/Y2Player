package com.schulzcode.y2player.backup

import com.schulzcode.y2player.core.model.AudioQualityMode
import com.schulzcode.y2player.core.model.AlbumSortOrder
import com.schulzcode.y2player.core.model.TrackSortOrder
import com.schulzcode.y2player.core.model.YearSortOrder
import com.schulzcode.y2player.core.state.PlayerPreferencesState
import com.schulzcode.y2player.input.HapticLevel
import com.schulzcode.y2player.playback.CrossfadeMode
import com.schulzcode.y2player.playback.ReplayGainMode
import com.schulzcode.y2player.playback.VolumeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceBackupTest {
    @Test fun everyApplicationSettingSurvivesSerializationAndAColdRead() {
        val expected = PlayerPreferencesState(
            uiSoundEffectsEnabled = true,
            verboseDiagnostics = true,
            volumeMode = VolumeMode.PERCEPTUAL,
            volumeLevel = 13,
            replayGainMode = ReplayGainMode.TRACK_WHEN_SHUFFLING,
            hapticLevel = HapticLevel.STRONG,
            wrapLists = false,
            keepScreenOnWhilePlaying = true,
            extraTrackInfo = true,
            lightTheme = true,
            localKeysWhileScreenOff = true,
            pauseOnDisconnect = false,
            resumePosition = false,
            sortOrder = TrackSortOrder.RECENT,
            albumSortOrder = AlbumSortOrder.YEAR_DESCENDING,
            yearSortOrder = YearSortOrder.OLDEST_FIRST,
            gaplessEnabled = false,
            crossfadeMs = 6_000,
            crossfadeMode = CrossfadeMode.WHILE_SHUFFLING,
            pauseResumeFadeMs = 300,
            seekStepMs = 60_000,
            longSeekStepMs = 60_000,
            previousRestartThresholdMs = 6_000,
            duckOnFocusLoss = false,
            audioQualityMode = AudioQualityMode.DIRECT_DAC,
            audioEffectsEnabled = true,
            equalizerPreset = -1,
            equalizerBandLevelsMb = listOf(-3_000, -300, 0, 300, 3_000),
            bassStrength = 1_000,
            loudnessGainMb = 300,
            balance = -100
        )
        val persistedBackupFields = PreferenceBackup.encode(expected).toMap()
        assertEquals(expected, PreferenceBackup.decode(persistedBackupFields))
    }

    @Test fun oldBackupsReceiveStableDefaultsForNewSortingPolicies() {
        val oldFields = PreferenceBackup.encode(PlayerPreferencesState()).toMutableMap().apply {
            remove("album_sort_order")
            remove("year_sort_order")
        }

        val decoded = PreferenceBackup.decode(oldFields)
        assertEquals(AlbumSortOrder.TITLE, decoded.albumSortOrder)
        assertEquals(YearSortOrder.NEWEST_FIRST, decoded.yearSortOrder)
    }
}
