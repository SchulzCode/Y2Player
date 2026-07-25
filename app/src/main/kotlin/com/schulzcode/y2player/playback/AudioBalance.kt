package com.schulzcode.y2player.playback

/**
 * Left/right channel balance, as a pair of gains applied to MediaPlayer.
 *
 * Requested for asymmetric hearing loss. Implemented with
 * `MediaPlayer.setVolume(left, right)` rather than an `AudioEffect` because
 * AOSP has no balance effect at any API level, and because a per-channel gain on
 * the player is the cheapest possible route — no extra thread, no DSP, nothing
 * added to the decode path, and it keeps working on firmware whose effect
 * framework is missing entirely.
 *
 * **It attenuates, never boosts.** MediaPlayer volume saturates at 1.0, so
 * leaning left lowers the right channel rather than raising the left. Total
 * loudness therefore drops as balance moves off centre, which is the honest
 * behaviour: the alternative would be to pre-attenuate both channels at centre
 * and lose headroom for everyone to benefit a setting almost nobody changes.
 *
 * **What this cannot do:** sum both channels into both ears. Someone with no
 * hearing on one side needs a mono downmix, not balance — with balance alone,
 * anything panned to the silenced channel is simply lost. MediaPlayer cannot mix
 * channels, and the platform's own `MASTER_MONO` setting arrived in API 23, four
 * releases after this device. That would need a decode path of our own.
 */
object AudioBalance {
    /** Centre. The default, and the only value that changes nothing. */
    const val CENTRE = 0

    /** Full deflection in either direction, in percent. */
    const val RANGE = 100

    /** Offered on the settings screen, coarse near centre is not useful here. */
    val LEVELS: List<Int> = (-RANGE..RANGE step 10).toList()

    fun clamp(balance: Int): Int = balance.coerceIn(-RANGE, RANGE)

    /** True when the setting is doing nothing, so callers can skip work entirely. */
    fun isCentred(balance: Int): Boolean = clamp(balance) == CENTRE

    /** Negative balance leans left, so the left channel is the one left alone. */
    fun leftGain(balance: Int): Float = gain(clamp(balance))

    fun rightGain(balance: Int): Float = gain(-clamp(balance))

    /**
     * A channel is untouched on its own side and attenuated on the other.
     *
     * [offset] is how far the balance has moved *away* from this channel: at or
     * below zero the channel keeps full gain, above zero it is attenuated in
     * proportion. Both signs were wired the wrong way round on the first attempt,
     * which the tests caught — the labels stayed correct while the sound moved the
     * opposite way, which is the worst possible version of this bug.
     */
    private fun gain(offset: Int): Float =
        if (offset <= 0) 1f else (1f - offset.toFloat() / RANGE).coerceIn(0f, 1f)

    fun label(balance: Int): String = when (val value = clamp(balance)) {
        CENTRE -> "Centre · off"
        -RANGE -> "Left only"
        RANGE -> "Right only"
        else -> if (value < 0) "Left ${-value}%" else "Right $value%"
    }
}
