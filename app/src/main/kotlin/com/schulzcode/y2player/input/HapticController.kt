package com.schulzcode.y2player.input

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Vibrator
import com.schulzcode.y2player.diagnostics.Ev
import com.schulzcode.y2player.diagnostics.EventLog
import com.schulzcode.y2player.diagnostics.Sev
import com.schulzcode.y2player.diagnostics.Sub

/**
 * Fires wheel detent pulses.
 *
 * ## Why the pulse leaves the input thread
 * `Vibrator.vibrate()` is a Binder call into system_server. It is usually fast,
 * but it is still IPC, and it would sit directly in the wheel key path in front
 * of the reducer and the next frame. A spinning wheel would then pay that cost
 * on every detent. One low-priority thread owns every vibrate call, so scrolling
 * never waits on the system server.
 *
 * ## Why this cannot queue
 * The rate limiter decides on the caller thread *before* posting, so at most one
 * pulse per [HapticRateLimiter.MIN_INTERVAL_MS] is ever handed to the worker. A
 * single reused [Runnable] is posted — no lambda capture, no allocation per
 * detent — and the duration it reads is a volatile field.
 */
class HapticController(
    context: Context,
    private val eventLog: EventLog? = null
) {
    // VIBRATOR_SERVICE is deprecated in favour of VibratorManager (API 31).
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? =
        (context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.takeIf { runCatching { it.hasVibrator() }.getOrDefault(false) }

    /** True when the hardware can actually vibrate. The setting hides itself when false. */
    val available: Boolean get() = vibrator != null

    private val limiter = HapticRateLimiter()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var level: HapticLevel = HapticLevel.OFF
    @Volatile private var durationMs: Long = 0L
    @Volatile private var suspended = false
    private var failures = 0
    private var lastAggregateAt = 0L

    private val pulseRunnable = Runnable {
        val ms = durationMs
        val device = vibrator
        if (ms <= 0L || device == null || suspended) return@Runnable
        @Suppress("DEPRECATION") // vibrate(long) is the only form available on API 19.
        val failed = runCatching { device.vibrate(ms) }.isFailure
        if (failed) onPulseFailed()
    }

    fun setLevel(value: HapticLevel) {
        if (value == level) return
        level = value
        durationMs = value.durationMs
        limiter.reset()
        if (!value.enabled) {
            cancel()
            stopWorker()
        } else if (available) {
            startWorker()
        }
        eventLog?.info(Sub.INPUT, Ev.HAPTIC_LEVEL, "level" to value.storageId, "available" to available)
    }

    /**
     * Requests one detent pulse. Safe to call from the input path; returns
     * immediately and does no work at all when disabled.
     */
    fun wheelDetent() {
        if (!level.enabled || vibrator == null || suspended) return
        // elapsedRealtime, not uptimeMillis: it cannot stall across a deep-sleep
        // pause, so the window can never be artificially stretched after wake.
        if (!limiter.allow(SystemClock.elapsedRealtime())) return
        handler?.post(pulseRunnable)
        maybeLogAggregate()
    }

    /**
     * Stops feedback while the player is not in the foreground, and during
     * shutdown. The motor must never keep running after the UI is gone.
     */
    fun suspend() {
        suspended = true
        cancel()
        flushAggregate()
    }

    fun resume() {
        suspended = false
        limiter.reset()
        if (level.enabled && available) startWorker()
    }

    fun release() {
        suspend()
        stopWorker()
    }

    private fun cancel() {
        handler?.removeCallbacks(pulseRunnable)
        runCatching { vibrator?.cancel() }
    }

    private fun startWorker() {
        if (handler != null) return
        val created = HandlerThread("y2-haptics", Thread.MIN_PRIORITY)
        created.start()
        thread = created
        handler = Handler(created.looper)
    }

    private fun stopWorker() {
        handler = null
        thread?.quit()
        thread = null
    }

    private fun onPulseFailed() {
        failures++
        // Rate limited by the event log itself: a failing motor fails on every
        // pulse, and one line per spin is enough to diagnose it.
        eventLog?.logRateLimited(
            "haptic_fail",
            AGGREGATE_WINDOW_MS,
            Sev.WARN,
            Sub.INPUT,
            Ev.HAPTIC_FAIL,
            "failures" to failures
        )
    }

    /** One line per minute of use, never one per pulse. */
    private fun maybeLogAggregate() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAggregateAt < AGGREGATE_WINDOW_MS) return
        lastAggregateAt = now
        flushAggregate()
    }

    private fun flushAggregate() {
        val counters = limiter.drainCounters()
        if (counters[0] == 0 && counters[1] == 0) return
        eventLog?.debug(
            Sub.INPUT,
            Ev.HAPTIC_SUMMARY,
            "pulses" to counters[0],
            "suppressed" to counters[1],
            "failures" to failures
        )
    }

    private companion object {
        const val AGGREGATE_WINDOW_MS = 60_000L
    }
}
