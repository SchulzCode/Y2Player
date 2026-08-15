package com.schulzcode.y2player.playback

object VolumeCurve {
    const val STEPS = 40

    const val RANGE_DB = -48.0

    private val table: FloatArray = buildTable()

    private fun buildTable(): FloatArray = FloatArray(STEPS + 1) { level ->
        when (level) {
            0 -> 0f
            STEPS -> 1f
            else -> decibelGain(level.toFloat() / STEPS)
        }
    }

    fun decibelGain(position: Float, rangeDb: Double = RANGE_DB): Float {
        val x = position.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        return Math.pow(10.0, rangeDb * (1.0 - x) / 20.0).toFloat()
    }

    fun gainForLevel(level: Int): Float = table[level.coerceIn(0, STEPS)]

    fun clampLevel(level: Int): Int = level.coerceIn(0, STEPS)

    fun adjustLevel(level: Int, direction: Int): Int =
        clampLevel(level + if (direction > 0) 1 else -1)

    fun percentForLevel(level: Int): Int = (clampLevel(level) * 100) / STEPS
}

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

enum class VolumeMode(val storageId: String) {
    SYSTEM("system"),
    PERCEPTUAL("perceptual");

    fun next(): VolumeMode = if (this == SYSTEM) PERCEPTUAL else SYSTEM

    companion object {
        fun fromStorage(value: String?): VolumeMode =
            values().firstOrNull { it.storageId == value || it.name == value } ?: SYSTEM
    }
}
