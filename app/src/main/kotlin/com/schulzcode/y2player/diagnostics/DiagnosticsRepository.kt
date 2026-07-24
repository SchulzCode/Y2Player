package com.schulzcode.y2player.diagnostics

import android.os.Handler
import android.os.Looper
import com.schulzcode.y2player.storage.UsbState
import com.schulzcode.y2player.storage.Y2StoragePaths
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

class DiagnosticsRepository(
    private val logger: DiagnosticLogger,
    private val eventLog: EventLog? = null
) {
    fun interface Listener { fun onChanged(state: DiagnosticsState) }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Every state mutation runs here, in order. Two reasons:
     * - `logger.recentLines()` reads the log file (and briefly waits for the
     *   writer to flush). Several publishers call in from the main thread
     *   (probe status, idle summary), and file I/O must never run there.
     * - Applying the transform on one thread makes concurrent publishers
     *   (USB executor, main thread, export worker) serialize instead of
     *   losing updates to a read-copy-write race.
     */
    private val stateExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "y2-diagnostics-state").apply { isDaemon = true }
    }

    @Volatile private var state = DiagnosticsState()

    fun addListener(listener: Listener, emitImmediately: Boolean = true) {
        listeners += listener
        if (emitImmediately) listener.onChanged(state)
    }

    fun removeListener(listener: Listener) { listeners -= listener }

    /**
     * Last published state. `recentLines` is as fresh as the latest publish
     * rather than re-read here — reading the log file on the caller's thread is
     * exactly what this class exists to avoid.
     */
    fun snapshot(): DiagnosticsState = state

    fun setProbeRunning(running: Boolean) = publish { it.copy(formatProbeRunning = running, lastError = null) }
    fun setProbeResults(results: List<FormatProbeResult>) = publish {
        it.copy(formatProbeRunning = false, formatProbeResults = results, lastError = null)
    }
    fun setError(message: String) = publish { it.copy(formatProbeRunning = false, lastError = message) }

    fun refresh() = publish { it }

    /** Read-only USB status for the Diagnostics screen. Published only on change. */
    fun setUsbState(usb: UsbState) {
        if (state.usb == usb) return
        publish { it.copy(usb = usb) }
    }

    /**
     * Exports both logs: the human-readable text report and every retained
     * NDJSON event file. A support bundle is useless if it contains only one of
     * them — the text log explains, the event log proves.
     *
     * Blocking; callers run it off the main thread (MainActivity uses its
     * background executor).
     */
    fun export(): Result<File> = runCatching {
        val preferred = Y2StoragePaths.roots.firstOrNull { it.id == "internal" && it.directory.canWrite() }
            ?: Y2StoragePaths.availableRoots().firstOrNull()
            ?: error("No writable music storage is mounted")
        val destination = File(preferred.directory, "Y2Player/diagnostics")
        val output = logger.exportTo(destination)

        // Flush pending events first, then copy the rotated set beside the report.
        var eventFiles = 0
        eventLog?.let { log ->
            log.flush()
            val stamp = output.nameWithoutExtension.removePrefix("y2-diagnostics-")
            log.logFiles().forEachIndexed { index, source ->
                runCatching { source.copyTo(File(destination, "y2-events-$stamp-$index.ndjson"), overwrite = true) }
                    .onSuccess { eventFiles += 1 }
            }
        }
        publish { it.copy(exportedPath = output.absolutePath, lastError = null) }
        // Recorded after the copy so the exported bundle itself does not contain
        // the record of its own export, which would always be the last line.
        eventLog?.info(
            Sub.DIAG, Ev.DIAGNOSTICS_EXPORT,
            "destination" to destination.path,
            "report" to output.name,
            "eventFiles" to eventFiles
        )
        output
    }.onFailure { setError(it.message ?: it.javaClass.simpleName) }

    private fun publish(transform: (DiagnosticsState) -> DiagnosticsState) {
        stateExecutor.execute {
            val value = transform(state).copy(recentLines = logger.recentLines())
            state = value
            mainHandler.post { listeners.forEach { it.onChanged(value) } }
        }
    }
}
