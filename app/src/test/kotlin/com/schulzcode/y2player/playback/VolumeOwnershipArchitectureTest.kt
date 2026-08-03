package com.schulzcode.y2player.playback

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class VolumeOwnershipArchitectureTest {
    private val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
        .first { File(it, "app/src/main/kotlin").isDirectory }

    @Test fun obsoleteScreenLifecycleVolumeHandoffIsAbsent() {
        val sources = listOf(
            "app/src/main/kotlin/com/schulzcode/y2player/ui/MainActivity.kt",
            "app/src/main/kotlin/com/schulzcode/y2player/playback/PlaybackService.kt",
            "app/src/main/kotlin/com/schulzcode/y2player/settings/AppPreferences.kt"
        ).map { File(root, it).readText() }
        assertFalse(sources.any { it.contains("backgroundVolumeHandoff", ignoreCase = true) })
    }

    @Test fun systemVolumeIncreaseWaitsUntilTheLiveOutputGainWasApplied() {
        val source = File(
            root,
            "app/src/main/kotlin/com/schulzcode/y2player/playback/PlaybackService.kt"
        ).readText()
        val transition = source.substringAfter("private fun applyVolumeModeTransitionInternal")
            .substringBefore("private fun normalizeSystemVolumeForAppControl")
        assertTrue(transition.contains("raiseSystemVolumeAfterOutputGain(systemVolumeIndex)"))

        val acknowledgement = source.substringAfter("private fun raiseSystemVolumeAfterOutputGain")
            .substringBefore("private fun beginExplicitPlaybackRequest")
        assertTrue(acknowledgement.indexOf("applyLiveOutputGain {") < acknowledgement.indexOf("setMusicStreamVolume(systemVolumeIndex)"))
    }

    @Test fun earlyRouteEventsCannotApplyGainBeforeTheEngineExists() {
        val source = File(
            root,
            "app/src/main/kotlin/com/schulzcode/y2player/playback/PlaybackService.kt"
        ).readText()
        val normalization = source.substringAfter("private fun normalizeSystemVolumeForAppControl")
            .substringBefore("private fun setMusicStreamVolume")
        assertTrue(normalization.contains("!::engine.isInitialized"))
    }

    @Test fun steadyAndTransientGainAreComposedOnAudioTrack() {
        val source = File(
            root,
            "app/src/main/kotlin/com/schulzcode/y2player/playback/PlaybackService.kt"
        ).readText()
        val liveGain = source.substringAfter("private fun applyLiveOutputGain")
            .substringBefore("private fun raiseSystemVolumeAfterOutputGain")
        assertTrue(liveGain.contains("engine.setOutputGain(outputVolume * transientOutputGain"))
    }

    @Test fun wheelSystemVolumeUsesTheFrameworkUiNotificationPath() {
        val source = File(
            root,
            "app/src/main/kotlin/com/schulzcode/y2player/ui/MainActivity.kt"
        ).readText()
        val adjust = source.substringAfter("private fun adjustVolume(direction: Int)")
            .substringBefore("private fun refreshPlaybackHistory")
        assertTrue(adjust.contains("AudioManager.FLAG_SHOW_UI"))
    }
}
