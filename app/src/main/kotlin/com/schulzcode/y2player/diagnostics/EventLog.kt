package com.schulzcode.y2player.diagnostics

import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured NDJSON event log for hardware bring-up and field diagnosis.
 *
 * Runs beside the human-readable [DiagnosticLogger]; neither replaces the other.
 * Design constraints, all of which are load-bearing on this device:
 *
 * - **Callers never block and never serialize.** [log] allocates one small event
 *   and offers it to a bounded queue; JSON is built on the writer thread. That
 *   keeps it legal to call from the playback thread and from input handling.
 * - **Bounded with visible loss.** Overflow drops the oldest entry and counts it;
 *   the next serialized event carries a `dropped` count field so a gap is never silent.
 * - **Idle costs nothing.** The writer blocks on `take()`; no timer, no polling,
 *   no wakeups when the device is doing nothing.
 * - **Batched.** After the first event the writer drains for up to
 *   [BATCH_WINDOW_MS] or [BATCH_MAX] events, so a burst becomes one write.
 *   Warnings and errors bypass the window and flush immediately.
 * - **Survives restart and reboot** by construction (plain files), and survives a
 *   crash because the uncaught handler calls [crashFlush].
 *
 * ## Destinations
 * The **primary** destination is app-internal storage, which is always mounted
 * and always writable. The **mirror** is the removable card, written
 * best-effort so logs can be read by pulling the card out of a device that will
 * not boot.
 *
 * The card is deliberately *not* the primary destination, and that ordering is
 * load-bearing rather than stylistic: stock USB mass storage unmounts the
 * card, so a card-primary log goes blind during a storage transition.
 * Mirroring is skipped silently while the card is away and resumes when
 * it returns; the gap is recorded once, not per event.
 *
 * One line per event:
 * `{"t":…,"up":…,"sess":"…","seq":N,"sev":"info","sub":"playback","ev":"track_start","d":{…},"st":{…}}`
 */
