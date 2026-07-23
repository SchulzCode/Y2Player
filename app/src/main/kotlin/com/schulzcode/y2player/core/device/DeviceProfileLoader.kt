package com.schulzcode.y2player.core.device

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.WindowManager
import com.schulzcode.y2player.storage.Y2StoragePaths
import java.io.File

/**
 * Performs the one-time I/O behind [DeviceProfile].
 *
 * Called once per process, off the main thread (see Y2Application). The work is
 * one small sysfs read plus a handful of Build/WindowManager lookups, but it is
 * still kept off the UI thread on principle: this device has slow storage and
 * startup latency is user-visible.
 */
object DeviceProfileLoader {

    fun load(context: Context): DeviceProfile {
        val appContext = context.applicationContext

        val metrics = readDisplayMetrics(appContext)
        val rawVirtualSize = readVirtualSize()
        val panel = resolvePanel(rawVirtualSize, metrics)

        val hardware = Build.HARDWARE.orEmpty()
        val model = Build.MODEL.orEmpty()
        val (family, confidence) = DeviceProfiles.classify(hardware, model, panel)

        return DeviceProfile(
            panel = panel,
            sysfsVirtualSize = rawVirtualSize,
            displayWidth = metrics?.widthPixels ?: 0,
            displayHeight = metrics?.heightPixels ?: 0,
            densityDpi = metrics?.densityDpi ?: 0,
            apiLevel = Build.VERSION.SDK_INT,
            model = model,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            hardware = hardware,
            family = family,
            confidence = confidence,
            hasVibrator = hasVibrator(appContext),
            internalStorageAvailable = volumeAvailable("internal"),
            removableStorageAvailable = volumeAvailable("sdcard")
        )
    }

    // defaultDisplay/getMetrics are deprecated in modern SDKs; they are the only
    // display query that exists on API 19.
    @Suppress("DEPRECATION")
    private fun readDisplayMetrics(context: Context): DisplayMetrics? = runCatching {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
    }.getOrNull()

    /** Reads the framebuffer geometry node; null when absent or unreadable. */
    private fun readVirtualSize(): String? = runCatching {
        val file = File(DeviceProfiles.SYSFS_VIRTUAL_SIZE)
        if (!file.isFile || !file.canRead()) return@runCatching null
        // The node is a few bytes; bound the read anyway so a pathological
        // sysfs entry cannot allocate an unbounded string.
        file.inputStream().use { stream ->
            val buffer = ByteArray(64)
            val count = stream.read(buffer)
            if (count <= 0) null else String(buffer, 0, count).trim()
        }
    }.getOrNull()

    /**
     * DisplayMetrics is authoritative for layout; the sysfs value is used for
     * identification and is de-buffered against the metrics height.
     */
    private fun resolvePanel(rawVirtualSize: String?, metrics: DisplayMetrics?): PanelGeometry {
        val parsed = DeviceProfiles.parseVirtualSize(rawVirtualSize)
        if (parsed != null) {
            val (width, virtualHeight) = parsed
            val height = DeviceProfiles.resolveVisibleHeight(
                width = width,
                virtualHeight = virtualHeight,
                hintHeight = metrics?.heightPixels ?: 0
            )
            return PanelGeometry(width, height, PanelSource.SYSFS)
        }
        if (metrics != null && metrics.widthPixels > 0) {
            return PanelGeometry(metrics.widthPixels, metrics.heightPixels, PanelSource.DISPLAY_METRICS)
        }
        return PanelGeometry(0, 0, PanelSource.UNKNOWN)
    }

    // VIBRATOR_SERVICE is deprecated in favour of VibratorManager (API 31).
    @Suppress("DEPRECATION")
    private fun hasVibrator(context: Context): Boolean = runCatching {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return@runCatching false
        // hasVibrator() is API 11; safe on 19.
        vibrator.hasVibrator()
    }.getOrDefault(false)

    private fun volumeAvailable(id: String): Boolean = runCatching {
        Y2StoragePaths.roots.firstOrNull { it.id == id }?.let(Y2StoragePaths::isAvailable) ?: false
    }.getOrDefault(false)
}
