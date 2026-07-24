package com.schulzcode.y2player.diagnostics

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The process's single log worker thread.
 *
 * Both loggers ([DiagnosticLogger] and [EventLog]) used to own a daemon thread
 * each. On a two-core Cortex-A7 that is one thread too many for work that is
 * idle almost all the time and I/O-bound when it is not, so they now share one.
 *
 * What is deliberately *not* shared is the queue. Each sink keeps its own
 * bounded queue, its own capacity and its own drop accounting, because
 * [EventLog]'s "bounded with visible loss" contract (overflow drops the oldest
 * and reports a `dropped` count on the next event) is only meaningful if a burst
 * of human-readable lines cannot silently evict structured events. Sharing the
 * thread costs nothing; sharing the queue would cost that guarantee.
 *
 * ## Idle cost
 * The thread blocks on [awaitWork] with no timeout, so a device doing nothing
 * schedules nothing. There is no timer and no polling.
 *
 * ## Coalescing
 * After a wake the writer waits up to [BATCH_WINDOW_MS] before draining, so a
 * burst becomes one write per sink instead of one write per entry — the eMMC on
 * this device is slow enough that batching matters. A sink reporting
 * [Sink.hasUrgentPending] (a warning, an error, or a pending flush marker)
 * skips the window and is written immediately.
 */
class LogWriter(threadName: String = "y2-log") {

    interface Sink {
        /** True when something queued must not wait for the coalescing window. */
        fun hasUrgentPending(): Boolean

        /**
         * Drains everything queued at this moment and writes it. Never blocks
         * waiting for more input. Called only on the writer thread.
         */
        fun drainAndWrite()
    }

    private val sinks = CopyOnWriteArrayList<Sink>()
    private val signal = Object()
    private var pending = false

    @Suppress("unused")
    private val worker = Thread(::loop, threadName).apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }

    fun register(sink: Sink) {
        sinks.addIfAbsent(sink)
    }

    /** Called from any thread after enqueuing into a sink's own queue. */
    fun wake() {
        synchronized(signal) {
            pending = true
            signal.notifyAll()
        }
    }

    private fun loop() {
        while (true) {
            try {
                awaitWork()
                if (!anyUrgent()) coalesce()
                // A sink that throws must not stop the other from being written,
                // and neither may become a second application fault.
                sinks.forEach { sink -> runCatching { sink.drainAndWrite() } }
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                // Logging must never become a secondary application crash.
            }
        }
    }

    private fun awaitWork() {
        synchronized(signal) {
            while (!pending) signal.wait()
            pending = false
        }
    }

    /**
     * Holds a freshly signalled batch briefly so a burst becomes one write.
     *
     * Clearing [pending] here is safe: this method always returns into a drain,
     * so a signal arriving during the window is never lost — at worst it is
     * absorbed into the drain that is about to happen anyway.
     */
    private fun coalesce() {
        val deadline = SystemClock.elapsedRealtime() + BATCH_WINDOW_MS
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L || anyUrgent()) return
            synchronized(signal) {
                pending = false
                signal.wait(remaining)
            }
        }
    }

    private fun anyUrgent(): Boolean = sinks.any { sink ->
        runCatching { sink.hasUrgentPending() }.getOrDefault(true)
    }

    companion object {
        /** Coalescing window. Shared by both sinks so one wake serves both. */
        const val BATCH_WINDOW_MS = 5_000L
    }
}