class EventLog(
    private val primaryDirectory: File,
    private val mirrorProvider: () -> File? = { null },
    private val appVersion: String,
    private val buildId: String = "unknown",
    private val sessionId: String = generateSessionId()
) {
    /** Supplies the compact state snapshot attached to every event. */
    fun interface StateProvider { fun snapshot(): Map<String, Any?> }

    /** Supplies the one-line device summary; set once the profile has loaded. */
    fun interface DeviceProvider { fun summary(): String? }

    private class Entry(
        val wallMs: Long,
        val upMs: Long,
        val threadId: Long,
        val seq: Long,
        val sev: Sev,
        val sub: Sub,
        val ev: Ev,
        val data: Array<out Pair<String, Any?>>,
        val state: Map<String, Any?>?
    )

    private class Flush(val latch: CountDownLatch)

    private val processId: Int = android.os.Process.myPid()
    private val queue = ArrayBlockingQueue<Any>(QUEUE_CAPACITY)
    private val sequence = AtomicLong(0)
    private val dropped = AtomicLong(0)
    @Volatile private var stateProvider: StateProvider? = null
    @Volatile private var deviceProvider: DeviceProvider? = null
    @Volatile private var enabled = true

    /** Writer-thread state; touched nowhere else (except [crashFlush], see there). */
    private var activeDirectory: File? = null
    private var ioFailures = 0
    private var primaryDisabled = false
    private val builder = StringBuilder(512)

    /**
     * Writer-thread mirror state.
     *
     * The mirror directory itself is deliberately not cached: the card can be
     * unmounted between any two batches, so it is re-resolved every time.
     */
    private var mirrorFailures = 0
    @Volatile private var mirrorAvailable = false

    /** Guarded by [logRateLimited]'s @Synchronized; consulted on caller threads. */
    private val rateLimiter = HashMap<String, Long>()

    @Suppress("unused")
    private val worker = Thread(::drainLoop, "y2-eventlog").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }

    fun setStateProvider(provider: StateProvider) { stateProvider = provider }
    fun setDeviceProvider(provider: DeviceProvider) { deviceProvider = provider }

    /**
     * Verbose diagnostics toggle. When off, only DEBUG events are suppressed.
     * INFO and above remain available for storage and playback diagnosis.
     */
    fun setEnabled(value: Boolean) { enabled = value }

    fun log(sev: Sev, sub: Sub, ev: Ev, vararg data: Pair<String, Any?>) {
        if (!enabled && sev == Sev.DEBUG) return
        // Thread.id, not the newer threadId(): the latter is a Java 19 addition
        // and does not exist on this API-19 device. The deprecation warning
        // targets desktop JVMs and does not apply here.
        @Suppress("DEPRECATION")
        val threadId = Thread.currentThread().id
        val entry = Entry(
            wallMs = System.currentTimeMillis(),
            upMs = SystemClock.elapsedRealtime(),
            threadId = threadId,
            seq = sequence.incrementAndGet(),
            sev = sev,
            sub = sub,
            ev = ev,
            data = data,
            state = runCatching { stateProvider?.snapshot() }.getOrNull()
        )
        if (!queue.offer(entry)) {
            // Drop the oldest so the newest (usually the most relevant) survives.
            queue.poll()
            dropped.incrementAndGet()
            queue.offer(entry)
        }
    }

    fun debug(sub: Sub, ev: Ev, vararg data: Pair<String, Any?>) = log(Sev.DEBUG, sub, ev, *data)
    fun info(sub: Sub, ev: Ev, vararg data: Pair<String, Any?>) = log(Sev.INFO, sub, ev, *data)
    fun warn(sub: Sub, ev: Ev, vararg data: Pair<String, Any?>) = log(Sev.WARN, sub, ev, *data)
    fun error(sub: Sub, ev: Ev, vararg data: Pair<String, Any?>) = log(Sev.ERROR, sub, ev, *data)

    /**
     * Records an event at most once per [windowMs] for the given key.
     *
     * Prevents a repeating failure (an unreadable file hit on every track, a
     * failing artwork decode) from flooding the card and burning write cycles.
     * The window map is consulted on the caller's thread, so this method is
     * synchronized; the plain [log] path never touches it.
     */
    @Synchronized
    fun logRateLimited(
        key: String,
        windowMs: Long,
        sev: Sev,
        sub: Sub,
        ev: Ev,
        vararg data: Pair<String, Any?>
    ) {
        val now = SystemClock.elapsedRealtime()
        val last = rateLimiter[key]
        if (last != null && now - last < windowMs) return
        rateLimiter[key] = now
        log(sev, sub, ev, *data)
    }

    /** Blocks briefly until queued events have been written (used before export). */
    fun flush(timeoutMs: Long = 1_000L) {
        val latch = CountDownLatch(1)
        if (queue.offer(Flush(latch))) {
            runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        }
    }

    /**
     * Synchronous write path for the uncaught-exception handler: the process is
     * about to die, so the queue is drained and written on the calling thread.
     *
     * Concurrency assumption: this touches writer-thread state ([writeBatch])
     * from the crashing thread while the writer may also be mid-batch. That race
     * is accepted — the process is terminating, both writers append whole lines
     * to an O_APPEND stream, and the alternative (waiting on the writer thread
     * from a crash handler) risks dying with the queue unwritten.
     */
    fun crashFlush(throwable: Throwable?) {
        runCatching {
            log(Sev.ERROR, Sub.APP, Ev.CRASH,
                "type" to (throwable?.javaClass?.name ?: "unknown"),
                "message" to (throwable?.message ?: ""))
            val pending = ArrayList<Any>()
            queue.drainTo(pending)
            writeBatch(pending.filterIsInstance<Entry>())
            pending.forEach { if (it is Flush) it.latch.countDown() }
        }
    }

    /** Log files, oldest first, for export. */
    fun logFiles(): List<File> {
        val directory = activeDirectory ?: resolveDirectory() ?: return emptyList()
        return (BACKUP_COUNT downTo 1)
            .map { File(directory, "events.$it.ndjson") }
            .filter(File::exists) + listOf(File(directory, ACTIVE_NAME)).filter(File::exists)
    }

    // ------------------------------------------------------------------ writer

    private fun drainLoop() {
        val batch = ArrayList<Any>(BATCH_MAX)
        while (true) {
            try {
                batch.clear()
                batch.add(queue.take())
                val deadline = SystemClock.elapsedRealtime() + BATCH_WINDOW_MS
                // Collect until the window closes, the batch is full, or an entry
                // that must not wait (warn/error/flush) appears.
                while (batch.size < BATCH_MAX && !mustFlushNow(batch)) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    val next = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: break
                    batch.add(next)
                }
                writeBatch(batch.filterIsInstance<Entry>())
                batch.forEach { if (it is Flush) it.latch.countDown() }
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                // Diagnostics must never become a second fault.
            }
        }
    }

    private fun mustFlushNow(batch: List<Any>): Boolean = batch.any {
        it is Flush || (it is Entry && (it.sev == Sev.WARN || it.sev == Sev.ERROR))
    }

    private fun writeBatch(entries: List<Entry>) {
        if (entries.isEmpty()) return

        // Serialize once; both destinations receive identical bytes.
        val payload = StringBuilder(entries.size * 160)
        entries.forEach { entry ->
            builder.setLength(0)
            serialize(entry, builder)
            payload.append(builder).append('\n')
        }
        val text = payload.toString()

        writePrimary(text)
        writeMirror(text)
    }

    private fun writePrimary(text: String) {
        if (primaryDisabled) return
        val directory = activeDirectory ?: resolveDirectory() ?: return
        activeDirectory = directory
        val target = File(directory, ACTIVE_NAME)
        try {
            rotateIfNeeded(target, text.length.toLong())
            appendText(target, text)
            ioFailures = 0
        } catch (_: IOException) {
            handleIoFailure()
        } catch (_: SecurityException) {
            handleIoFailure()
        }
    }

    /**
     * Best-effort copy to the removable card.
     *
     * The card comes and goes — it is unmounted for the whole duration of a USB
     * export — so its directory is re-resolved on every batch rather than held.
     * A failure here never touches [ioFailures]: losing the mirror must not
     * disturb the destination that actually matters. When the card returns,
     * mirroring simply resumes from the current batch; no backlog is replayed,
     * because a replay would be unbounded and the internal log already holds
     * the complete record.
     */
    private fun writeMirror(text: String) {
        val directory = runCatching { mirrorProvider() }.getOrNull()
        if (directory == null) {
            if (mirrorAvailable) {
                mirrorAvailable = false
                log(Sev.INFO, Sub.DIAG, Ev.LOG_MIRROR_STOPPED, "reason" to "unavailable")
            }
            return
        }
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            if (!mirrorAvailable) {
                mirrorAvailable = true
                mirrorFailures = 0
                log(Sev.INFO, Sub.DIAG, Ev.LOG_MIRROR_STARTED, "path" to directory.path)
            }
            val target = File(directory, ACTIVE_NAME)
            rotateIfNeeded(target, text.length.toLong())
            appendText(target, text)
            mirrorFailures = 0
        } catch (_: IOException) {
            noteMirrorFailure()
        } catch (_: SecurityException) {
            noteMirrorFailure()
        }
    }

    private fun noteMirrorFailure() {
        mirrorFailures += 1
        if (mirrorFailures >= MAX_IO_FAILURES && mirrorAvailable) {
            mirrorAvailable = false
            log(Sev.WARN, Sub.DIAG, Ev.LOG_MIRROR_STOPPED, "reason" to "write_failed")
        }
    }

    private fun appendText(target: File, text: String) {
        OutputStreamWriter(FileOutputStream(target, true), Charsets.UTF_8).buffered().use { writer ->
            writer.append(text)
        }
    }

    /**
     * On primary write failure, stop trying after a few attempts rather than
     * thrash the filesystem. There is no fallback below internal storage: if
     * `filesDir` is unwritable the process has larger problems, and the
     * human-readable log records the fault.
     */
    private fun handleIoFailure() {
        ioFailures += 1
        if (ioFailures >= MAX_IO_FAILURES) {
            activeDirectory = null
            primaryDisabled = true
        }
    }

    private fun resolveDirectory(): File? = runCatching {
        primaryDirectory.apply { mkdirs() }.takeIf { it.isDirectory }
    }.getOrNull()

    /**
     * Rotates before a write that would push the active file over the limit.
     *
     * [incoming] is the size of the batch about to be appended. Using the projected
     * size keeps every file at or below [MAX_FILE_BYTES]. A batch is far smaller
     * than the limit, so this does not rotate a nearly empty file.
     */
    private fun rotateIfNeeded(active: File, incoming: Long) {
        if (!active.exists()) return
        val projected = active.length() + incoming
        if (projected < MAX_FILE_BYTES) return
        val directory = active.parentFile ?: return
        // Drop the oldest, then shift each backup up one slot.
        File(directory, "events.$BACKUP_COUNT.ndjson").takeIf(File::exists)?.delete()
        for (index in BACKUP_COUNT - 1 downTo 1) {
            val source = File(directory, "events.$index.ndjson")
            if (source.exists()) source.renameTo(File(directory, "events.${index + 1}.ndjson"))
        }
        active.renameTo(File(directory, "events.1.ndjson"))
    }

    private fun serialize(entry: Entry, out: StringBuilder) {
        out.append("{\"t\":").append(entry.wallMs)
        out.append(",\"up\":").append(entry.upMs)
        out.append(",\"sess\":")
        EventJson.escape(sessionId, out)
        out.append(",\"seq\":").append(entry.seq)
        out.append(",\"sev\":")
        EventJson.escape(entry.sev.code, out)
        out.append(",\"sub\":")
        EventJson.escape(entry.sub.code, out)
        out.append(",\"ev\":")
        EventJson.escape(entry.ev.code, out)
        out.append(",\"v\":")
        EventJson.escape(appVersion, out)
        out.append(",\"build\":")
        EventJson.escape(buildId, out)
        out.append(",\"pid\":").append(processId)
        out.append(",\"tid\":").append(entry.threadId)
        deviceProvider?.summary()?.let {
            out.append(",\"dev\":")
            EventJson.escape(it, out)
        }
        if (entry.data.isNotEmpty()) {
            out.append(",\"d\":{")
            entry.data.forEachIndexed { index, (key, value) ->
                if (index > 0) out.append(',')
                EventJson.escape(key, out)
                out.append(':')
                EventJson.appendValue(value, out)
            }
            out.append('}')
        }
        entry.state?.takeIf { it.isNotEmpty() }?.let { state ->
            out.append(",\"st\":{")
            var first = true
            state.forEach { (key, value) ->
                if (!first) out.append(',')
                first = false
                EventJson.escape(key, out)
                out.append(':')
                EventJson.appendValue(value, out)
            }
            out.append('}')
        }
        val lost = dropped.getAndSet(0)
        if (lost > 0) out.append(",\"dropped\":").append(lost)
        out.append('}')
    }

    companion object {
        const val ACTIVE_NAME = "events.ndjson"
        const val QUEUE_CAPACITY = 512
        const val BATCH_MAX = 64
        const val BATCH_WINDOW_MS = 5_000L
        const val MAX_FILE_BYTES = 512L * 1024L
        const val BACKUP_COUNT = 4
        private const val MAX_IO_FAILURES = 3

        fun generateSessionId(): String =
            java.lang.Long.toHexString(System.currentTimeMillis() xor (Math.random() * Long.MAX_VALUE).toLong())
                .takeLast(6)
    }
}
