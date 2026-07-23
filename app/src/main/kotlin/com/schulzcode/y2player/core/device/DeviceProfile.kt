package com.schulzcode.y2player.core.device

/**
 * Measured hardware facts, resolved once per process.
 *
 * Motivation: `ro.product.model` lies. The Solar firmware analysis found stock
 * A5 units reporting `model=Y1`, and the Y1 and Y2 share a 480x360 panel — so
 * neither the model string nor the panel geometry alone identifies a device.
 * This layer therefore reports *measurements* and attaches an explicit
 * confidence to any classification, so diagnostics can be trusted and behaviour
 * never silently changes on a guess.
 *
 * All parsing and classification here is pure and host-JVM testable; the I/O
 * lives in [DeviceProfileLoader].
 */

/** Where the panel geometry came from, so diagnostics can weigh it. */
enum class PanelSource { SYSFS, DISPLAY_METRICS, UNKNOWN }

enum class DeviceFamily { Y1, Y2, UNKNOWN }

/**
 * How much the [DeviceFamily] can be trusted.
 * - [HIGH]: SoC and panel agree (MT6582 + 480x360 = Y2).
 * - [LOW]: only the (unreliable) model string suggested it.
 * - [NONE]: unknown; callers must fall back to conservative, device-agnostic behaviour.
 */
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
    /** Raw `virtual_size` contents, kept verbatim for diagnostics. */
    val sysfsVirtualSize: String?,
    /** Authoritative for UI layout — never overridden by the sysfs reading. */
    val displayWidth: Int,
    val displayHeight: Int,
    val densityDpi: Int,
    val apiLevel: Int,
    val model: String,
    val manufacturer: String,
    /** `Build.HARDWARE`, e.g. "mt6582". Public API, no reflection, no I/O. */
    val hardware: String,
    val family: DeviceFamily,
    val confidence: DeviceConfidence,
    val hasVibrator: Boolean,
    val internalStorageAvailable: Boolean,
    val removableStorageAvailable: Boolean
) {
    /** Compact one-line form embedded in every structured log event. */
    fun summary(): String =
        "$family/${confidence.name.lowercase()} panel=$panel display=${displayWidth}x$displayHeight " +
            "dpi=$densityDpi api=$apiLevel hw=$hardware model=$model vib=$hasVibrator"

    companion object {
        /** Used before the real profile is loaded; behaves as "unknown device". */
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

/** Pure parsing and classification. No Android imports — fully unit testable. */
object DeviceProfiles {

    const val SYSFS_VIRTUAL_SIZE = "/sys/class/graphics/fb0/virtual_size"

    /** Panels far outside this range are treated as unreadable rather than trusted. */
    private const val MIN_DIMENSION = 64
    private const val MAX_DIMENSION = 8192

    /**
     * Parses the `virtual_size` node, whose contents are "width,height".
     *
     * Returns null for missing, blank, malformed or implausible values rather
     * than throwing — an unreadable panel node must never break startup.
     */
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

    /**
     * The framebuffer's virtual height is the visible height multiplied by the
     * number of buffers (double/triple buffering), so a 480x360 panel commonly
     * reports "480,720" or "480,1080".
     *
     * [hintHeight] is the height reported by DisplayMetrics when available; the
     * divisor whose result is closest to it wins. Without a hint, the divisor
     * producing the most plausible small-panel aspect ratio is chosen.
     */
    fun resolveVisibleHeight(width: Int, virtualHeight: Int, hintHeight: Int = 0): Int {
        if (width <= 0 || virtualHeight <= 0) return virtualHeight
        val candidates = (1..4)
            .filter { virtualHeight % it == 0 }
            .map { virtualHeight / it }
            .filter { it >= MIN_DIMENSION }
        if (candidates.isEmpty()) return virtualHeight
        if (hintHeight > 0) return candidates.minByOrNull { kotlin.math.abs(it - hintHeight) } ?: virtualHeight
        // No hint: prefer the tallest candidate that still yields a landscape
        // panel, which is the shape both known devices use.
        return candidates.firstOrNull { it <= width } ?: candidates.max()
    }

    /**
     * Classifies the device from the SoC and panel together.
     *
     * Deliberately conservative:
     * - 480x360 alone never decides, because Y1 and Y2 share it.
     * - The model string alone yields at most [DeviceConfidence.LOW].
     * - Anything unrecognised stays [DeviceFamily.UNKNOWN], and callers must
     *   then fall back to conservative, device-agnostic behaviour.
     */
    fun classify(hardware: String, model: String, panel: PanelGeometry): Pair<DeviceFamily, DeviceConfidence> {
        val hw = hardware.lowercase()
        val mdl = model.lowercase()
        val knownPanel = panel.isValid && panel.width == 480 && panel.height == 360

        // SoC is the strongest available signal: MT6582 = Y2, MT6572 = Y1.
        if (hw.contains("6582")) return DeviceFamily.Y2 to if (knownPanel) DeviceConfidence.HIGH else DeviceConfidence.LOW
        if (hw.contains("6572")) return DeviceFamily.Y1 to if (knownPanel) DeviceConfidence.HIGH else DeviceConfidence.LOW

        // Fall back to the model string, which is known to be unreliable.
        if (mdl == "y2" || mdl.contains("y2")) return DeviceFamily.Y2 to DeviceConfidence.LOW
        if (mdl == "y1" || mdl.contains("y1")) return DeviceFamily.Y1 to DeviceConfidence.LOW

        return DeviceFamily.UNKNOWN to DeviceConfidence.NONE
    }
}
