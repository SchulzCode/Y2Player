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

class EventLog(
    private val primaryDirectory: File,
    private val mirrorProvider: () -> File? = { null },
    private val appVersion: String,
    private val buildId: String = "unknown",
    private val sessionId: String = generateSessionId(),
    private val writer: LogWriter = LogWriter("y2-eventlog")
) : LogWriter.Sink {
    fun interface StateProvider { fun snapshot(): Map<String, Any?> }

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

    private var activeDirectory: File? = null
    private var ioFailures = 0
    private var primaryDisabled = false
    private val builder = StringBuilder(512)

    private var mirrorFailures = 0
    @Volatile private var mirrorAvailable = false

    private val rateLimiter = HashMap<String, Long>()

    init {
        writer.register(this)
    }

    fun setStateProvider(provider: StateProvider) { stateProvider = provider }
    fun setDeviceProvider(provider: DeviceProvider) { deviceProvider = provider }

    fun setEnabled(value: Boolean) { enabled = value }

    fun log(sev: Sev, sub: Sub, ev: Ev, vararg data: Pair<String, Any?>) {
        if (!enabled && sev == Sev.DEBUG) return
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
            queue.poll()
            dropped.incrementAndGet()
            queue.offer(entry)
        }
        writer.wake()
    }

    fun debug(sub: Sub, ev: Ev, vararg data: Pair<String, Any?>) = log(Sev.DEBUG, sub, ev, *data)
    fun info(sub: Sub, ev: Ev, vararg data: Pair<String, Any?>) = log(Sev.INFO, sub, ev, *data)
    fun warn(sub: Sub, ev: Ev, vararg data: Pair<String, Any?>) = log(Sev.WARN, sub, ev, *data)
    fun error(sub: Sub, ev: Ev, vararg data: Pair<String, Any?>) = log(Sev.ERROR, sub, ev, *data)

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

    fun flush(timeoutMs: Long = 1_000L) {
        val latch = CountDownLatch(1)
        if (queue.offer(Flush(latch))) {
            writer.wake()
            runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        }
    }

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

    fun logFiles(): List<File> {
        val directory = activeDirectory ?: resolveDirectory() ?: return emptyList()
        return (BACKUP_COUNT downTo 1)
            .map { File(directory, "events.$it.ndjson") }
            .filter(File::exists) + listOf(File(directory, ACTIVE_NAME)).filter(File::exists)
    }

    override fun hasUrgentPending(): Boolean = queue.any {
        it is Flush || (it is Entry && (it.sev == Sev.WARN || it.sev == Sev.ERROR))
    }

    override fun drainAndWrite() {
        val batch = ArrayList<Any>(BATCH_MAX)
        while (true) {
            batch.clear()
            queue.drainTo(batch, BATCH_MAX)
            if (batch.isEmpty()) return
            writeBatch(batch.filterIsInstance<Entry>())
            batch.forEach { if (it is Flush) it.latch.countDown() }
        }
    }

    private fun writeBatch(entries: List<Entry>) {
        if (entries.isEmpty()) return

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

    private fun rotateIfNeeded(active: File, incoming: Long) {
        if (!active.exists()) return
        val projected = active.length() + incoming
        if (projected < MAX_FILE_BYTES) return
        val directory = active.parentFile ?: return
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
        const val MAX_FILE_BYTES = 512L * 1024L
        const val BACKUP_COUNT = 4
        private const val MAX_IO_FAILURES = 3

        fun generateSessionId(): String =
            java.lang.Long.toHexString(System.currentTimeMillis() xor (Math.random() * Long.MAX_VALUE).toLong())
                .takeLast(6)
    }
}
