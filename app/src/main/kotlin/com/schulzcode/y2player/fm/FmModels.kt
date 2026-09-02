package com.schulzcode.y2player.fm

/**
 * Band limits for the region the device is used in.
 *
 * Frequencies are held in kHz as integers rather than MHz floats so that
 * repeated stepping cannot accumulate rounding error, and so the UI can format
 * them without floating point comparisons.
 */
object FmBand {
    const val MIN_KHZ = 87_500
    const val MAX_KHZ = 108_000

    /** Europe uses 100 kHz spacing. The Americas use 200 kHz. */
    const val STEP_KHZ = 100

    fun clamp(khz: Int): Int = khz.coerceIn(MIN_KHZ, MAX_KHZ)

    /** Steps by [steps] channels, wrapping at both ends so tuning never dead-ends. */
    fun step(khz: Int, steps: Int): Int {
        val span = MAX_KHZ - MIN_KHZ + STEP_KHZ
        val offset = clamp(khz) - MIN_KHZ + steps * STEP_KHZ
        return MIN_KHZ + ((offset % span) + span) % span
    }

    /** "103.7", without allocating a formatter. */
    fun label(khz: Int): String {
        val tenths = khz / 100
        return "${tenths / 10}.${tenths % 10}"
    }
}

/**
 * Everything the FM screen renders, kept in the store like any other state.
 *
 * [rssi] is only refreshed when something changes the tuning, never on a timer:
 * a background poll would keep the CPU awake for a screen that is usually idle.
 */
data class FmState(
    val available: Boolean = false,
    val powered: Boolean = false,
    val frequencyKhz: Int = 103_700,
    val rssi: Int = RSSI_UNKNOWN,
    val stereo: Boolean = false,
    val seeking: Boolean = false,
    val message: String? = null
) {
    val frequencyLabel: String get() = FmBand.label(frequencyKhz)

    /**
     * Signal as a 0..1 fraction for the meter. The tuner reports dBm, where
     * about -110 is the noise floor and -50 a strong local transmitter.
     */
    val signalFraction: Float
        get() = if (rssi == RSSI_UNKNOWN) 0f
        else ((rssi + 110).toFloat() / 60f).coerceIn(0f, 1f)

    companion object {
        const val RSSI_UNKNOWN = Int.MIN_VALUE
    }
}
