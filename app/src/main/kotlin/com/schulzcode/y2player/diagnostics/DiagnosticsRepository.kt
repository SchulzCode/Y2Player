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

    private val stateExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "y2-diagnostics-state").apply { isDaemon = true }
    }

    @Volatile private var state = DiagnosticsState()

    fun addListener(listener: Listener, emitImmediately: Boolean = true) {
        listeners += listener
        if (emitImmediately) listener.onChanged(state)
    }

    fun removeListener(listener: Listener) { listeners -= listener }

    fun snapshot(): DiagnosticsState = state

    fun setError(message: String) = publish { it.copy(lastError = message) }

    fun refresh() = publish { it }

    fun setPlaybackHistory(sessions: Int, bytes: Long) {
        if (state.historySessions == sessions && state.historyBytes == bytes) return
        publish { it.copy(historySessions = sessions, historyBytes = bytes) }
    }

    fun setUsbState(usb: UsbState) {
        if (state.usb == usb) return
        publish { it.copy(usb = usb) }
    }

    fun export(): Result<File> = runCatching {
        val preferred = Y2StoragePaths.roots.firstOrNull { it.id == "internal" && it.directory.canWrite() }
            ?: Y2StoragePaths.availableRoots().firstOrNull()
            ?: error("No writable music storage is mounted")
        val destination = File(preferred.directory, "Y2Player/diagnostics")
        val output = logger.exportTo(destination)

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
