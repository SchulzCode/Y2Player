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
        val raiseDecision = "VolumeModeTransitionDecision.RAISE_SYSTEM_VOLUME"
        assertTrue(acknowledgement.indexOf("engineAtRequest.setOutputGain") < acknowledgement.indexOf(raiseDecision))
        assertTrue(acknowledgement.indexOf(raiseDecision) < acknowledgement.indexOf("setMusicStreamVolume(systemVolumeIndex)"))

        val engine = File(
            root,
            "app/src/main/kotlin/com/schulzcode/y2player/playback/FfmpegPlaybackEngine.kt"
        ).readText()
        val applyGain = engine.substringAfter("private fun performOutputGain")
            .substringBefore("private fun confirmOutputGainActivation")
        val confirmGain = engine.substringAfter("private fun confirmOutputGainActivation")
            .substringBefore("private fun cancelPendingOutputGain")
        assertFalse(applyGain.contains("completeOutputGain(command, OutputGainApplyResult.APPLIED)") &&
            applyGain.indexOf("completeOutputGain(command, OutputGainApplyResult.APPLIED)") <
            applyGain.indexOf("OutputGainActivationPolicy.confirmationFrame"))
        assertTrue(confirmGain.contains("OutputGainActivationPolicy.isActive"))
        assertTrue(confirmGain.contains("completeOutputGain(command, OutputGainApplyResult.APPLIED)"))
    }

    @Test fun outputReplacementAndFlushPathsInvalidateAnUnconfirmedGain() {
        val source = File(
            root,
            "app/src/main/kotlin/com/schulzcode/y2player/playback/FfmpegPlaybackEngine.kt"
        ).readText()

        listOf("performPrepare", "promoteWithFlush", "performSeek", "performCancel").forEach { function ->
            val body = source.substringAfter("private fun $function")
                .substringBefore("private fun", missingDelimiterValue = source.substringAfter("private fun $function"))
            assertTrue("$function must cancel its pending output fence", body.contains("cancelPendingOutputGain"))
        }
        val release = source.substringAfter("private fun performRelease")
            .substringBefore("private fun schedulePump")
        assertTrue(release.contains("cancelPendingOutputGain(OutputGainApplyResult.RELEASED)"))
    }

    @Test fun leavingInAppModeLowersSystemVolumeBeforeRemovingAttenuation() {
        val source = File(
            root,
            "app/src/main/kotlin/com/schulzcode/y2player/playback/PlaybackService.kt"
        ).readText()
        val transition = source.substringAfter("private fun applyVolumeModeTransitionInternal")
            .substringBefore("private fun normalizeSystemVolumeForAppControl")
        val leaving = transition.substringAfter("} else {")

        assertTrue(leaving.indexOf("setMusicStreamVolume(systemVolumeIndex)") < leaving.indexOf("applyPreferencesInternal(value)"))
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
