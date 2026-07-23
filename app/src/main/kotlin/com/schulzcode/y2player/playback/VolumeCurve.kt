package com.schulzcode.y2player.playback

/**
 * Perceptual volume mapping for the in-app volume mode.
 *
 * The curve uses constant-decibel steps:
 *
 *     amplitude(level) = 10^(RANGE_DB · (1 - level/STEPS) / 20)
 *
 * Forty steps over -48 dB produce uniform 1.2 dB increments; level 0 is
 * special-cased to silence. The 41-float table is precomputed so input and
 * playback paths perform only an array read on the Cortex-A7.
 */
object VolumeCurve {

    /** Number of steps above silence. Level 0 is digital silence. */
    const val STEPS = 40

    /** Attenuation at level 1, in decibels. Level 0 is silence, not this value. */
    const val RANGE_DB = -48.0

    private val table: FloatArray = buildTable()

    private fun buildTable(): FloatArray = FloatArray(STEPS + 1) { level ->
        when (level) {
            0 -> 0f
            STEPS -> 1f
            else -> decibelGain(level.toFloat() / STEPS)
        }
    }

    /**
     * Constant-dB mapping of a normalized fader position to linear amplitude.
     * Returns exactly 0 at position 0 and exactly 1 at position 1.
     */
    fun decibelGain(position: Float, rangeDb: Double = RANGE_DB): Float {
        val x = position.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        return Math.pow(10.0, rangeDb * (1.0 - x) / 20.0).toFloat()
    }

    /** Cube-law mapping for comparison tests and hardware evaluation; not used for playback. */
    fun cubeGain(position: Float): Float {
        val x = position.coerceIn(0f, 1f)
        return x * x * x
    }

    /**
     * Linear amplitude for a discrete level in `0..`[STEPS].
     *
     * Guarantees: level 0 is exactly 0f (true digital silence, not a very small
     * number), level [STEPS] is exactly 1f (no app-level attenuation), and the
     * mapping is monotonically increasing.
     */
    fun gainForLevel(level: Int): Float = table[level.coerceIn(0, STEPS)]

    /** Clamps a level into range; used when restoring persisted values. */
    fun clampLevel(level: Int): Int = level.coerceIn(0, STEPS)

    /** Applies a wheel/button step, saturating at both ends. */
    fun adjustLevel(level: Int, direction: Int): Int =
        clampLevel(level + if (direction > 0) 1 else -1)

    /** Human-readable percentage of fader travel for the UI. */
    fun percentForLevel(level: Int): Int = (clampLevel(level) * 100) / STEPS
}

/**
 * Converts between Android's discrete music-stream index and Y2Player's
 * discrete in-app fader. Android 4.4 does not expose the vendor volume curve,
 * so fader position is the only stable quantity that can be transferred.
 *
 * The integer arithmetic is allocation-free and rounds to the nearest step.
 */
object VolumeModeTransfer {
    fun appLevelFromSystemIndex(systemIndex: Int, systemMax: Int): Int {
        if (systemMax <= 0) return VolumeCurve.STEPS
        val safeIndex = systemIndex.coerceIn(0, systemMax)
        return ((safeIndex.toLong() * VolumeCurve.STEPS + systemMax / 2L) / systemMax)
            .toInt()
            .coerceIn(0, VolumeCurve.STEPS)
    }

    fun systemIndexFromAppLevel(appLevel: Int, systemMax: Int): Int {
        if (systemMax <= 0) return 0
        val safeLevel = VolumeCurve.clampLevel(appLevel)
        return ((safeLevel.toLong() * systemMax + VolumeCurve.STEPS / 2L) / VolumeCurve.STEPS)
            .toInt()
            .coerceIn(0, systemMax)
    }
}

/**
 * How Y2Player controls loudness.
 *
 * [SYSTEM] is the default: hardware keys drive the Android music stream and the
 * app applies no attenuation of its own (its gain is exactly 1.0, so the
 * multiplication in the service is a no-op). It is the safe fallback.
 *
 * [PERCEPTUAL] holds the Android music stream at maximum and attenuates inside
 * the player via MediaPlayer.setVolume, using [VolumeCurve]. When modes change,
 * [VolumeModeTransfer] moves the current fader position to the newly active
 * control so the displayed level and available range stay in sync.
 *
 * Exactly one mode is active at a time. Stacking both would square the
 * attenuation and make the bottom of the range unusable.
 */
enum class VolumeMode(val storageId: String, val label: String) {
    SYSTEM("system", "System volume"),
    PERCEPTUAL("perceptual", "Perceptual (in-app)");

    fun next(): VolumeMode = if (this == SYSTEM) PERCEPTUAL else SYSTEM

    companion object {
        fun fromStorage(value: String?): VolumeMode =
            values().firstOrNull { it.storageId == value || it.name == value } ?: SYSTEM
    }
}
