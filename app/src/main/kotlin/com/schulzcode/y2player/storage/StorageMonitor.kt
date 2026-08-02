package com.schulzcode.y2player.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import com.schulzcode.y2player.core.state.DeviceState
import com.schulzcode.y2player.core.state.StorageVolumeState
import com.schulzcode.y2player.diagnostics.Ev
import com.schulzcode.y2player.diagnostics.EventLog
import com.schulzcode.y2player.diagnostics.Sub
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class StorageMonitor(
    context: Context,
    private val eventLog: EventLog? = null
) {
    fun interface Listener { fun onStorageChanged(state: DeviceState) }

    fun interface ContentListener { fun onExternalContentChanged(reason: String) }

    private val appContext = context.applicationContext
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val contentListeners = CopyOnWriteArraySet<ContentListener>()
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "y2-storage").apply { isDaemon = true }
    }
    private val refreshQueued = AtomicBoolean(false)
    private val deferredPublish = Runnable(::publish)
    @Volatile private var latest = DeviceState()
    @Volatile private var contentReason = REASON_TRANSFER

    private val deferredContentChange = Runnable {
        val reason = contentReason
        contentListeners.forEach { it.onExternalContentChanged(reason) }
    }

    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Y2StoragePaths.invalidateMountCaches()
            eventLog?.info(
                Sub.STORAGE, Ev.STORAGE_BROADCAST,
                "action" to intent?.action,
                "data" to intent?.dataString
            )
            handler.removeCallbacks(deferredPublish)
            handler.postDelayed(deferredPublish, STORAGE_DEBOUNCE_MS)
        }
    }

    private val contentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == ACTION_USB_STATE) {
                if (intent.getBooleanExtra(EXTRA_USB_CONNECTED, false)) return
                contentReason = REASON_USB
            } else {
                contentReason = REASON_TRANSFER
            }
            handler.removeCallbacks(deferredContentChange)
            handler.postDelayed(deferredContentChange, CONTENT_DEBOUNCE_MS)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateBattery(intent)
    }

    fun addListener(listener: Listener, emitImmediately: Boolean = true) {
        listeners += listener
        if (emitImmediately) {
            val value = latest
            if (value.storageVolumes.isNotEmpty()) listener.onStorageChanged(value) else publish()
        }
    }

    fun removeListener(listener: Listener) { listeners -= listener }

    fun addContentListener(listener: ContentListener) { contentListeners += listener }

    fun removeContentListener(listener: ContentListener) { contentListeners -= listener }

    fun start() {
        if (!registered) {
            val storageFilter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addAction(Intent.ACTION_MEDIA_SHARED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTABLE)
                addDataScheme("file")
            }
            appContext.registerReceiver(storageReceiver, storageFilter)
            appContext.registerReceiver(
                contentReceiver,
                IntentFilter(Intent.ACTION_MEDIA_SCANNER_FINISHED).apply { addDataScheme("file") }
            )
            appContext.registerReceiver(contentReceiver, IntentFilter(ACTION_USB_STATE))
            appContext.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registered = true
        }
        publish()
    }

    fun stop() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(storageReceiver) }
        runCatching { appContext.unregisterReceiver(contentReceiver) }
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        handler.removeCallbacks(deferredPublish)
        handler.removeCallbacks(deferredContentChange)
        registered = false
    }

    fun publish() {
        if (!refreshQueued.compareAndSet(false, true)) return
        executor.execute {
            refreshQueued.set(false)
            val previous = latest
            val value = runCatching { snapshot() }.getOrElse { latest }
            latest = value
            logVolumeChanges(previous, value)
            handler.post { listeners.forEach { it.onStorageChanged(value) } }
        }
    }

    private fun logVolumeChanges(previous: DeviceState, current: DeviceState) {
        val log = eventLog ?: return
        current.storageVolumes.forEach { volume ->
            val before = previous.storageVolumes.firstOrNull { it.id == volume.id }
            if (before != null && before.available == volume.available) return@forEach
            log.info(
                Sub.STORAGE, Ev.STORAGE_VOLUME_CHANGE,
                "volume" to volume.id,
                "available" to volume.available,
                "path" to volume.path,
                "freeBytes" to volume.freeBytes,
                "totalBytes" to volume.totalBytes,
                "first" to (before == null)
            )
        }
    }

    private fun updateBattery(intent: Intent?) {
        val current = latest
        if (current.storageVolumes.isEmpty()) {
            publish()
            return
        }
        val reading = batteryReading(intent)
        if (current.batteryPercent == reading.percent && current.charging == reading.charging) return
        val value = current.copy(batteryPercent = reading.percent, charging = reading.charging)
        latest = value
        handler.post { listeners.forEach { it.onStorageChanged(value) } }
    }

    private data class BatteryReading(val percent: Int?, val charging: Boolean)

    private fun batteryReading(intent: Intent?): BatteryReading {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return BatteryReading(
            percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        )
    }

    fun snapshot(): DeviceState {
        val roots = Y2StoragePaths.roots
        val internal = roots.first { it.id == "internal" }
        val removable = roots.first { it.id == "sdcard" }
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val reading = batteryReading(battery)

        val volumes = listOf(
            volumeState(internal, "Internal Storage"),
            volumeState(removable, "SD Card")
        )
        return DeviceState(
            internalStorageAvailable = volumes.first { it.id == "internal" }.available,
            removableStorageAvailable = volumes.first { it.id == "sdcard" }.available,
            storageVolumes = volumes,
            batteryPercent = reading.percent,
            charging = reading.charging,
            deviceModel = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Innioasis Y2" },
            androidVersion = "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
            firmwareBuild = Build.DISPLAY.orEmpty().ifBlank { Build.ID.orEmpty().ifBlank { "Unknown" } },
            uptimeMs = SystemClock.elapsedRealtime()
        )
    }

    private fun volumeState(root: StorageRoot, label: String): StorageVolumeState {
        val id = root.id
        val directory = root.directory
        val readable = Y2StoragePaths.isAvailable(root)
        val stats = if (readable) runCatching {
            val statFs = StatFs(directory.absolutePath)
            val blockSize = statFs.blockSizeLong
            val total = statFs.blockCountLong * blockSize
            val free = statFs.availableBlocksLong * blockSize
            total to free
        }.getOrNull() else null
        return StorageVolumeState(
            id = id,
            label = label,
            path = directory.absolutePath,
            available = readable,
            totalBytes = stats?.first ?: 0,
            freeBytes = stats?.second ?: 0
        )
    }

    companion object {
        private const val STORAGE_DEBOUNCE_MS = 600L
        private const val CONTENT_DEBOUNCE_MS = 2_500L
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        // @hide on API 19; the string values are stable.
        private const val EXTRA_USB_CONNECTED = "connected"
        private const val REASON_USB = "USB disconnected"
        private const val REASON_TRANSFER = "media scanner finished"
    }
}
