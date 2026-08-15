package com.schulzcode.y2player.diagnostics

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

class LogWriter(threadName: String = "y2-log") {
    interface Sink {
        fun hasUrgentPending(): Boolean

        fun drainAndWrite()
    }

    private val sinks = CopyOnWriteArrayList<Sink>()
    private val signal = Object()
    private var pending = false

    init {
        Thread(::loop, threadName).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun register(sink: Sink) {
        sinks.addIfAbsent(sink)
    }

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
                sinks.forEach { sink -> runCatching { sink.drainAndWrite() } }
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
            }
        }
    }

    private fun awaitWork() {
        synchronized(signal) {
            while (!pending) signal.wait()
            pending = false
        }
    }

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
        const val BATCH_WINDOW_MS = 5_000L
    }
}
