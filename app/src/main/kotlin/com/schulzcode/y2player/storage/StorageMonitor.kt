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
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns every storage- and power-related system broadcast.
 *
 * Three distinct signals, deliberately kept apart because they have very
 * different costs and frequencies:
 *  - **mount events** (rare, expensive): full [snapshot] including StatFs.
 *  - **content events** (rare, no snapshot): a file transfer finished, so the
 *    library may be stale — see [ContentListener].
 *  - **battery events** (frequent, must be nearly free): updated straight from
 *    the broadcast intent, with no filesystem access and no listener dispatch
 *    unless the user-visible value actually changed.
 */
class StorageMonitor(context: Context) {
    fun interface Listener { fun onStorageChanged(state: DeviceState) }

    /**
     * Raised when external storage *content* may have changed without a mount
     * transition — the classic case being an MTP transfer from a PC, which
     * never unmounts anything. Consumers trigger an incremental rescan.
     */
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
    // Written by the snapshot executor and by updateBattery on the main thread.
    // An interleaving can drop one battery delta; the next ACTION_BATTERY_CHANGED
    // (frequent) repairs it, so no synchronization is added for it.
    @Volatile private var latest = DeviceState()
    @Volatile private var contentReason = REASON_TRANSFER

    private val deferredContentChange = Runnable {
        val reason = contentReason
        contentListeners.forEach { it.onExternalContentChanged(reason) }
    }

    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handler.removeCallbacks(deferredPublish)
            handler.postDelayed(deferredPublish, STORAGE_DEBOUNCE_MS)
        }
    }

    /**
     * MTP writes files into mounted storage without any mount transition, so the
     * only reliable hints that new music arrived are the platform media scanner
     * finishing and the USB cable being unplugged. Both are debounced into one
     * notification; the library scan they trigger is incremental, so a false
     * positive costs a directory walk, not a metadata re-read.
     */
    private val contentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == ACTION_USB_STATE) {
                // Only a completed (disconnected) transfer is interesting.
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
            // MEDIA_SCANNER_FINISHED carries a file:// data URI; USB_STATE does not,
            // so they cannot share one filter.
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

    /**
     * Requests a full device snapshot. Concurrent requests are **coalesced, not
     * dropped**: the queued flag is cleared before the snapshot runs, so a
     * mount event arriving mid-refresh always schedules a fresh pass.
     */
    fun publish() {
        if (!refreshQueued.compareAndSet(false, true)) return
        executor.execute {
            refreshQueued.set(false)
            val value = runCatching { snapshot() }.getOrElse { latest }
            latest = value
            handler.post { listeners.forEach { it.onStorageChanged(value) } }
        }
    }

    /**
     * Battery fast path: reads the values out of the broadcast itself (no sticky
     * re-read, no StatFs) and dispatches only when a user-visible value changed.
     * ACTION_BATTERY_CHANGED also fires for voltage and temperature, which the
     * UI never shows; dispatching on those would cost two StatFs calls and a
     * full-screen repaint every few seconds, so they are ignored here.
     */
    private fun updateBattery(intent: Intent?) {
        val current = latest
        if (current.storageVolumes.isEmpty()) {
            publish()
            return
        }
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else null
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (current.batteryPercent == percent && current.charging == charging) return
        val value = current.copy(batteryPercent = percent, charging = charging)
        latest = value
        handler.post { listeners.forEach { it.onStorageChanged(value) } }
    }

    fun snapshot(): DeviceState {
        val internal = Y2StoragePaths.roots.first { it.id == "internal" }
        val removable = Y2StoragePaths.roots.first { it.id == "sdcard" }
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else null
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val volumes = listOf(
            volumeState(internal.id, "Internal Storage", internal.directory),
            volumeState(removable.id, "SD Card", removable.directory)
        )
        return DeviceState(
            internalStorageAvailable = volumes.first { it.id == "internal" }.available,
            removableStorageAvailable = volumes.first { it.id == "sdcard" }.available,
            storageVolumes = volumes,
            batteryPercent = percent,
            charging = charging,
            deviceModel = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Innioasis Y2" },
            androidVersion = "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
            firmwareBuild = Build.DISPLAY.orEmpty().ifBlank { Build.ID.orEmpty().ifBlank { "Unknown" } },
            uptimeMs = SystemClock.elapsedRealtime()
        )
    }

    private fun volumeState(id: String, label: String, directory: File): StorageVolumeState {
        val root = Y2StoragePaths.roots.firstOrNull { it.id == id } ?: StorageRoot(id, directory)
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
        // Long enough to absorb a burst of scanner-finished broadcasts and to let
        // the filesystem settle after an unplug, short enough to feel automatic.
        private const val CONTENT_DEBOUNCE_MS = 2_500L
        // Framework constants that are @hide on API 19; the string values are stable.
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        private const val EXTRA_USB_CONNECTED = "connected"
        private const val REASON_USB = "USB disconnected"
        private const val REASON_TRANSFER = "media scanner finished"
    }
}
