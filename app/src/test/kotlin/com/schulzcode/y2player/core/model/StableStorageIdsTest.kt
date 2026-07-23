package com.schulzcode.y2player.core.model

import com.schulzcode.y2player.input.HapticLevel
import com.schulzcode.y2player.playback.VolumeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StableStorageIdsTest {
    @Test
    fun repeatModeReadsStableAndLegacyValues() {
        assertEquals(RepeatMode.ALL, RepeatMode.fromStorage("all"))
        assertEquals(RepeatMode.ALL, RepeatMode.fromStorage("ALL"))
        assertEquals(RepeatMode.OFF, RepeatMode.fromStorage("renamed-or-invalid"))
    }

    @Test
    fun trackSortOrderReadsStableAndLegacyValues() {
        assertEquals(TrackSortOrder.RECENT, TrackSortOrder.fromStorage("recent"))
        assertEquals(TrackSortOrder.RECENT, TrackSortOrder.fromStorage("RECENT"))
        assertEquals(TrackSortOrder.TITLE, TrackSortOrder.fromStorage("renamed-or-invalid"))
    }

    @Test
    fun volumeModeReadsStableAndLegacyValuesAndDefaultsToSystem() {
        assertEquals(VolumeMode.PERCEPTUAL, VolumeMode.fromStorage("perceptual"))
        assertEquals(VolumeMode.PERCEPTUAL, VolumeMode.fromStorage("PERCEPTUAL"))
        // Unknown or absent must fall back to SYSTEM (the Android music stream),
        // never to hidden in-app attenuation.
        assertEquals(VolumeMode.SYSTEM, VolumeMode.fromStorage(null))
        assertEquals(VolumeMode.SYSTEM, VolumeMode.fromStorage("renamed-or-invalid"))
    }

    @Test
    fun hapticLevelReadsStableAndLegacyValuesAndDefaultsToOff() {
        assertEquals(HapticLevel.MEDIUM, HapticLevel.fromStorage("medium"))
        assertEquals(HapticLevel.MEDIUM, HapticLevel.fromStorage("MEDIUM"))
        // Unknown must never restore to a buzzing device.
        assertEquals(HapticLevel.OFF, HapticLevel.fromStorage(null))
        assertEquals(HapticLevel.OFF, HapticLevel.fromStorage("renamed-or-invalid"))
    }

    @Test
    fun audioQualityModeReadsStableAndLegacyValuesAndDefaultsToBalanced() {
        assertEquals(AudioQualityMode.DIRECT_DAC, AudioQualityMode.fromStorage("direct_dac"))
        assertEquals(AudioQualityMode.DIRECT_DAC, AudioQualityMode.fromStorage("DIRECT_DAC"))
        assertEquals(AudioQualityMode.BALANCED, AudioQualityMode.fromStorage(null))
        assertEquals(AudioQualityMode.BALANCED, AudioQualityMode.fromStorage("renamed-or-invalid"))
    }
}
