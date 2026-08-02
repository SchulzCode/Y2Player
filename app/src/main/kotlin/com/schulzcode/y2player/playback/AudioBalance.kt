package com.schulzcode.y2player.playback

object AudioBalance {
    const val CENTRE = 0

    const val RANGE = 100

    val LEVELS: List<Int> = (-RANGE..RANGE step 10).toList()

    fun clamp(balance: Int): Int = balance.coerceIn(-RANGE, RANGE)

    fun isCentred(balance: Int): Boolean = clamp(balance) == CENTRE

    fun leftGain(balance: Int): Float = gain(clamp(balance))

    fun rightGain(balance: Int): Float = gain(-clamp(balance))

    private fun gain(offset: Int): Float =
        if (offset <= 0) 1f else (1f - offset.toFloat() / RANGE).coerceIn(0f, 1f)

    fun label(balance: Int): String = when (val value = clamp(balance)) {
        CENTRE -> "Centre · off"
        -RANGE -> "Left only"
        RANGE -> "Right only"
        else -> if (value < 0) "Left ${-value}%" else "Right $value%"
    }
}
