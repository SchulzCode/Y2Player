package com.schulzcode.y2player.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Rotating file logger.
 *
 * Callers include the playback thread and broadcast receivers on the main thread, so
 * `info`/`warn`/`error` only enqueue: a synchronous open/append/close per line on slow
 * eMMC would add latency to track transitions and jank to the UI. The shared
 * [LogWriter] thread drains the queue in batches. Under overflow the oldest line is
 * dropped — losing a diagnostic line is better than blocking audio. The
 * uncaught-exception handler uses [crash], which writes synchronously because the
 * process is about to die.
 *
 * ## Verbosity
 * [info] is gated on [setVerbose]. Without it this logger wrote several lines to
 * internal flash on every track change, route change and discovered Bluetooth
 * device even with diagnostics switched off — an unbounded flash and battery
 * cost the user had no way to decline. [warn], [error] and [crash] are never
 * gated: they describe faults, and a fault that goes unrecorded because logging
 * was quiet is the one case where the log has failed at its job.
 *
 * This is **stricter than [EventLog]**, which follows the same preference but
 * only suppresses `DEBUG` and keeps `INFO` regardless. The asymmetry is
 * deliberate — NDJSON events are compact and bounded, these lines are chatty
 * prose — but it is a trap for anyone reading a quiet text log beside a
 * populated event log, so the transition is announced at [warn] level. Without
 * that announcement "quiet" and "broken" look identical.
 */
