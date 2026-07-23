package com.schulzcode.y2player.ui

import com.schulzcode.y2player.core.model.AudioOutputRoute
import com.schulzcode.y2player.core.model.AudioQualityMode

enum class RowVisualState { FOCUSED, FOCUSED_ACTIVE, ACTIVE, NORMAL, UNAVAILABLE }
enum class ArtworkVisual { EMBEDDED, FALLBACK }
enum class EmptyStateKind { SCANNING, STORAGE_MISSING, NO_MUSIC, EMPTY_QUEUE, EMPTY_FAVORITES, EMPTY_RECENT, EMPTY_PLAYLIST }
enum class EmptyStateAction { NONE, OPEN_STORAGE, GO_BACK }
enum class RouteIcon { HEADPHONES, BLUETOOTH, SPEAKER, DISCONNECTED, UNKNOWN }
enum class PlayerLayout { WIDE, TALL }

data class RoutePresentation(val label: String, val icon: RouteIcon, val warning: Boolean = false)

/** Pure UI decisions shared by the renderer and local unit tests. */
object Y2UiLogic {
    private val wiredRoute = RoutePresentation("Wired", RouteIcon.HEADPHONES)
    private val bluetoothRoute = RoutePresentation("Bluetooth", RouteIcon.BLUETOOTH)
    private val speakerRoute = RoutePresentation("Speaker", RouteIcon.SPEAKER, warning = true)
    private val noRoute = RoutePresentation("Output lost", RouteIcon.DISCONNECTED, warning = true)
    private val unknownRoute = RoutePresentation("Output idle", RouteIcon.UNKNOWN)

    fun rowVisualState(focused: Boolean, active: Boolean, unavailable: Boolean): RowVisualState = when {
        focused && active -> RowVisualState.FOCUSED_ACTIVE
        focused -> RowVisualState.FOCUSED
        unavailable -> RowVisualState.UNAVAILABLE
        active -> RowVisualState.ACTIVE
        else -> RowVisualState.NORMAL
    }

    fun playerLayout(widthPx: Int, heightPx: Int): PlayerLayout =
        if (widthPx > heightPx) PlayerLayout.WIDE else PlayerLayout.TALL

    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    fun visibleRowCount(availableHeightPx: Float, rowHeightPx: Float): Int {
        if (rowHeightPx <= 0f) return 1
        return (availableHeightPx.coerceAtLeast(0f) / rowHeightPx).toInt().coerceAtLeast(1)
    }

    fun firstVisibleRow(totalRows: Int, selectedIndex: Int, previousStart: Int, visibleCount: Int): Int {
        if (totalRows <= 0) return 0
        val count = visibleCount.coerceAtLeast(1)
        val selected = selectedIndex.coerceIn(0, totalRows - 1)
        var start = previousStart.coerceAtLeast(0)
        if (selected < start) start = selected
        if (selected >= start + count) start = selected - count + 1
        return start.coerceIn(0, (totalRows - count).coerceAtLeast(0))
    }

    inline fun truncationEnd(
        textLength: Int,
        maxWidth: Float,
        suffixWidth: Float,
        measurePrefix: (Int) -> Float
    ): Int {
        if (textLength <= 0 || maxWidth <= 0f || suffixWidth > maxWidth) return 0
        var low = 0
        var high = textLength
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (measurePrefix(middle) + suffixWidth <= maxWidth) low = middle else high = middle - 1
        }
        return low
    }

    fun safeTextBoundary(text: String, requestedEnd: Int): Int {
        var end = requestedEnd.coerceIn(0, text.length)
        if (end in 1 until text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) {
            end -= 1
        }
        return end
    }

    fun routePresentation(route: AudioOutputRoute): RoutePresentation = when (route) {
        AudioOutputRoute.WIRED -> wiredRoute
        AudioOutputRoute.BLUETOOTH -> bluetoothRoute
        AudioOutputRoute.SPEAKER -> speakerRoute
        AudioOutputRoute.NONE -> noRoute
        AudioOutputRoute.UNKNOWN -> unknownRoute
    }

    fun artworkVisual(hasArtwork: Boolean): ArtworkVisual = if (hasArtwork) ArtworkVisual.EMBEDDED else ArtworkVisual.FALLBACK

    fun emptyState(
        scanning: Boolean,
        storageAvailable: Boolean,
        queueScreen: Boolean,
        favoritesScreen: Boolean,
        playlistScreen: Boolean,
        recentScreen: Boolean = false
    ): EmptyStateKind = when {
        scanning -> EmptyStateKind.SCANNING
        !storageAvailable -> EmptyStateKind.STORAGE_MISSING
        queueScreen -> EmptyStateKind.EMPTY_QUEUE
        favoritesScreen -> EmptyStateKind.EMPTY_FAVORITES
        recentScreen -> EmptyStateKind.EMPTY_RECENT
        playlistScreen -> EmptyStateKind.EMPTY_PLAYLIST
        else -> EmptyStateKind.NO_MUSIC
    }

    fun emptyStateAction(kind: EmptyStateKind): EmptyStateAction = when (kind) {
        EmptyStateKind.SCANNING -> EmptyStateAction.NONE
        EmptyStateKind.STORAGE_MISSING, EmptyStateKind.NO_MUSIC -> EmptyStateAction.OPEN_STORAGE
        EmptyStateKind.EMPTY_QUEUE, EmptyStateKind.EMPTY_FAVORITES, EmptyStateKind.EMPTY_RECENT,
        EmptyStateKind.EMPTY_PLAYLIST -> EmptyStateAction.GO_BACK
    }

    fun statusMessageTimeoutMs(message: String?): Long = when {
        message == null -> 0L
        message.contains("disconnect", ignoreCase = true) || message.contains("output", ignoreCase = true) -> 6_000L
        else -> 3_200L
    }

    fun dacModeLabel(
        requestedMode: AudioQualityMode,
        dacDetected: Boolean,
        hiFiRequestAccepted: Boolean
    ): String = when {
        requestedMode != AudioQualityMode.DIRECT_DAC -> "Balanced"
        dacDetected && hiFiRequestAccepted -> "Direct DAC requested"
        dacDetected -> "Direct DAC unavailable"
        else -> "Balanced fallback"
    }
}
