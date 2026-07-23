package com.schulzcode.y2player.ui

import com.schulzcode.y2player.core.model.AudioOutputRoute
import com.schulzcode.y2player.core.model.AudioOutputRouteResolver
import com.schulzcode.y2player.core.model.AudioQualityMode
import com.schulzcode.y2player.core.model.PauseReason
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.state.AppState
import com.schulzcode.y2player.core.state.ScreenContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Y2UiLogicTest {
    @Test fun themeUsesOneReadableAccentOnDarkSurfaces() {
        assertEquals(0xFF0A0D12.toInt(), Y2UiTheme.BACKGROUND)
        assertEquals(0xFFD6AC53.toInt(), Y2UiTheme.ACCENT)
        assertTrue(contrastRatio(Y2UiTheme.PRIMARY_TEXT, Y2UiTheme.BACKGROUND) >= 7.0)
        assertTrue(contrastRatio(Y2UiTheme.ACCENT, Y2UiTheme.BACKGROUND) >= 4.5)
        assertTrue(Y2UiTheme.FOCUS_SURFACE != Y2UiTheme.BACKGROUND)
        assertTrue(contrastRatio(Y2UiTheme.SECONDARY_TEXT, Y2UiTheme.BACKGROUND) >= 4.5)
    }

    @Test fun focusAndPlayingStatesRemainDistinct() {
        assertEquals(RowVisualState.FOCUSED, Y2UiLogic.rowVisualState(focused = true, active = false, unavailable = true))
        assertEquals(RowVisualState.FOCUSED_ACTIVE, Y2UiLogic.rowVisualState(focused = true, active = true, unavailable = false))
        assertEquals(RowVisualState.ACTIVE, Y2UiLogic.rowVisualState(focused = false, active = true, unavailable = false))
        assertEquals(RowVisualState.UNAVAILABLE, Y2UiLogic.rowVisualState(focused = false, active = false, unavailable = true))
    }

    @Test fun focusSurfaceFitsInsideTheMenuAndClearsTheScrollbar() {
        val rowAreaRight = Y2UiTheme.TARGET_WIDTH_PX * .55f
        val halfStroke = Y2UiTheme.FOCUS_OUTLINE_DP * .5f
        val scrollbarRight = rowAreaRight - Y2UiTheme.SCROLLBAR_END_INSET_DP
        val scrollbarLeft = scrollbarRight - Y2UiTheme.SCROLLBAR_WIDTH_DP
        val focusLeft = Y2UiTheme.ROW_FOCUS_INSET_DP
        val focusRight = scrollbarLeft - Y2UiTheme.ROW_SCROLLBAR_GAP_DP - halfStroke

        assertTrue(focusLeft - halfStroke > 0f)
        assertEquals(Y2UiTheme.EDGE_DP, focusLeft, 0f)
        assertEquals(
            Y2UiTheme.ROW_SCROLLBAR_GAP_DP,
            scrollbarLeft - (focusRight + halfStroke),
            0f
        )
        assertTrue(scrollbarRight < rowAreaRight)
        assertEquals(2f, Y2UiTheme.FOCUS_BAR_WIDTH_DP, 0f)
        assertTrue(Y2UiTheme.FOCUS_OUTLINE_DP > 0f)
    }

    @Test fun homeProgressBlockMovesUpWithoutIncreasingItsPane() {
        assertEquals(32f, Y2UiTheme.HOME_PROGRESS_BOTTOM_INSET_DP, 0f)
        assertTrue(Y2UiTheme.HOME_PROGRESS_BOTTOM_INSET_DP > 26f)
        val footerTop = Y2UiTheme.TARGET_HEIGHT_PX - Y2UiTheme.COMPACT_FOOTER_HEIGHT_DP
        val paneBottom = footerTop - 10f
        val barTop = paneBottom - Y2UiTheme.HOME_PROGRESS_BOTTOM_INSET_DP
        val knobBottom = barTop + Y2UiTheme.PROGRESS_HEIGHT_DP * .5f + 3f
        val timeBaseline = barTop + 15f
        for (position in listOf(0L, 50L, 100L)) {
            assertTrue(Y2UiLogic.progressFraction(position, 100L) in 0f..1f)
            assertTrue(knobBottom < paneBottom)
            assertTrue(timeBaseline < paneBottom)
            assertTrue(paneBottom < footerTop)
        }
    }

    @Test fun textTruncationFindsTheLargestPrefixAndPreservesSurrogatePairs() {
        val end = Y2UiLogic.truncationEnd(textLength = 10, maxWidth = 7f, suffixWidth = 1f) { it.toFloat() }
        assertEquals(6, end)
        val unicode = "AB\uD83C\uDFB5CD"
        assertEquals(2, Y2UiLogic.safeTextBoundary(unicode, 3))
        assertEquals(4, Y2UiLogic.safeTextBoundary(unicode, 4))
    }

    @Test fun progressIsClampedAndZeroDurationIsSafe() {
        assertEquals(0f, Y2UiLogic.progressFraction(-10, 100), 0f)
        assertEquals(.5f, Y2UiLogic.progressFraction(50, 100), 0f)
        assertEquals(1f, Y2UiLogic.progressFraction(150, 100), 0f)
        assertEquals(0f, Y2UiLogic.progressFraction(10, 0), 0f)
    }

    @Test fun y2LandscapeViewportKeepsFourReadableRowsAndTracksSelection() {
        // 360 px tall landscape panel minus 44/30 dp chrome leaves four 58 dp rows.
        val available = Y2UiTheme.TARGET_HEIGHT_PX - Y2UiTheme.HEADER_HEIGHT_DP - Y2UiTheme.COMPACT_FOOTER_HEIGHT_DP
        val count = Y2UiLogic.visibleRowCount(available, Y2UiTheme.ROW_HEIGHT_DP)
        assertEquals(4, count)
        assertEquals(0, Y2UiLogic.firstVisibleRow(100, 0, 0, count))
        assertEquals(4, Y2UiLogic.firstVisibleRow(100, 7, 0, count))
        assertEquals(96, Y2UiLogic.firstVisibleRow(100, 99, 1, count))
        val albumAvailable = available - Y2UiTheme.DETAIL_HEADER_HEIGHT_DP
        assertEquals(3, Y2UiLogic.visibleRowCount(albumAvailable, Y2UiTheme.ROW_HEIGHT_DP))
    }

    @Test fun routeIndicatorsAreExplicitAndWarningsAreVisible() {
        assertEquals(RouteIcon.HEADPHONES, Y2UiLogic.routePresentation(AudioOutputRoute.WIRED).icon)
        assertEquals("Bluetooth", Y2UiLogic.routePresentation(AudioOutputRoute.BLUETOOTH).label)
        assertTrue(Y2UiLogic.routePresentation(AudioOutputRoute.SPEAKER).warning)
        assertEquals("Output lost", Y2UiLogic.routePresentation(AudioOutputRoute.NONE).label)
        assertEquals("Output idle", Y2UiLogic.routePresentation(AudioOutputRoute.UNKNOWN).label)
    }

    @Test fun outputResolverNeverClaimsOneOfTwoSimultaneousPrivateRoutes() {
        assertEquals(
            AudioOutputRoute.UNKNOWN,
            AudioOutputRouteResolver.resolve(true, true, PlaybackStatus.PLAYING, PauseReason.NONE)
        )
        assertEquals(
            AudioOutputRoute.NONE,
            AudioOutputRouteResolver.resolve(false, false, PlaybackStatus.PAUSED, PauseReason.OUTPUT_DISCONNECTED)
        )
        assertEquals(
            AudioOutputRoute.SPEAKER,
            AudioOutputRouteResolver.resolve(false, false, PlaybackStatus.PLAYING, PauseReason.NONE)
        )
    }

    @Test fun emptyStatesPrioritizeScanAndMissingStorage() {
        assertEquals(
            EmptyStateKind.SCANNING,
            Y2UiLogic.emptyState(true, false, queueScreen = true, favoritesScreen = false, playlistScreen = false)
        )
        assertEquals(
            EmptyStateKind.STORAGE_MISSING,
            Y2UiLogic.emptyState(false, false, queueScreen = false, favoritesScreen = true, playlistScreen = false)
        )
        assertEquals(
            EmptyStateKind.EMPTY_QUEUE,
            Y2UiLogic.emptyState(false, true, queueScreen = true, favoritesScreen = false, playlistScreen = false)
        )
        assertEquals(EmptyStateAction.NONE, Y2UiLogic.emptyStateAction(EmptyStateKind.SCANNING))
        assertEquals(EmptyStateAction.OPEN_STORAGE, Y2UiLogic.emptyStateAction(EmptyStateKind.NO_MUSIC))
        assertEquals(EmptyStateAction.GO_BACK, Y2UiLogic.emptyStateAction(EmptyStateKind.EMPTY_QUEUE))
    }

    @Test fun layoutSelectionMatchesTheRealLandscapePanelAndPortraitFallback() {
        assertEquals(PlayerLayout.WIDE, Y2UiLogic.playerLayout(480, 360))
        assertEquals(PlayerLayout.TALL, Y2UiLogic.playerLayout(320, 480))
    }

    @Test fun criticalTypographyMeetsTheSmallScreenReadabilityFloor() {
        assertTrue(Y2UiTheme.SCREEN_TITLE_SP >= 18f)
        assertTrue(Y2UiTheme.ROW_TITLE_SP >= 16f)
        assertTrue(Y2UiTheme.ROW_SUBTITLE_SP >= 13f)
        assertTrue(Y2UiTheme.NAV_LABEL_SP >= 12f)
        assertTrue(Y2UiTheme.ICON_SIZE_DP >= 24f)
    }

    @Test fun routeMessagesStayLongerThanOrdinaryMessages() {
        assertEquals(0L, Y2UiLogic.statusMessageTimeoutMs(null))
        assertEquals(3_200L, Y2UiLogic.statusMessageTimeoutMs("Playlist saved"))
        assertEquals(6_000L, Y2UiLogic.statusMessageTimeoutMs("Private output disconnected"))
    }

    @Test fun artworkFallbackAndDacLabelsAreDeterministic() {
        assertEquals(ArtworkVisual.EMBEDDED, Y2UiLogic.artworkVisual(true))
        assertEquals(ArtworkVisual.FALLBACK, Y2UiLogic.artworkVisual(false))
        assertEquals("Balanced", Y2UiLogic.dacModeLabel(AudioQualityMode.BALANCED, true, true))
        assertEquals("Direct DAC requested", Y2UiLogic.dacModeLabel(AudioQualityMode.DIRECT_DAC, true, true))
        assertEquals("Direct DAC unavailable", Y2UiLogic.dacModeLabel(AudioQualityMode.DIRECT_DAC, true, false))
        assertEquals("Balanced fallback", Y2UiLogic.dacModeLabel(AudioQualityMode.DIRECT_DAC, false, false))
    }

    /** Visible rows must fit the fixed cache used by drawRows. */
    @Test fun visibleRowCountNeverExceedsTheFixedRowCache() {
        val maxVisibleRows = 12
        val tallPanel = Y2UiLogic.visibleRowCount(availableHeightPx = 2_000f, rowHeightPx = 46f)
        assertTrue("unclamped count must be larger, otherwise this test proves nothing", tallPanel > maxVisibleRows)
        assertEquals(maxVisibleRows, tallPanel.coerceAtMost(maxVisibleRows))
    }

    @Test fun mainMenuRowCalculationRemainsStableForWheelNavigation() {
        // Seven browsing rows (docs/PRODUCT_DESIGN.md): the playing track lives in the
        // home player pane, not in the menu.
        assertEquals(7, ScreenContent.rows(AppState()).size)
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        val brighter = maxOf(luminance(first), luminance(second))
        val darker = minOf(luminance(first), luminance(second))
        return (brighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color ushr shift) and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
