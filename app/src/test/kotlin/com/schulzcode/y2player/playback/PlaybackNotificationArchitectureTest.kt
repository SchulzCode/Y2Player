package com.schulzcode.y2player.playback

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNotificationArchitectureTest {
    @Test fun playingSnapshotIsBuiltBeforeEnteringForeground() {
        val source = File(
            repositoryRoot(),
            "app/src/main/kotlin/com/schulzcode/y2player/playback/PlaybackService.kt"
        ).readText()

        val confirmedStart = source.substringAfter("override fun onStarted(requestId: Long)")
            .substringBefore("override fun onNextTrackNeeded")
        assertTrue(confirmedStart.indexOf("snapshot = buildSnapshot(") < confirmedStart.indexOf("enterForeground()"))

        val transitioned = source.substringAfter("private fun handleTransitioned(requestId: Long, durationMs: Long)")
            .substringBefore("override fun onCompleted")
        assertTrue(transitioned.indexOf("snapshot = buildSnapshot(") < transitioned.indexOf("enterForeground()"))
    }

    private fun repositoryRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "app/src/main/AndroidManifest.xml").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("repository root not found")
    }
}
