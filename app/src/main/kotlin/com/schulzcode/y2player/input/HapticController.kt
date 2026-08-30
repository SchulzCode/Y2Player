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

class HapticController(
    context: Context,
    private val eventLog: EventLog? = null
) {
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? =
        (context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.takeIf { runCatching { it.hasVibrator() }.getOrDefault(false) }

    val available: Boolean get() = vibrator != null

    private val limiter = HapticRateLimiter()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var level: HapticLevel = HapticLevel.OFF
    @Volatile private var durationMs: Long = 0L
    private var failures = 0
    private var lastAggregateAt = 0L

    private val pulseRunnable = Runnable {
        val ms = durationMs
        val device = vibrator
        if (ms <= 0L || device == null) return@Runnable
        @Suppress("DEPRECATION")
        val failed = runCatching { device.vibrate(ms) }.isFailure
        if (failed) onPulseFailed()
    }

    @Synchronized
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

    @Synchronized
    fun acceptedAction() {
        if (!level.enabled || vibrator == null) return
        if (!limiter.allow(SystemClock.elapsedRealtime())) return
        handler?.post(pulseRunnable)
        maybeLogAggregate()
    }

    @Synchronized
    fun usbConnected() {
        val device = vibrator
        val ms = HapticPolicy.usbConnectionDuration(level, device != null)
        if (ms <= 0L || device == null) return
        handler?.post {
            @Suppress("DEPRECATION")
            val failed = runCatching { device.vibrate(ms) }.isFailure
            if (failed) onPulseFailed()
        }
    }

    @Synchronized
    fun release() {
        cancel()
        flushAggregate()
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
        eventLog?.logRateLimited(
            "haptic_fail",
            AGGREGATE_WINDOW_MS,
            Sev.WARN,
            Sub.INPUT,
            Ev.HAPTIC_FAIL,
            "failures" to failures
        )
    }

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
