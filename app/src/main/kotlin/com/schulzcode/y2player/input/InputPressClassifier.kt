package com.schulzcode.y2player.input

internal object InputPressClassifier {
    const val LONG_PRESS_REPEAT = 3
    const val LONG_PRESS_MS = 650L

    /**
     * Key-repeat events between successive scrub seeks while a seek key stays held.
     * Android repeats roughly every 50 ms after the initial delay, so 8 repeats is
     * ~400 ms between seek steps — fast enough to feel continuous, slow enough not
     * to flood MediaPlayer with overlapping seekTo calls on the MT6582.
     */
    const val SCRUB_REPEAT_PERIOD = 8

    fun isLongPress(frameworkLongPress: Boolean, repeatCount: Int, heldForMs: Long): Boolean =
        frameworkLongPress || repeatCount >= LONG_PRESS_REPEAT || heldForMs.coerceAtLeast(0) >= LONG_PRESS_MS
}
