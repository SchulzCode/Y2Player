package com.schulzcode.y2player.playback

enum class PrivateAudioRoute { WIRED, BLUETOOTH }

data class PrivateRouteSnapshot(
    val wired: Boolean = false,
    val bluetooth: Boolean = false
) {
    fun connected(): Set<PrivateAudioRoute> = buildSet {
        if (wired) add(PrivateAudioRoute.WIRED)
        if (bluetooth) add(PrivateAudioRoute.BLUETOOTH)
    }
}

class PlaybackSafetyPolicy {
    private val guardedRoutes = linkedSetOf<PrivateAudioRoute>()
    private var routeGuardArmed = false
    private var routeLossLatched = false
    private var resumeAfterTransientFocusLoss = false

    fun onExplicitPlaybackRequest(routes: PrivateRouteSnapshot) {
        routeGuardArmed = true
        routeLossLatched = false
        resumeAfterTransientFocusLoss = false
        guardedRoutes.clear()
        guardedRoutes.addAll(routes.connected())
    }

    fun onRestoredPausedSession(routes: PrivateRouteSnapshot) {
        val connected = routes.connected()
        routeGuardArmed = connected.isNotEmpty()
        routeLossLatched = false
        resumeAfterTransientFocusLoss = false
        guardedRoutes.clear()
        guardedRoutes.addAll(connected)
    }

    fun onRoutesChanged(
        routes: PrivateRouteSnapshot,
        becomingNoisy: Boolean,
        speakerFallbackAllowed: Boolean
    ): Boolean {
        val connected = routes.connected()
        val lostGuardedRoute = guardedRoutes.any { it !in connected }
        // Bluetooth loss never falls through to the speaker. The preference covers a
        // wired unplug only.
        val guardedBluetooth = PrivateAudioRoute.BLUETOOTH in guardedRoutes
        val bluetoothLoss = guardedBluetooth &&
            (PrivateAudioRoute.BLUETOOTH !in connected || becomingNoisy)
        val mustPause = routeGuardArmed && !routeLossLatched &&
            (bluetoothLoss || (!speakerFallbackAllowed && (lostGuardedRoute || becomingNoisy)))
        guardedRoutes.clear()
        guardedRoutes.addAll(connected)
        if (mustPause) onRouteLoss()
        return mustPause
    }

    fun onTransientFocusLoss(wasPlaying: Boolean): Boolean {
        resumeAfterTransientFocusLoss = wasPlaying && !routeLossLatched
        return wasPlaying
    }

    fun onPermanentFocusLoss() {
        resumeAfterTransientFocusLoss = false
    }

    fun onManualPause() {
        resumeAfterTransientFocusLoss = false
    }

    fun onSessionCleared() {
        routeGuardArmed = false
        routeLossLatched = false
        resumeAfterTransientFocusLoss = false
        guardedRoutes.clear()
    }

    fun onRouteLoss() {
        routeLossLatched = true
        resumeAfterTransientFocusLoss = false
    }

    fun consumeFocusResume(): Boolean {
        val resume = resumeAfterTransientFocusLoss && !routeLossLatched
        resumeAfterTransientFocusLoss = false
        return resume
    }

    fun canAutomaticallyStart(): Boolean = !routeLossLatched
    fun hasPendingFocusResume(): Boolean = resumeAfterTransientFocusLoss && !routeLossLatched
}

enum class PlaybackFailure { UNSUPPORTED, TRANSIENT, UNKNOWN }

internal object PlaybackRequestGate {
    fun accepts(callbackRequestId: Long, activeRequestId: Long): Boolean =
        callbackRequestId > 0 && callbackRequestId == activeRequestId
}

internal object PlaybackPositionPolicy {
    const val END_GUARD_MS = 5_000L
    private const val MINIMUM_PLAYABLE_TAIL_MS = 250L

    const val AUDIOBOOK_MIN_SAVE_MS = 10_000L

    // Speech needs a run-up; resuming mid-sentence loses the thread.
    const val AUDIOBOOK_REWIND_MS = 5_000L

    fun clampRestored(positionMs: Long, durationMs: Long): Long {
        if (positionMs <= 0 || durationMs <= 0 || positionMs >= durationMs) return 0
        return if (durationMs - positionMs <= END_GUARD_MS) 0 else positionMs
    }

    fun clampSeek(positionMs: Long, durationMs: Long): Long {
        if (durationMs <= 0) return 0
        // Seeking to the exact duration can leave a decoder at EOF without a
        // final PCM buffer. Keep a tiny playable tail so normal completion owns
        // the queue transition.
        val latestPlayablePosition = (durationMs - MINIMUM_PLAYABLE_TAIL_MS).coerceAtLeast(0)
        return positionMs.coerceIn(0, latestPlayablePosition)
    }

    fun audiobookSavePosition(positionMs: Long, durationMs: Long): Long = when {
        positionMs < AUDIOBOOK_MIN_SAVE_MS -> 0
        durationMs > 0 && durationMs - positionMs <= END_GUARD_MS -> 0
        else -> positionMs
    }

    fun audiobookResumePosition(positionMs: Long, durationMs: Long): Long {
        if (positionMs <= 0) return 0
        if (durationMs > 0 && positionMs >= durationMs) return 0
        return (positionMs - AUDIOBOOK_REWIND_MS).coerceAtLeast(0)
    }
}

internal class ListeningSession(
    val startedAtUtcMs: Long,
    val startPositionMs: Long,
    private val uptimeMs: () -> Long
) {
    private var accumulatedMs = 0L
    private var since: Long? = null

    fun resume() {
        if (since == null) since = uptimeMs()
    }

    fun pause() {
        val start = since ?: return
        since = null
        accumulatedMs += (uptimeMs() - start).coerceAtLeast(0)
    }

    fun listenedMs(): Long =
        accumulatedMs + (since?.let { (uptimeMs() - it).coerceAtLeast(0) } ?: 0L)
}

internal class GenerationGuard {
    private var generation = 0L
    fun advance(): Long = ++generation
    fun isCurrent(candidate: Long): Boolean = candidate == generation
}
