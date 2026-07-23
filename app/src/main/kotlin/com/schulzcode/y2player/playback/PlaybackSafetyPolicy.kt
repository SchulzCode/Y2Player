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

/** Pure safety state used by framework route and audio-focus callbacks. */
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
        // Bluetooth loss is never allowed to fall through to the built-in speaker. The
        // user preference only applies to a wired unplug, and a latched loss consumes
        // duplicate noisy/A2DP broadcasts without repeating the stop sequence.
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
    fun isRouteLossLatched(): Boolean = routeLossLatched
    fun hasPendingFocusResume(): Boolean = resumeAfterTransientFocusLoss && !routeLossLatched
}

internal object PlaybackRequestGate {
    fun accepts(callbackRequestId: Long, activeRequestId: Long): Boolean =
        callbackRequestId > 0 && callbackRequestId == activeRequestId
}

internal object PlaybackPositionPolicy {
    private const val END_GUARD_MS = 5_000L

    fun clampRestored(positionMs: Long, durationMs: Long): Long {
        if (positionMs <= 0 || durationMs <= 0 || positionMs >= durationMs) return 0
        return if (durationMs - positionMs <= END_GUARD_MS) 0 else positionMs
    }
}

internal class GenerationGuard {
    private var generation = 0L
    fun advance(): Long = ++generation
    fun isCurrent(candidate: Long): Boolean = candidate == generation
}
