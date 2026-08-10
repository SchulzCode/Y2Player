package com.schulzcode.y2player.playback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackContinuationArchitectureTest {
    @Test
    fun `standard promotion is published only after output becomes audible`() {
        val engine = engineSource()
        val promotion = engine.substringAfter("private fun promoteWithFlush(prepared: Slot)")
            .substringBefore("private fun notifyPromotion")

        assertTrue(promotion.contains("output.resume()"))
        assertTrue(promotion.contains("scheduleTransitionAnnouncement("))
        assertTrue(promotion.contains("FIRST_AUDIBLE_OUTPUT_FRAME"))
        assertFalse(promotion.contains("listener?.onTransitioned"))
    }

    @Test
    fun `successful PCM writes are acknowledged once per decoder slot`() {
        val engine = engineSource()

        assertTrue(engine.contains("var firstPcmWriteLogged: Boolean = false"))
        assertTrue(engine.contains("first PCM written request="))
        assertTrue(engine.contains("playing=${'$'}{output.isPlaying}"))
    }

    @Test
    fun `changing transition configuration invalidates a prepared successor`() {
        val service = serviceSource()
        val sync = service.substringAfter("private fun syncTransition()")
            .substringBefore("private fun syncReplayGain()")

        assertTrue(sync.contains("engine.configureTransition"))
        assertTrue(sync.contains("if (!engine.isTransitioning) clearPreload()"))
        assertTrue(sync.indexOf("engine.configureTransition") < sync.indexOf("clearPreload()"))
    }

    @Test
    fun `stale promotions and transitions remain request gated`() {
        val service = serviceSource()
        val promotion = service.substringAfter("override fun onTrackPromoted(")
            .substringBefore("override fun onTransitioned")
        val transitioned = service.substringAfter("private fun handleTransitioned(")
            .substringBefore("override fun onCompleted")

        assertTrue(promotion.contains("PlaybackRequestGate.accepts"))
        assertTrue(transitioned.contains("requestId != lastPromotedRequestId"))
    }

    @Test
    fun `preload preparation failure retains bounded recovery`() {
        val service = serviceSource()
        val failure = service.substringAfter("override fun onNextError(")
            .substringBefore("override fun onPermanentLoss")

        assertTrue(failure.contains("preloadRetryCount < MAX_TRACK_RETRIES"))
        assertTrue(failure.contains("preloadTrack(failedTrack)"))
        assertTrue(failure.contains("clearPreload(preserveAttemptGuard = true)"))
    }

    @Test
    fun `pausing a terminal drain preserves completion for resume`() {
        val engine = engineSource()
        val pause = engine.substringAfter("private fun performPause()")
            .substringBefore("private fun performSeek")

        assertFalse(pause.contains("clearCompletion()"))
        assertFalse(pause.contains("completionPending = false"))
    }

    @Test
    fun `a paused transition acknowledgement cannot publish playing`() {
        val service = serviceSource()
        val transitioned = service.substringAfter("private fun handleTransitioned(")
            .substringBefore("override fun onCompleted")

        assertTrue(transitioned.contains("engine.state == EngineState.PAUSED"))
        assertTrue(transitioned.contains("status = PlaybackStatus.PAUSED"))
        assertTrue(transitioned.indexOf("status = PlaybackStatus.PAUSED") < transitioned.indexOf("recordCurrentPlaybackStart()"))
    }

    @Test
    fun `seek after promotion retargets acknowledgement to new audible PCM`() {
        val engine = engineSource()
        val seek = engine.substringAfter("private fun performSeek(positionMs: Long)")
            .substringBefore("private fun performCancel")

        assertTrue(seek.contains("retargetPendingTransitionAfterFlush()"))
        assertFalse(seek.contains("announcePendingTransition()"))
    }

    @Test
    fun `manual next retains prepared and fallback continuation paths`() {
        val service = serviceSource()
        val next = service.substringAfter("private fun nextInternal(userInitiated: Boolean)")
            .substringBefore("private fun finishQueue")

        assertTrue(next.contains("engine.skipToPreparedNext()"))
        assertTrue(next.contains("moveToNextAvailable(ignoreRepeatOne = userInitiated"))
        assertTrue(next.contains("prepareCurrent(shouldAutoPlay, 0)"))
    }

    private fun engineSource(): String = source("FfmpegPlaybackEngine.kt")
    private fun serviceSource(): String = source("PlaybackService.kt")

    private fun source(name: String): String = File(
        repositoryRoot(),
        "app/src/main/kotlin/com/schulzcode/y2player/playback/$name"
    ).readText()

    private fun repositoryRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "app/src/main/AndroidManifest.xml").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("repository root not found")
    }
}
