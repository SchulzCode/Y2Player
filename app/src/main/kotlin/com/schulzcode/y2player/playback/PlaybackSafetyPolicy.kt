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

    /**
     * False exactly while a route loss is latched, i.e. while automatic starts
     * are suppressed. Also the assertion tests use to observe latching, so the
     * policy exposes one predicate rather than two spellings of it.
     */
    fun canAutomaticallyStart(): Boolean = !routeLossLatched
    fun hasPendingFocusResume(): Boolean = resumeAfterTransientFocusLoss && !routeLossLatched
}

/**
 * Whether a playback failure was the media's fault or the moment's.
 *
 * Only [UNSUPPORTED] is remembered against a file. Everything else must not be,
 * because the alternative is condemning a good track for an unrelated fault:
 * device logs from this player have already shown `MEDIA_ERROR_SERVER_DIED`
 * arriving because mediaserver died during a USB eject, with nothing wrong with
 * the file at all.
 */
enum class PlaybackFailure { UNSUPPORTED, TRANSIENT, UNKNOWN }

/**
 * Classifies `MediaPlayer` error codes.
 *
 * Deliberately conservative: a code is only called [PlaybackFailure.UNSUPPORTED]
 * when the framework explicitly said the media could not be handled. Anything
 * ambiguous stays [PlaybackFailure.UNKNOWN] and is not recorded, because a false
 * "not playable" label is worse than no label — the user cannot tell that the
 * app is wrong, and a working file looks broken forever.
 */
object PlaybackErrorClassifier {

    // Values of MediaPlayer.MEDIA_ERROR_*, named here because the constants for
    // the `extra` codes are not all public on API 19.
    private const val SERVER_DIED = 100
    private const val EXTRA_UNSUPPORTED = -1010
    private const val EXTRA_MALFORMED = -1007
    private const val EXTRA_IO = -1004
    private const val EXTRA_TIMED_OUT = -110

    fun classify(what: Int, extra: Int): PlaybackFailure = when {
        // mediaserver restarting says nothing about this particular file.
        what == SERVER_DIED -> PlaybackFailure.TRANSIENT
        extra == EXTRA_IO || extra == EXTRA_TIMED_OUT -> PlaybackFailure.TRANSIENT
        extra == EXTRA_UNSUPPORTED || extra == EXTRA_MALFORMED -> PlaybackFailure.UNSUPPORTED
        else -> PlaybackFailure.UNKNOWN
    }
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
