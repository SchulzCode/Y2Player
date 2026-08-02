package com.schulzcode.y2player.core.device

enum class PanelSource { SYSFS, DISPLAY_METRICS, UNKNOWN }

enum class DeviceFamily { Y1, Y2, UNKNOWN }

enum class DeviceConfidence { HIGH, LOW, NONE }

data class PanelGeometry(
    val width: Int,
    val height: Int,
    val source: PanelSource
) {
    val isLandscape: Boolean get() = width > height
    val isValid: Boolean get() = width > 0 && height > 0
    override fun toString(): String = "${width}x$height/${source.name.lowercase()}"
}

data class DeviceProfile(
    val panel: PanelGeometry,
    val sysfsVirtualSize: String?,
    val displayWidth: Int,
    val displayHeight: Int,
    val densityDpi: Int,
    val apiLevel: Int,
    val model: String,
    val manufacturer: String,
    val hardware: String,
    val family: DeviceFamily,
    val confidence: DeviceConfidence,
    val hasVibrator: Boolean,
    val internalStorageAvailable: Boolean,
    val removableStorageAvailable: Boolean
) {
    fun summary(): String =
        "$family/${confidence.name.lowercase()} panel=$panel display=${displayWidth}x$displayHeight " +
            "dpi=$densityDpi api=$apiLevel hw=$hardware model=$model vib=$hasVibrator"

    companion object {
        val UNRESOLVED = DeviceProfile(
            panel = PanelGeometry(0, 0, PanelSource.UNKNOWN),
            sysfsVirtualSize = null,
            displayWidth = 0,
            displayHeight = 0,
            densityDpi = 0,
            apiLevel = 0,
            model = "",
            manufacturer = "",
            hardware = "",
            family = DeviceFamily.UNKNOWN,
            confidence = DeviceConfidence.NONE,
            hasVibrator = false,
            internalStorageAvailable = false,
            removableStorageAvailable = false
        )
    }
}

object DeviceProfiles {
    const val SYSFS_VIRTUAL_SIZE = "/sys/class/graphics/fb0/virtual_size"

    private const val MIN_DIMENSION = 64
    private const val MAX_DIMENSION = 8192

    fun parseVirtualSize(raw: String?): Pair<Int, Int>? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = text.split(',', ' ', '\t').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val width = parts[0].trim().toIntOrNull() ?: return null
        val height = parts[1].trim().toIntOrNull() ?: return null
        if (width !in MIN_DIMENSION..MAX_DIMENSION) return null
        if (height !in MIN_DIMENSION..MAX_DIMENSION) return null
        return width to height
    }

    fun resolveVisibleHeight(width: Int, virtualHeight: Int, hintHeight: Int = 0): Int {
        if (width <= 0 || virtualHeight <= 0) return virtualHeight
        val candidates = (1..4)
            .filter { virtualHeight % it == 0 }
            .map { virtualHeight / it }
            .filter { it >= MIN_DIMENSION }
        if (candidates.isEmpty()) return virtualHeight
        if (hintHeight > 0) return candidates.minByOrNull { kotlin.math.abs(it - hintHeight) } ?: virtualHeight
        return candidates.firstOrNull { it <= width } ?: candidates.max()
    }

    fun classify(hardware: String, model: String, panel: PanelGeometry): Pair<DeviceFamily, DeviceConfidence> {
        val hw = hardware.lowercase()
        val mdl = model.lowercase()
        val knownPanel = panel.isValid && panel.width == 480 && panel.height == 360

        // MT6582 is the Y2, MT6572 the Y1. The model string is not reliable on either.
        if (hw.contains("6582")) return DeviceFamily.Y2 to if (knownPanel) DeviceConfidence.HIGH else DeviceConfidence.LOW
        if (hw.contains("6572")) return DeviceFamily.Y1 to if (knownPanel) DeviceConfidence.HIGH else DeviceConfidence.LOW

        if (mdl == "y2" || mdl.contains("y2")) return DeviceFamily.Y2 to DeviceConfidence.LOW
        if (mdl == "y1" || mdl.contains("y1")) return DeviceFamily.Y1 to DeviceConfidence.LOW

        return DeviceFamily.UNKNOWN to DeviceConfidence.NONE
    }
}