class DiagnosticLogger(
    context: Context,
    private val writer: LogWriter = LogWriter("y2-diagnostics")
) : LogWriter.Sink {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "diagnostics").apply { mkdirs() }
    private val activeFile = File(directory, "y2player.log")
    // Guards file access and the (non-thread-safe) date formatter.
    private val fileLock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private sealed interface Entry
    private class Line(val atMs: Long, val level: String, val category: String, val message: String) : Entry
    private class Flush(val latch: CountDownLatch) : Entry

    private val queue = ArrayBlockingQueue<Entry>(QUEUE_CAPACITY)
    @Volatile private var writerDisabled = false
    @Volatile private var verbose = true
    private var verboseAnnounced = false
    private var consecutiveWriteFailures = 0

    init {
        writer.register(this)
    }

    /**
     * Follows the user's verbose-diagnostics preference. Defaults to `true` so
     * that startup — everything logged before preferences are readable — is
     * always recorded; [Y2Application] applies the stored value immediately
     * after the container is built.
     *
     * The resulting mode is written at [warn] level, which is never gated, so
     * that it appears even in a quiet log. The first call always records, even
     * when it agrees with the default, so every process states its mode once.
     */
    @Synchronized
    fun setVerbose(value: Boolean) {
        val announce = !verboseAnnounced || verbose != value
        verbose = value
        verboseAnnounced = true
        if (!announce) return
        enqueue(
            "W",
            "Diagnostics",
            if (value) {
                "verbose logging on: informational lines are being recorded"
            } else {
                "verbose logging off: informational lines are NOT recorded " +
                    "(warnings and errors still are; structured events keep INFO)"
            }
        )
    }

    fun info(category: String, message: String) {
        if (!verbose) return
        enqueue("I", category, message)
    }

    fun warn(category: String, message: String) = enqueue("W", category, message)
    fun error(category: String, message: String, error: Throwable? = null) {
        val detail = error?.let(::boundedStackTrace)
        enqueue("E", category, if (detail == null) message else "$message\n$detail")
    }

    /** Synchronous write for the uncaught-exception handler; drains pending lines first. */
    fun crash(category: String, message: String, error: Throwable?) {
        runCatching {
            val pending = ArrayList<Entry>()
            queue.drainTo(pending)
            val detail = error?.let(::boundedStackTrace)
            val lines = pending.filterIsInstance<Line>() +
                Line(System.currentTimeMillis(), "E", category, if (detail == null) message else "$message\n$detail")
            writeLines(lines, force = true)
            pending.forEach { if (it is Flush) it.latch.countDown() }
        }
    }

    /**
     * The most recent [limit] lines, read from the tail of the active file.
     *
     * Only the last [TAIL_BYTES] are touched. Reading the whole file to keep its
     * final few lines meant allocating a list of every line in up to 512 KB —
     * several thousand strings — on a device with a small heap, and this is
     * called on every diagnostics publish, including every USB cable event.
     */
    fun recentLines(limit: Int = RECENT_LINE_COUNT): List<String> {
        awaitFlush(SHORT_FLUSH_TIMEOUT_MS)
        return synchronized(fileLock) {
            if (!activeFile.exists()) return@synchronized emptyList()
            runCatching { tailLines(activeFile, limit) }.getOrDefault(emptyList())
        }
    }

    private fun tailLines(file: File, limit: Int): List<String> {
        val length = file.length()
        if (length <= 0L) return emptyList()
        val from = (length - TAIL_BYTES).coerceAtLeast(0L)
        val buffer = ByteArray((length - from).toInt())
        RandomAccessFile(file, "r").use { input ->
            input.seek(from)
            input.readFully(buffer)
        }
        val lines = String(buffer, Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }
        // Starting mid-file almost certainly lands mid-line; that fragment would
        // otherwise be shown as a truncated entry.
        val whole = if (from > 0L && lines.size > 1) lines.drop(1) else lines
        return whole.filter { it.isNotBlank() }.takeLast(limit)
    }

    fun exportTo(destinationDirectory: File): File {
        awaitFlush(EXPORT_FLUSH_TIMEOUT_MS)
        synchronized(fileLock) {
            destinationDirectory.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val output = File(destinationDirectory, "y2-diagnostics-$stamp.txt")
            output.bufferedWriter().use { writer ->
                writer.appendLine("Y2 Player diagnostics")
                writer.appendLine("Generated: ${dateFormat.format(Date())}")
                writer.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                writer.appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                writer.appendLine("Build: ${Build.DISPLAY}")
                writer.appendLine()
                logFilesOldestFirst().forEach { file ->
                    writer.appendLine("===== ${file.name} =====")
                    runCatching { file.forEachLine { writer.appendLine(it) } }
                        .onFailure { writer.appendLine("Unable to read ${file.name}: ${it.javaClass.simpleName}") }
                    writer.appendLine()
                }
            }
            return output
        }
    }

    private fun enqueue(level: String, category: String, message: String) {
        val entry = Line(System.currentTimeMillis(), level, category, message)
        // Drop-oldest under overflow; never block the caller.
        while (!queue.offer(entry)) queue.poll()
        writer.wake()
    }

    private fun awaitFlush(timeoutMs: Long) {
        val latch = CountDownLatch(1)
        if (queue.offer(Flush(latch))) {
            writer.wake()
            runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        }
    }

    /**
     * A warning, an error or a pending flush must not sit in the coalescing
     * window. Scanning the queue is bounded by [QUEUE_CAPACITY] and only happens
     * once per wake, on the writer thread.
     */
    override fun hasUrgentPending(): Boolean = queue.any {
        it is Flush || (it is Line && it.level != "I")
    }

    override fun drainAndWrite() {
        val batch = ArrayList<Entry>(DRAIN_BATCH)
        // Drain in DRAIN_BATCH-sized passes so one wake clears a full queue
        // without holding an unbounded list.
        while (true) {
            batch.clear()
            queue.drainTo(batch, DRAIN_BATCH)
            if (batch.isEmpty()) return
            val lines = batch.filterIsInstance<Line>()
            if (lines.isNotEmpty()) writeLines(lines)
            batch.forEach { if (it is Flush) it.latch.countDown() }
        }
    }

    private fun writeLines(lines: List<Line>, force: Boolean = false) {
        if (writerDisabled && !force) return
        val result = runCatching {
            synchronized(fileLock) {
                rotateIfNeeded()
                FileOutputStream(activeFile, true).bufferedWriter().use { writer ->
                    lines.forEach { line ->
                        val stamp = dateFormat.format(Date(line.atMs))
                        val safe = line.message.replace("\r\n", "\n").replace('\r', '\n')
                        safe.lineSequence().forEachIndexed { index, text ->
                            writer.append(stamp)
                            writer.append(" ")
                            writer.append(line.level)
                            writer.append("/")
                            writer.append(line.category)
                            writer.append(if (index == 0) ": " else ": | ")
                            writer.appendLine(text)
                        }
                    }
                }
            }
        }
        if (result.isSuccess) {
            consecutiveWriteFailures = 0
        } else if (!force) {
            consecutiveWriteFailures += 1
            // Do not retry broken internal storage on every future batch.
            // Structured EventLog is independent and remains available.
            if (consecutiveWriteFailures >= MAX_WRITE_FAILURES) writerDisabled = true
        }
    }

    private fun boundedStackTrace(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        val full = writer.toString()
        return if (full.length <= MAX_STACK_CHARS) full.trimEnd()
        else full.take(MAX_STACK_CHARS).trimEnd() + "\n… stack trace truncated"
    }

    private fun logFilesOldestFirst(): List<File> = buildList {
        for (index in BACKUP_COUNT downTo 1) {
            File(directory, "y2player.$index.log").takeIf(File::exists)?.let(::add)
        }
        activeFile.takeIf(File::exists)?.let(::add)
    }

    private fun rotateIfNeeded() {
        if (!activeFile.exists() || activeFile.length() < MAX_BYTES) return
        for (index in BACKUP_COUNT downTo 1) {
            val source = if (index == 1) activeFile else File(directory, "y2player.${index - 1}.log")
            val destination = File(directory, "y2player.$index.log")
            if (source.exists()) {
                if (destination.exists()) destination.delete()
                source.renameTo(destination)
            }
        }
    }

    companion object {
        /**
         * How many recent lines the Diagnostics screen receives and shows. One
         * constant so the fetch and the display cannot drift: the screen used to
         * discard 68 of the 80 lines it was handed.
         */
        const val RECENT_LINE_COUNT = 30

        /** Enough tail to contain [RECENT_LINE_COUNT] lines several times over. */
        private const val TAIL_BYTES = 64L * 1024L
        private const val MAX_BYTES = 512L * 1024L
        private const val BACKUP_COUNT = 3
        private const val MAX_STACK_CHARS = 12_000
        private const val QUEUE_CAPACITY = 256
        private const val DRAIN_BATCH = 64
        private const val SHORT_FLUSH_TIMEOUT_MS = 100L
        private const val EXPORT_FLUSH_TIMEOUT_MS = 1_000L
        private const val MAX_WRITE_FAILURES = 3
    }
}
