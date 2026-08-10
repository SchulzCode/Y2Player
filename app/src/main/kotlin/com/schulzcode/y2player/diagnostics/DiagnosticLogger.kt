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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DiagnosticLogger internal constructor(
    private val directory: File,
    private val writer: LogWriter = LogWriter("y2-diagnostics")
) : LogWriter.Sink {
    private val activeFile = File(directory, "y2player.log")
    private val nativeCrashFile = File(directory, "y2-native-crash.log")
    private val fileLock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private sealed interface Entry
    private class Line(val atMs: Long, val level: String, val category: String, val message: String) : Entry
    private class Flush(val latch: CountDownLatch) : Entry
    private class Clear(val latch: CountDownLatch, val result: AtomicReference<Boolean>) : Entry

    private val queue = java.util.ArrayDeque<Entry>(QUEUE_CAPACITY)
    private val queueLock = Any()
    @Volatile private var writerDisabled = false
    @Volatile private var verbose = true
    private var verboseAnnounced = false
    private var consecutiveWriteFailures = 0

    constructor(context: Context, writer: LogWriter = LogWriter("y2-diagnostics")) : this(
        File(context.applicationContext.filesDir, "diagnostics").apply { mkdirs() },
        writer
    )

    init {
        writer.register(this)
    }

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

    fun crash(category: String, message: String, error: Throwable?) {
        runCatching {
            val pending = ArrayList<Entry>()
            synchronized(queueLock) {
                while (queue.isNotEmpty()) pending += queue.removeFirst()
            }
            val detail = error?.let(::boundedStackTrace)
            val lines = pending.filterIsInstance<Line>() +
                Line(System.currentTimeMillis(), "E", category, if (detail == null) message else "$message\n$detail")
            writeLines(lines, force = true)
            pending.forEach {
                when (it) {
                    is Flush -> it.latch.countDown()
                    is Clear -> {
                        it.result.set(false)
                        it.latch.countDown()
                    }
                    is Line -> Unit
                }
            }
        }
    }

    /**
     * Places an ordered reset barrier in the writer queue. Lines queued before the
     * barrier are discarded or deleted; lines queued after it are written to a new
     * active file. The exported diagnostics directory is deliberately unrelated.
     */
    fun clear(timeoutMs: Long = CLEAR_TIMEOUT_MS): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicReference(false)
        synchronized(queueLock) {
            // Pending lines have not reached disk and belong to the pre-reset log.
            val controls = ArrayList<Entry>()
            while (queue.isNotEmpty()) {
                when (val entry = queue.removeFirst()) {
                    is Line -> Unit
                    else -> controls += entry
                }
            }
            controls.forEach(queue::addLast)
            queue.addLast(Clear(latch, result))
        }
        writer.wake()
        return runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) && result.get() }
            .getOrDefault(false)
    }

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
                nativeCrashFile.takeIf(File::exists)?.let { file ->
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
        synchronized(queueLock) {
            if (queue.size >= QUEUE_CAPACITY) {
                val retained = ArrayList<Entry>(queue.size)
                var removedLine = false
                while (queue.isNotEmpty()) {
                    val pending = queue.removeFirst()
                    if (!removedLine && pending is Line) removedLine = true else retained += pending
                }
                retained.forEach(queue::addLast)
                if (!removedLine) return
            }
            queue.addLast(entry)
        }
        writer.wake()
    }

    private fun awaitFlush(timeoutMs: Long) {
        val latch = CountDownLatch(1)
        synchronized(queueLock) {
            queue.addLast(Flush(latch))
        }
        writer.wake()
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    override fun hasUrgentPending(): Boolean = synchronized(queueLock) {
        queue.any { it is Flush || it is Clear || (it is Line && it.level != "I") }
    }

    override fun drainAndWrite() {
        val batch = ArrayList<Entry>(DRAIN_BATCH)
        while (true) {
            batch.clear()
            synchronized(queueLock) {
                repeat(minOf(DRAIN_BATCH, queue.size)) { batch += queue.removeFirst() }
            }
            if (batch.isEmpty()) return
            val lines = ArrayList<Line>()
            fun writePending() {
                if (lines.isNotEmpty()) {
                    writeLines(lines)
                    lines.clear()
                }
            }
            batch.forEach { entry ->
                when (entry) {
                    is Line -> lines += entry
                    is Flush -> {
                        writePending()
                        entry.latch.countDown()
                    }
                    is Clear -> {
                        writePending()
                        entry.result.set(clearFiles())
                        entry.latch.countDown()
                    }
                }
            }
            writePending()
        }
    }

    private fun clearFiles(): Boolean = synchronized(fileLock) {
        var success = true
        val names = buildList {
            add(activeFile.name)
            add(nativeCrashFile.name)
            for (index in 1..BACKUP_COUNT) add("y2player.$index.log")
        }
        names.forEach { name ->
            val file = File(directory, name)
            if (file.exists() && !file.delete()) success = false
        }
        writerDisabled = false
        consecutiveWriteFailures = 0
        success
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
        const val RECENT_LINE_COUNT = 30

        private const val TAIL_BYTES = 64L * 1024L
        private const val MAX_BYTES = 512L * 1024L
        private const val BACKUP_COUNT = 3
        private const val MAX_STACK_CHARS = 12_000
        private const val QUEUE_CAPACITY = 256
        private const val DRAIN_BATCH = 64
        private const val SHORT_FLUSH_TIMEOUT_MS = 100L
        private const val EXPORT_FLUSH_TIMEOUT_MS = 1_000L
        private const val CLEAR_TIMEOUT_MS = 2_000L
        private const val MAX_WRITE_FAILURES = 3
    }
}
