package com.schulzcode.y2player.ui

import com.schulzcode.y2player.core.state.Screen

internal class SelectedTextScroller(
    private val speedPxPerSecond: Float,
    private val initialDelayMs: Long = INITIAL_DELAY_MS,
    private val endPauseMs: Long = END_PAUSE_MS
) {
    private var phase = PHASE_WAITING
    private var phaseStartedAtMs = 0L
    private var active = false
    private var targetKind = TARGET_NONE
    private var targetScreen: Screen? = null
    private var targetIndex = -1
    private var targetText = ""
    private var overflowPx = 0f

    var offsetPx = 0f
        private set

    val drawsFullText: Boolean get() = targetKind != TARGET_NONE && phase != PHASE_WAITING
    val hasTarget: Boolean get() = targetKind != TARGET_NONE
    val kind: Int get() = targetKind
    val index: Int get() = targetIndex

    fun setActive(value: Boolean, nowMs: Long): Boolean {
        if (active == value) return false
        active = value
        restart(nowMs)
        return true
    }

    fun setTarget(
        kind: Int,
        screen: Screen,
        index: Int,
        text: String,
        textWidthPx: Float,
        availableWidthPx: Float,
        nowMs: Long
    ): Boolean {
        val overflow = (textWidthPx - availableWidthPx).coerceAtLeast(0f)
        if (text.isEmpty() || overflow <= MIN_OVERFLOW_PX) return clear()
        if (targetKind == kind && targetScreen == screen && targetIndex == index &&
            targetText == text && overflowPx == overflow
        ) return false

        targetKind = kind
        targetScreen = screen
        targetIndex = index
        targetText = text
        overflowPx = overflow
        restart(nowMs)
        return true
    }

    fun isTarget(kind: Int, screen: Screen, index: Int, text: String): Boolean =
        targetKind == kind && targetScreen == screen && targetIndex == index && targetText == text

    fun restart(nowMs: Long) {
        phase = PHASE_WAITING
        phaseStartedAtMs = nowMs
        offsetPx = 0f
    }

    fun clear(): Boolean {
        if (targetKind == TARGET_NONE) return false
        targetKind = TARGET_NONE
        targetScreen = null
        targetIndex = -1
        targetText = ""
        overflowPx = 0f
        offsetPx = 0f
        phase = PHASE_WAITING
        return true
    }

    /** Returns 0 for the next animation frame, a delay, or -1 for no callback. */
    fun advance(nowMs: Long): Long {
        if (!active || targetKind == TARGET_NONE) return NO_CALLBACK
        val elapsed = (nowMs - phaseStartedAtMs).coerceAtLeast(0L)
        return when (phase) {
            PHASE_WAITING -> {
                val remaining = initialDelayMs - elapsed
                if (remaining > 0L) remaining else {
                    phase = PHASE_MOVING
                    phaseStartedAtMs = nowMs
                    offsetPx = 0f
                    NEXT_FRAME
                }
            }
            PHASE_MOVING -> {
                offsetPx = (elapsed * speedPxPerSecond / 1_000f).coerceAtMost(overflowPx)
                if (offsetPx >= overflowPx) {
                    phase = PHASE_END_PAUSE
                    phaseStartedAtMs = nowMs
                    endPauseMs
                } else NEXT_FRAME
            }
            else -> {
                val remaining = endPauseMs - elapsed
                if (remaining > 0L) remaining else {
                    restart(nowMs)
                    initialDelayMs
                }
            }
        }
    }

    companion object {
        const val TARGET_LIST = 1
        const val TARGET_NOW_TITLE = 2
        const val TARGET_NOW_ARTIST = 3
        const val TARGET_NOW_ALBUM = 4
        const val NEXT_FRAME = 0L
        const val NO_CALLBACK = -1L
        const val INITIAL_DELAY_MS = 1_500L
        const val END_PAUSE_MS = 1_000L
        private const val TARGET_NONE = 0
        private const val PHASE_WAITING = 0
        private const val PHASE_MOVING = 1
        private const val PHASE_END_PAUSE = 2
        private const val MIN_OVERFLOW_PX = 1f
    }
}
