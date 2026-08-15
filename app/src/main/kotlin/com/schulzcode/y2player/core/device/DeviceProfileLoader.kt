package com.schulzcode.y2player.core.device

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File

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
            hardware = hardware,
            family = family,
            confidence = confidence,
            hasVibrator = hasVibrator(appContext)
        )
    }

    @Suppress("DEPRECATION")
    private fun readDisplayMetrics(context: Context): DisplayMetrics? = runCatching {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
    }.getOrNull()

    private fun readVirtualSize(): String? = runCatching {
        val file = File(DeviceProfiles.SYSFS_VIRTUAL_SIZE)
        if (!file.isFile || !file.canRead()) return@runCatching null
        file.inputStream().use { stream ->
            val buffer = ByteArray(64)
            val count = stream.read(buffer)
            if (count <= 0) null else String(buffer, 0, count).trim()
        }
    }.getOrNull()

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

    @Suppress("DEPRECATION")
    private fun hasVibrator(context: Context): Boolean = runCatching {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return@runCatching false
        vibrator.hasVibrator()
    }.getOrDefault(false)
}
