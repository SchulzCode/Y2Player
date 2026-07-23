package com.schulzcode.y2player.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.schulzcode.y2player.artwork.AlbumArtworkLoader
import com.schulzcode.y2player.core.model.AudioCodecLabels
import com.schulzcode.y2player.core.model.AudioQualityMode
import com.schulzcode.y2player.core.model.PauseReason
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.SleepTimerMode
import com.schulzcode.y2player.core.state.AppAction
import com.schulzcode.y2player.core.state.AppState
import com.schulzcode.y2player.core.state.BluetoothAdapterMode
import com.schulzcode.y2player.core.state.BluetoothLinkState
import com.schulzcode.y2player.core.state.Screen
import com.schulzcode.y2player.core.state.ScreenContent
import com.schulzcode.y2player.core.state.ScreenRow
import com.schulzcode.y2player.util.TimeFormat

/**
 * Single custom-drawn surface for the whole UI.
 *
 * Rendering rules: all strings and derived visual state are cached in
 * [updatePresentationCache] / [refreshVisibleRowCache]; onDraw never allocates,
 * never formats, never looks anything up. The partial invalidation paths in
 * [render] (selection-only / progress-only) must stay intact.
 *
 * Layout is derived from the runtime width/height. The primary target is the Y2's
 * 480x360 landscape panel; a portrait panel automatically falls back to the stacked
 * Now Playing layout.
 */
@SuppressLint("ViewConstructor")
@Suppress("DEPRECATION")
class Y2PlayerView(
    context: Context,
    private val dispatch: (AppAction) -> Unit,
    // Shared process-wide loader (AppContainer); this view must not shut it down.
    private val artworkLoader: AlbumArtworkLoader
) : View(context) {
    private val density = resources.displayMetrics.density.coerceAtLeast(1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.NORMAL)
        style = Paint.Style.FILL
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
        style = Paint.Style.FILL
    }
    private val iconPainter = Y2IconPainter(paint, density)
    private val reusableRect = Rect()
    private val reusableRectF = RectF()

    private var state: AppState = AppState()
    private var rows = ScreenContent.rows(state)
    private var artwork: Bitmap? = null
    private var artworkPath: String? = null
    private var detailArtwork: Bitmap? = null
    private var detailArtworkPath: String? = null
    private var visibleStart = 0
    private val rowHeight = Y2UiTheme.ROW_HEIGHT_DP * density
    private val headerHeight = Y2UiTheme.HEADER_HEIGHT_DP * density
    private val detailHeaderHeight = Y2UiTheme.DETAIL_HEADER_HEIGHT_DP * density
    private val footerHeight: Float
        get() = when {
            isSplitHome() -> 0f

            state.currentScreen == Screen.NowPlaying ->
                Y2UiTheme.COMPACT_FOOTER_HEIGHT_DP * density

            state.playback.currentTrackId != null ->
                Y2UiTheme.MINI_PLAYER_HEIGHT_DP * density

            else ->
                Y2UiTheme.COMPACT_FOOTER_HEIGHT_DP * density
        }
    private var pendingTouchIndex: Int? = null
    private var pendingTouchActive = false

    private val cachedTitles = arrayOfNulls<String>(MAX_VISIBLE_ROWS)
    private val cachedSubtitles = arrayOfNulls<String>(MAX_VISIBLE_ROWS)
    private val cachedIcons = Array(MAX_VISIBLE_ROWS) { Y2Icon.INFO }
    private val cachedTrailingIcons = arrayOfNulls<Y2Icon>(MAX_VISIBLE_ROWS)
    private val cachedTrailingText = arrayOfNulls<String>(MAX_VISIBLE_ROWS)
    private val cachedTrackNumbers = arrayOfNulls<String>(MAX_VISIBLE_ROWS)
    private val cachedActive = BooleanArray(MAX_VISIBLE_ROWS)
    private val cachedUnavailable = BooleanArray(MAX_VISIBLE_ROWS)
    private var cachedRowStart = -1
    private var cachedRowEnd = -1
    private var cachedHeaderTitle = "Y2 Player"
    private var cachedRoute = Y2UiLogic.routePresentation(state.playback.outputRoute)
    private var cachedHeaderRouteIcon: RouteIcon? = null
    private var cachedBatteryText = "--"
    private var cachedBatteryPercent: Int? = null
    private var cachedFooterPosition = ""
    private var cachedFooterHint = ""
    private var cachedMiniTitle = ""
    private var cachedMiniArtist = ""
    private var cachedNowTitle = "Nothing playing"
    private var cachedNowTitle2 = ""
    private var cachedNowArtist = "Select a track"
    private var cachedNowAlbum = ""
    private var cachedTechnicalLine = ""
    private var cachedNowSecondary = ""
    private var cachedNowSecondaryWarning = false
    private var cachedStatusTag = ""
    private var cachedDacLabel = "Balanced"
    private var cachedElapsed = "0:00"
    private var cachedDuration = "0:00"
    private var cachedMessageSource: String? = null
    private var cachedMessageTitle = ""
    private var cachedMessageBody = ""
    private var cachedNowFavorite = false
    private var cachedPaneTitle = ""
    private var cachedPaneArtist = ""
    private var cachedHomeStats = ""
    private var cachedHomeHint = ""
    private var cachedDetailTitle = ""
    private var cachedDetailTitle2 = ""
    private var cachedDetailSubtitle = ""

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updatePresentationCache()
    }

    fun render(newState: AppState) {
        val oldState = state
        val progressOnly = oldState.copy(
            playback = oldState.playback.copy(
                positionMs = newState.playback.positionMs,
                durationMs = newState.playback.durationMs,
                sleepTimerRemainingMs = newState.playback.sleepTimerRemainingMs
            )
        ) == newState
        val sameScreenPath = oldState.screenStack.size == newState.screenStack.size &&
            oldState.screenStack.indices.all { oldState.screenStack[it].screen == newState.screenStack[it].screen }
        val selectionOnly = sameScreenPath && oldState.copy(screenStack = newState.screenStack) == newState

        state = newState
        if (selectionOnly) {
            ensureSelectionVisible()
            updateFooterPosition()
            contentDescription = buildContentDescription(newState)
            invalidate(0, headerHeight.toInt(), width, height)
            return
        }
        if (progressOnly) {
            cachedElapsed = TimeFormat.duration(newState.playback.positionMs)
            cachedDuration = TimeFormat.duration(newState.playback.durationMs)
            when (newState.currentScreen) {
                Screen.NowPlaying -> invalidate(0, headerHeight.toInt(), width, (height - footerHeight).toInt())
                // The home player pane shows live progress; repaint only its region.
                Screen.MainMenu -> if (isSplitHome() && newState.playback.currentTrackId != null) {
                    invalidate(rowAreaRight().toInt(), headerHeight.toInt(), width, (height - footerHeight).toInt())
                }
                Screen.PlaybackSettings, Screen.NowPlayingOptions -> if (
                    oldState.playback.sleepTimerRemainingMs != newState.playback.sleepTimerRemainingMs
                ) {
                    rows = ScreenContent.rows(newState)
                    invalidateRowCache()
                    invalidate()
                }
                else -> Unit
            }
            return
        }

        rows = ScreenContent.rows(newState)
        requestArtwork()
        updatePresentationCache()
        invalidateRowCache()
        ensureSelectionVisible()
        contentDescription = buildContentDescription(newState)
        invalidate()
    }

    fun trimMemory() {
        artwork = null
        artworkPath = null
        detailArtwork = null
        detailArtworkPath = null
        artworkLoader.trimMemory()
        invalidate()
    }

    fun release() {
        // The artwork loader is owned by AppContainer and outlives this view.
        artwork = null
        artworkPath = null
        detailArtwork = null
        detailArtworkPath = null
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        invalidateRowCache()
        updatePresentationCache()
        ensureSelectionVisible()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Y2UiTheme.BACKGROUND)
        drawHeader(canvas)
        if (state.currentScreen == Screen.NowPlaying) {
            drawNowPlaying(canvas)
        } else {
            if (hasDetailHeader()) drawDetailHeader(canvas)
            drawRows(canvas)
        }
        if (isSplitHome()) drawHomePane(canvas)
        drawFooter(canvas)
        drawStatusMessage(canvas)
    }

    /** Home uses the iPod-style split layout on landscape panels: menu left, player pane right. */
    private fun isSplitHome(): Boolean = state.currentScreen == Screen.MainMenu && width > height

    /** Right edge of the list area; the home player pane owns the rest. */
    private fun rowAreaRight(): Float = if (isSplitHome()) width * .55f else width.toFloat()

    private fun hasDetailHeader(): Boolean = state.currentScreen is Screen.AlbumSongs || state.currentScreen is Screen.ArtistSongs

    private fun rowAreaTop(): Float = headerHeight + if (hasDetailHeader()) detailHeaderHeight else 0f

    private fun rowAreaBottom(): Float = height - footerHeight - if (cachedMessageSource != null) 66f * density else 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pendingTouchActive = true
                pendingTouchIndex = touchIndex(event.x, event.y)
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                pendingTouchActive = false
                pendingTouchIndex = null
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!pendingTouchActive) true else {
                    pendingTouchActive = false
                    // Tapping the home player pane opens Now Playing, like a Right press.
                    if (isSplitHome() && event.x >= rowAreaRight() && event.y >= headerHeight && event.y < height - footerHeight) {
                        pendingTouchIndex = null
                        dispatch(AppAction.Right)
                        return true
                    }
                    if (state.currentScreen != Screen.NowPlaying) {
                        pendingTouchIndex = touchIndex(event.x, event.y)
                        if (pendingTouchIndex == null) return true
                    }
                    performClick()
                }
            }
            else -> true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (state.currentScreen == Screen.NowPlaying) {
            dispatch(AppAction.Confirm)
            pendingTouchActive = false
            pendingTouchIndex = null
            return true
        }
        val index = pendingTouchIndex ?: state.selectedIndex
        pendingTouchIndex = null
        if (index !in rows.indices) return false
        if (index == state.selectedIndex) dispatch(AppAction.Confirm) else dispatch(AppAction.SelectIndex(index))
        return true
    }

    private fun touchIndex(x: Float, y: Float): Int? {
        val rowsTop = rowAreaTop()
        if (y < rowsTop || y >= rowAreaBottom()) return null
        if (x >= rowAreaRight()) return null
        return (visibleStart + ((y - rowsTop) / rowHeight).toInt()).takeIf { it in rows.indices }
    }

    // ------------------------------------------------------------------ header
    private fun drawHeader(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Y2UiTheme.SURFACE
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, paint)

        val headerSave = canvas.save()
        canvas.translate(0f, -2f * density)

        val hasBack = state.screenStack.size > 1
        val titleLeft =
            if (hasBack) 36f * density
            else Y2UiTheme.EDGE_DP * density

        if (hasBack) {
            val save = canvas.save()
            canvas.rotate(180f, 19f * density, 23f * density)
            iconPainter.draw(
                canvas,
                Y2Icon.CHEVRON,
                19f * density,
                23f * density,
                18f * density,
                Y2UiTheme.PRIMARY_TEXT
            )
            canvas.restoreToCount(save)
        }

        boldPaint.color = Y2UiTheme.PRIMARY_TEXT
        boldPaint.textSize = Y2UiTheme.SCREEN_TITLE_SP * density
        boldPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            cachedHeaderTitle,
            titleLeft,
            30f * density,
            boldPaint
        )

        if (state.safeMode || state.library.isScanning) {
            val markerX =
                titleLeft +
                        boldPaint.measureText(cachedHeaderTitle) +
                        9f * density

            paint.style = Paint.Style.FILL

            if (state.safeMode) {
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = Y2UiTheme.BADGE_SP * density
                paint.color = Y2UiTheme.WARNING
                canvas.drawText("SAFE", markerX, 29f * density, paint)
            } else {
                paint.color = Y2UiTheme.ACCENT
                canvas.drawCircle(
                    markerX + 3f * density,
                    23f * density,
                    3f * density,
                    paint
                )
            }
        }

        var rightEdge = width - 10f * density
        rightEdge = drawBattery(canvas, rightEdge)

        cachedHeaderRouteIcon?.let { icon ->
            val color =
                if (cachedRoute.warning) {
                    Y2UiTheme.WARNING
                } else {
                    Y2UiTheme.ACCENT
                }

            iconPainter.draw(
                canvas,
                routeIcon(icon),
                rightEdge - 8f * density,
                22f * density,
                16f * density,
                color
            )

            paint.style = Paint.Style.FILL
        }

        canvas.restoreToCount(headerSave)
    }

    /** Battery glyph + percent; returns the x consumed up to (for stacking leftwards). */
    private fun drawBattery(canvas: Canvas, rightEdge: Float): Float {
        val color = when {
            state.device.charging -> Y2UiTheme.SUCCESS
            (cachedBatteryPercent ?: 100) <= 15 -> Y2UiTheme.WARNING
            else -> Y2UiTheme.SECONDARY_TEXT
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = Y2UiTheme.BADGE_SP * density
        paint.color = color
        val textWidth = if (cachedBatteryText.isEmpty()) 0f else {
            canvas.drawText(cachedBatteryText, rightEdge, 28f * density, paint)
            paint.measureText(cachedBatteryText) + 5f * density
        }

        val nubWidth = 2f * density
        val bodyRight = rightEdge - textWidth - nubWidth
        val bodyWidth = 15f * density
        val bodyTop = 16f * density
        val bodyBottom = 28f * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        reusableRectF.set(bodyRight - bodyWidth, bodyTop, bodyRight, bodyBottom)
        canvas.drawRoundRect(reusableRectF, 2f * density, 2f * density, paint)
        paint.style = Paint.Style.FILL
        reusableRectF.set(bodyRight, bodyTop + 2.5f * density, bodyRight + nubWidth, bodyBottom - 2.5f * density)
        canvas.drawRoundRect(reusableRectF, density, density, paint)
        val percent = (cachedBatteryPercent ?: 0).coerceIn(0, 100)
        if (percent > 0) {
            val innerWidth = (bodyWidth - 4f * density) * percent / 100f
            reusableRectF.set(
                bodyRight - bodyWidth + 2f * density,
                bodyTop + 2f * density,
                bodyRight - bodyWidth + 2f * density + innerWidth,
                bodyBottom - 2f * density
            )
            canvas.drawRoundRect(reusableRectF, density, density, paint)
        }
        return bodyRight - bodyWidth - 10f * density
    }

    // ------------------------------------------------------------------ rows

    private fun drawRows(canvas: Canvas) {
        if (rows.isEmpty()) {
            drawEmptyState(canvas)
            return
        }
        refreshVisibleRowCache()
        val rowsRight = rowAreaRight()
        val rowsTop = rowAreaTop()
        val saveCount = canvas.save()
        canvas.clipRect(0f, rowsTop, rowsRight, rowAreaBottom())
        for (index in cachedRowStart until cachedRowEnd) {
            val slot = index - cachedRowStart
            val top = rowsTop + (index - visibleStart) * rowHeight
            val bottom = top + rowHeight
            val focused = index == state.selectedIndex
            val visual = Y2UiLogic.rowVisualState(focused, cachedActive[slot], cachedUnavailable[slot])
            if (focused) drawFocus(canvas, top, bottom) else if (cachedActive[slot]) drawActiveRow(canvas, top, bottom)

            val iconColor = when (visual) {
                RowVisualState.FOCUSED, RowVisualState.FOCUSED_ACTIVE, RowVisualState.ACTIVE -> Y2UiTheme.ACCENT
                RowVisualState.UNAVAILABLE -> Y2UiTheme.MUTED_TEXT
                RowVisualState.NORMAL -> Y2UiTheme.SECONDARY_TEXT
            }
            val trackNumber = cachedTrackNumbers[slot]
            if (trackNumber != null && !cachedActive[slot]) {
                boldPaint.textAlign = Paint.Align.CENTER
                boldPaint.textSize = Y2UiTheme.META_SP * density
                boldPaint.color = iconColor
                canvas.drawText(trackNumber, 28f * density, top + rowHeight * .5f + 4f * density, boldPaint)
                boldPaint.textAlign = Paint.Align.LEFT
            } else {
                iconPainter.draw(canvas, cachedIcons[slot], 28f * density, top + rowHeight * .5f, Y2UiTheme.ICON_SIZE_DP * density, iconColor)
            }

            val subtitle = cachedSubtitles[slot]
            boldPaint.textAlign = Paint.Align.LEFT
            boldPaint.textSize = Y2UiTheme.ROW_TITLE_SP * density
            boldPaint.color = when (visual) {
                RowVisualState.UNAVAILABLE -> Y2UiTheme.MUTED_TEXT
                RowVisualState.ACTIVE -> Y2UiTheme.ACCENT
                else -> Y2UiTheme.PRIMARY_TEXT
            }
            val titleBaseline = if (subtitle == null) top + rowHeight * .5f + 6f * density else top + 24f * density
            canvas.drawText(cachedTitles[slot].orEmpty(), 50f * density, titleBaseline, boldPaint)
            if (subtitle != null) {
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
                paint.color = if (visual == RowVisualState.UNAVAILABLE) Y2UiTheme.MUTED_TEXT else Y2UiTheme.SECONDARY_TEXT
                canvas.drawText(subtitle, 50f * density, top + 45f * density, paint)
            }

            val surfaceRight = rowSurfaceRight()
            cachedTrailingText[slot]?.let { trailing ->
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.RIGHT
                paint.textSize = Y2UiTheme.META_SP * density
                paint.color = if (visual == RowVisualState.UNAVAILABLE) Y2UiTheme.MUTED_TEXT else Y2UiTheme.SECONDARY_TEXT
                canvas.drawText(trailing, surfaceRight - 8f * density, top + rowHeight * .5f + 4f * density, paint)
                paint.textAlign = Paint.Align.LEFT
            }
            cachedTrailingIcons[slot]?.let { trailingIcon ->
                iconPainter.draw(canvas, trailingIcon, surfaceRight - 13f * density, top + rowHeight * .5f, 17f * density, iconColor)
            }
        }
        canvas.restoreToCount(saveCount)
        drawScrollIndicator(canvas)
    }

    private fun drawFocus(canvas: Canvas, top: Float, bottom: Float) {
        val left = Y2UiTheme.ROW_FOCUS_INSET_DP * density
        val right = rowSurfaceRight()

        // Subtle background for the focused row.
        reusableRectF.set(
            left,
            top + 2.5f * density,
            right,
            bottom - 2.5f * density
        )

        paint.style = Paint.Style.FILL
        paint.color = Y2UiTheme.FOCUS_SURFACE

        canvas.drawRoundRect(
            reusableRectF,
            Y2UiTheme.ROW_RADIUS_DP * density,
            Y2UiTheme.ROW_RADIUS_DP * density,
            paint
        )

        // Accent indicator only on the left side.
        val barWidth = Y2UiTheme.FOCUS_BAR_WIDTH_DP * density

        reusableRectF.set(
            left,
            top + 9f * density,
            left + barWidth,
            bottom - 9f * density
        )

        paint.color = Y2UiTheme.ACCENT

        canvas.drawRoundRect(
            reusableRectF,
            barWidth * 0.5f,
            barWidth * 0.5f,
            paint
        )
    }

    private fun drawActiveRow(canvas: Canvas, top: Float, bottom: Float) {
        reusableRectF.set(
            (Y2UiTheme.ROW_FOCUS_INSET_DP + 1f) * density,
            top + 4f * density,
            rowSurfaceRight() - density,
            bottom - 4f * density
        )
        paint.color = Y2UiTheme.ACTIVE_SURFACE
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(reusableRectF, Y2UiTheme.ROW_RADIUS_DP * density, Y2UiTheme.ROW_RADIUS_DP * density, paint)
    }

    /** Right edge of row surfaces, including the complete focus stroke and scrollbar gap. */
    private fun rowSurfaceRight(): Float {
        if (!hasScrollIndicator()) return rowAreaRight() - Y2UiTheme.ROW_FOCUS_INSET_DP * density
        val scrollbarLeft = rowAreaRight() -
            (Y2UiTheme.SCROLLBAR_END_INSET_DP + Y2UiTheme.SCROLLBAR_WIDTH_DP) * density
        return scrollbarLeft -
            (Y2UiTheme.ROW_SCROLLBAR_GAP_DP + Y2UiTheme.FOCUS_OUTLINE_DP * .5f) * density
    }

    private fun hasScrollIndicator(): Boolean = !isSplitHome() && rows.size > visibleRowCount()

    private fun drawScrollIndicator(canvas: Canvas) {
        val visibleCount = visibleRowCount()
        if (!hasScrollIndicator()) return
        val right = rowAreaRight()
        val trackTop = rowAreaTop() + 8f * density
        val trackBottom = rowAreaBottom() - 8f * density
        val trackHeight = trackBottom - trackTop
        paint.style = Paint.Style.FILL
        paint.color = Y2UiTheme.DIVIDER
        val trackRight = right - Y2UiTheme.SCROLLBAR_END_INSET_DP * density
        val trackLeft = trackRight - Y2UiTheme.SCROLLBAR_WIDTH_DP * density
        reusableRectF.set(trackLeft, trackTop, trackRight, trackBottom)
        canvas.drawRoundRect(reusableRectF, density, density, paint)
        val thumbHeight = (trackHeight * visibleCount / rows.size.toFloat()).coerceAtLeast(24f * density)
        val maxStart = (rows.size - visibleCount).coerceAtLeast(1)
        val thumbTop = trackTop + (trackHeight - thumbHeight) * (visibleStart.toFloat() / maxStart)
        paint.color = Y2UiTheme.ACCENT
        reusableRectF.set(trackLeft, thumbTop, trackRight, thumbTop + thumbHeight)
        canvas.drawRoundRect(reusableRectF, density, density, paint)
    }

    private fun drawEmptyState(canvas: Canvas) {
        val storageAvailable = state.device.internalStorageAvailable || state.device.removableStorageAvailable
        val kind = Y2UiLogic.emptyState(
            scanning = state.library.isScanning,
            storageAvailable = storageAvailable,
            queueScreen = state.currentScreen == Screen.Queue,
            favoritesScreen = state.currentScreen == Screen.Favorites,
            playlistScreen = state.currentScreen is Screen.PlaylistTracks,
            recentScreen = state.currentScreen == Screen.RecentlyPlayed
        )
        val title: String
        val detail: String
        val icon: Y2Icon
        when (kind) {
            EmptyStateKind.SCANNING -> {
                title = "Scanning music"
                detail = "Building your library safely"
                icon = Y2Icon.PREPARING
            }
            EmptyStateKind.STORAGE_MISSING -> {
                title = "Music storage unavailable"
                detail = "Insert or remount the SD card"
                icon = Y2Icon.STORAGE
            }
            EmptyStateKind.EMPTY_QUEUE -> {
                title = "Queue is empty"
                detail = "Choose a song to begin"
                icon = Y2Icon.QUEUE
            }
            EmptyStateKind.EMPTY_FAVORITES -> {
                title = "No favorites yet"
                detail = "Press Right on any song to favorite it"
                icon = Y2Icon.FAVORITE
            }
            EmptyStateKind.EMPTY_RECENT -> {
                title = "Nothing played yet"
                detail = "Played tracks will appear here"
                icon = Y2Icon.RECENT
            }
            EmptyStateKind.EMPTY_PLAYLIST -> {
                title = "Playlist is empty"
                detail = "Press Right on any song to add it"
                icon = Y2Icon.PLAYLIST
            }
            EmptyStateKind.NO_MUSIC -> {
                title = "No music found"
                detail = "Add supported audio files"
                icon = Y2Icon.SONG
            }
        }
        val centerX = width * .5f
        val rowsTop = rowAreaTop()
        val centerY = rowsTop + (rowAreaBottom() - rowsTop) * .42f
        paint.style = Paint.Style.FILL
        paint.color = Y2UiTheme.SURFACE_RAISED
        canvas.drawCircle(centerX, centerY - 34f * density, 26f * density, paint)
        iconPainter.draw(canvas, icon, centerX, centerY - 34f * density, 27f * density, if (kind == EmptyStateKind.STORAGE_MISSING) Y2UiTheme.WARNING else Y2UiTheme.ACCENT)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = Y2UiTheme.SECTION_TITLE_SP * density
        boldPaint.color = Y2UiTheme.PRIMARY_TEXT
        canvas.drawText(title, centerX, centerY + 12f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = Y2UiTheme.BODY_SP * density
        paint.color = Y2UiTheme.SECONDARY_TEXT
        canvas.drawText(ellipsize(detail, width - 34f * density, paint), centerX, centerY + 32f * density, paint)
        val emptyAction = Y2UiLogic.emptyStateAction(kind)
        if (emptyAction != EmptyStateAction.NONE) {
            val action = when (emptyAction) {
                EmptyStateAction.OPEN_STORAGE -> "CENTER · OPEN STORAGE"
                EmptyStateAction.GO_BACK -> "CENTER · GO BACK"
                EmptyStateAction.NONE -> ""
            }
            reusableRectF.set(centerX - 92f * density, centerY + 41f * density, centerX + 92f * density, centerY + 71f * density)
            paint.style = Paint.Style.FILL
            paint.color = Y2UiTheme.FOCUS_SURFACE
            canvas.drawRoundRect(reusableRectF, 8f * density, 8f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Y2UiTheme.FOCUS_OUTLINE_DP * density
            paint.color = Y2UiTheme.ACCENT
            canvas.drawRoundRect(reusableRectF, 8f * density, 8f * density, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
            paint.color = Y2UiTheme.PRIMARY_TEXT
            canvas.drawText(action, centerX, centerY + 61f * density, paint)
        }
        paint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
    }

    // ------------------------------------------------------------ album / artist detail

    /**
     * A compact header keeps identity and artwork visible without creating a
     * second focus pane. The wheel remains one-dimensional: header action first,
     * then tracks, with the selected row always scrolled into view.
     */
    private fun drawDetailHeader(canvas: Canvas) {
        val top = headerHeight
        val bottom = top + detailHeaderHeight
        paint.style = Paint.Style.FILL
        paint.color = Y2UiTheme.SURFACE
        canvas.drawRect(0f, top, width.toFloat(), bottom, paint)

        val artSize = 60f * density
        val artLeft = Y2UiTheme.EDGE_DP * density
        val artTop = top + 8f * density
        val fallback = if (state.currentScreen is Screen.ArtistSongs) Y2Icon.ARTIST else Y2Icon.ALBUM
        drawArtwork(canvas, artLeft, artTop, artSize, detailArtwork, fallback)

        val textLeft = artLeft + artSize + 12f * density
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.SECTION_TITLE_SP * density
        boldPaint.color = Y2UiTheme.PRIMARY_TEXT
        canvas.drawText(cachedDetailTitle, textLeft, top + 25f * density, boldPaint)
        if (cachedDetailTitle2.isNotEmpty()) {
            canvas.drawText(cachedDetailTitle2, textLeft, top + 44f * density, boldPaint)
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        paint.color = Y2UiTheme.SECONDARY_TEXT
        canvas.drawText(cachedDetailSubtitle, textLeft, if (cachedDetailTitle2.isEmpty()) top + 51f * density else top + 66f * density, paint)
    }

    // ------------------------------------------------------------------ now playing

    private fun drawNowPlaying(canvas: Canvas) {
        if (Y2UiLogic.playerLayout(width, height) == PlayerLayout.WIDE) drawNowPlayingWide(canvas) else drawNowPlayingTall(canvas)
    }

    /**
     * Landscape layout: artwork dominates the left, everything else forms one calm
     * left-aligned column. Hierarchy: artwork > title > artist > album > state badges
     * > progress. Route/DAC details appear only as warnings; the codec line is the
     * single always-on secondary detail (it is a HiFi player).
     */
    private fun drawNowPlayingWide(canvas: Canvas) {
        val contentTop = headerHeight
        val contentHeight = height - footerHeight - contentTop
        val margin = 14f * density
        val artSize = (contentHeight - 2 * margin).coerceAtMost(232f * density)
        val artTop = contentTop + (contentHeight - artSize) * .5f
        drawArtwork(canvas, margin, artTop, artSize)

        val colLeft = margin + artSize + 18f * density
        val colRight = width - margin
        val controlY = artTop + artSize - 22f * density
        val barTop = controlY - 48f * density

        // Metadata block flows top-down with one fact per line; the title may take
        // two lines — it is the most important text in the application.
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.NOW_TITLE_SP * density
        boldPaint.color = Y2UiTheme.PRIMARY_TEXT
        var y = artTop + 26f * density
        canvas.drawText(cachedNowTitle, colLeft, y, boldPaint)
        if (cachedNowTitle2.isNotEmpty()) {
            y += 22f * density
            canvas.drawText(cachedNowTitle2, colLeft, y, boldPaint)
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.NOW_ARTIST_SP * density
        paint.color = Y2UiTheme.SECONDARY_TEXT
        y += 22f * density
        canvas.drawText(cachedNowArtist, colLeft, y, paint)
        if (cachedNowAlbum.isNotEmpty()) {
            paint.textSize = Y2UiTheme.NOW_ALBUM_SP * density
            paint.color = Y2UiTheme.MUTED_TEXT
            y += 19f * density
            canvas.drawText(cachedNowAlbum, colLeft, y, paint)
        }
        if (cachedNowSecondary.isNotEmpty()) {
            paint.textSize = Y2UiTheme.META_SP * density
            paint.color = if (cachedNowSecondaryWarning) Y2UiTheme.WARNING else Y2UiTheme.MUTED_TEXT
            y += 21f * density
            canvas.drawText(cachedNowSecondary, colLeft, y, paint)
        }
        drawStateBadges(canvas, colLeft + 8f * density, (y + 25f * density).coerceAtMost(barTop - 18f * density), 28f * density)

        // Fixed, breathing-room anchored progress block at the artwork's bottom edge.
        // Non-playing states announce themselves as a quiet text tag ABOVE the bar —
        // a glyph beside the bar merged visually with the knob into one malformed
        // shape (the "gold blob" defect).
        if (cachedStatusTag.isNotEmpty()) {
            boldPaint.textAlign = Paint.Align.LEFT
            boldPaint.textSize = Y2UiTheme.BADGE_SP * density
            boldPaint.color = if (state.playback.status == PlaybackStatus.ERROR) Y2UiTheme.WARNING else Y2UiTheme.ACCENT
            canvas.drawText(cachedStatusTag, colLeft, barTop - 8f * density, boldPaint)
        }
        drawProgressBar(canvas, colLeft, colRight, barTop)
        val timesY = barTop + 19f * density
        paint.style = Paint.Style.FILL
        paint.textSize = Y2UiTheme.META_SP * density
        paint.color = Y2UiTheme.SECONDARY_TEXT
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(cachedElapsed, colLeft, timesY, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(cachedDuration, colRight, timesY, paint)
        drawPlaybackControls(canvas, colLeft, colRight, controlY)
        paint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawPlaybackControls(canvas: Canvas, left: Float, right: Float, centerY: Float) {
        val centerX = (left + right) * .5f
        val sideOffset = ((right - left) * .34f).coerceAtMost(70f * density)
        iconPainter.draw(canvas, Y2Icon.PREVIOUS, centerX - sideOffset, centerY, 24f * density, Y2UiTheme.PRIMARY_TEXT)
        iconPainter.draw(canvas, Y2Icon.NEXT, centerX + sideOffset, centerY, 24f * density, Y2UiTheme.PRIMARY_TEXT)

        paint.style = Paint.Style.FILL
        paint.color = Y2UiTheme.FOCUS_SURFACE
        canvas.drawCircle(centerX, centerY, 22f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Y2UiTheme.FOCUS_OUTLINE_DP * density
        paint.color = Y2UiTheme.ACCENT
        canvas.drawCircle(centerX, centerY, 22f * density, paint)
        paint.style = Paint.Style.FILL
        val playbackIcon = when (state.playback.status) {
            PlaybackStatus.PLAYING -> Y2Icon.PAUSE
            PlaybackStatus.PREPARING -> Y2Icon.PREPARING
            PlaybackStatus.ERROR -> Y2Icon.WARNING
            else -> Y2Icon.PLAY
        }
        iconPainter.draw(
            canvas,
            playbackIcon,
            centerX,
            centerY,
            Y2UiTheme.PRIMARY_ICON_SIZE_DP * density,
            if (state.playback.status == PlaybackStatus.ERROR) Y2UiTheme.WARNING else Y2UiTheme.ACCENT
        )
    }

    /**
     * Home player pane (right 45 % of the split home). While a track is loaded:
     * artwork thumbnail, title, artist, live progress — the playing music is always
     * visible content, never a menu row. Idle: library summary pointing at
     * Shuffle All. Everything drawn from cached strings and the shared artwork
     * bitmap; no allocations.
     */
    private fun drawHomePane(canvas: Canvas) {
        val paneLeft = rowAreaRight() + 6f * density
        val paneRight = width - 10f * density
        val paneTop = headerHeight + 10f * density
        val paneBottom = height - footerHeight - 10f * density
        reusableRectF.set(paneLeft, paneTop, paneRight, paneBottom)
        paint.style = Paint.Style.FILL
        paint.color = Y2UiTheme.SURFACE
        canvas.drawRoundRect(reusableRectF, Y2UiTheme.PANEL_RADIUS_DP * density, Y2UiTheme.PANEL_RADIUS_DP * density, paint)

        val paneWidth = paneRight - paneLeft
        val centerX = paneLeft + paneWidth * .5f
        val hasTrack = state.playback.currentTrackId != null
        if (hasTrack) {
            val artSize = (paneWidth - 48f * density).coerceAtMost(paneBottom - paneTop - 92f * density)
            val artTop = paneTop + 12f * density
            drawArtwork(canvas, centerX - artSize * .5f, artTop, artSize)
            boldPaint.textAlign = Paint.Align.CENTER
            boldPaint.textSize = Y2UiTheme.MINI_TITLE_SP * density
            boldPaint.color = Y2UiTheme.PRIMARY_TEXT
            canvas.drawText(cachedPaneTitle, centerX, artTop + artSize + 20f * density, boldPaint)
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
            paint.color = Y2UiTheme.SECONDARY_TEXT
            canvas.drawText(cachedPaneArtist, centerX, artTop + artSize + 36f * density, paint)
            val barTop = paneBottom - Y2UiTheme.HOME_PROGRESS_BOTTOM_INSET_DP * density
            val timeBaseline = paneBottom - Y2UiTheme.HOME_PROGRESS_TIME_BOTTOM_INSET_DP * density
            drawProgressBar(canvas, paneLeft + 16f * density, paneRight - 16f * density, barTop)
            paint.style = Paint.Style.FILL
            paint.textSize = Y2UiTheme.BADGE_SP * density
            paint.color = Y2UiTheme.MUTED_TEXT
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(cachedElapsed, paneLeft + 16f * density, timeBaseline, paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(cachedDuration, paneRight - 16f * density, timeBaseline, paint)
        } else {
            val centerY = (paneTop + paneBottom) * .5f
            iconPainter.draw(canvas, Y2Icon.SONG, centerX, centerY - 34f * density, 34f * density, Y2UiTheme.ACCENT)
            paint.style = Paint.Style.FILL
            boldPaint.textAlign = Paint.Align.CENTER
            boldPaint.textSize = Y2UiTheme.BODY_SP * density
            boldPaint.color = Y2UiTheme.PRIMARY_TEXT
            canvas.drawText(cachedHomeStats, centerX, centerY + 6f * density, boldPaint)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
            paint.color = Y2UiTheme.MUTED_TEXT
            canvas.drawText(cachedHomeHint, centerX, centerY + 24f * density, paint)
        }
        paint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
    }

    /** Portrait fallback: stacked Now Playing layout for natural-portrait panels. */
    private fun drawNowPlayingTall(canvas: Canvas) {
        val centerX = width * .5f
        val artSize = (width * .46f).coerceIn(124f * density, 156f * density)
        val artTop = headerHeight + 10f * density
        drawArtwork(canvas, centerX - artSize * .5f, artTop, artSize)

        val titleTop = artTop + artSize + 9f * density
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = Y2UiTheme.NOW_TITLE_SP * density
        boldPaint.color = Y2UiTheme.PRIMARY_TEXT
        canvas.drawText(cachedNowTitle, centerX, titleTop + 19f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = Y2UiTheme.NOW_ARTIST_SP * density
        paint.color = Y2UiTheme.SECONDARY_TEXT
        canvas.drawText(cachedNowArtist, centerX, titleTop + 40f * density, paint)
        paint.textSize = Y2UiTheme.NOW_ALBUM_SP * density
        paint.color = Y2UiTheme.MUTED_TEXT
        canvas.drawText(cachedNowAlbum, centerX, titleTop + 58f * density, paint)
        paint.textSize = Y2UiTheme.META_SP * density
        paint.color = if (cachedNowSecondaryWarning) Y2UiTheme.WARNING else Y2UiTheme.MUTED_TEXT
        canvas.drawText(cachedNowSecondary, centerX, titleTop + 75f * density, paint)

        val barTop = titleTop + 88f * density
        drawProgressBar(canvas, 20f * density, width - 20f * density, barTop)
        paint.style = Paint.Style.FILL
        paint.textSize = Y2UiTheme.META_SP * density
        paint.color = Y2UiTheme.SECONDARY_TEXT
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(cachedElapsed, 20f * density, barTop + 21f * density, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(cachedDuration, width - 20f * density, barTop + 21f * density, paint)

        val controlY = barTop + 47f * density
        drawPlaybackControls(canvas, 20f * density, width - 20f * density, controlY)
        paint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawArtwork(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
        source: Bitmap? = artwork,
        fallbackIcon: Y2Icon = Y2Icon.SONG
    ) {
        reusableRectF.set(left, top, left + size, top + size)
        paint.style = Paint.Style.FILL
        paint.color = Y2UiTheme.SURFACE_RAISED
        canvas.drawRoundRect(reusableRectF, Y2UiTheme.PANEL_RADIUS_DP * density, Y2UiTheme.PANEL_RADIUS_DP * density, paint)
        val bitmap = source
        if (Y2UiLogic.artworkVisual(bitmap != null) == ArtworkVisual.EMBEDDED && bitmap != null) {
            reusableRect.set(
                (left + 3f * density).toInt(),
                (top + 3f * density).toInt(),
                (left + size - 3f * density).toInt(),
                (top + size - 3f * density).toInt()
            )
            canvas.drawBitmap(bitmap, null, reusableRect, paint)
        } else {
            val centerX = left + size * .5f
            val centerY = top + size * .5f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = Y2UiTheme.DIVIDER
            canvas.drawCircle(centerX, centerY, size * .29f, paint)
            canvas.drawCircle(centerX, centerY, size * .17f, paint)
            paint.style = Paint.Style.FILL
            iconPainter.draw(canvas, fallbackIcon, centerX, centerY, size * .28f, Y2UiTheme.ACCENT)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = Y2UiTheme.DIVIDER
        reusableRectF.set(left, top, left + size, top + size)
        canvas.drawRoundRect(reusableRectF, Y2UiTheme.PANEL_RADIUS_DP * density, Y2UiTheme.PANEL_RADIUS_DP * density, paint)
        paint.style = Paint.Style.FILL
    }

    /**
     * State badges show only *active* states (shuffle on, repeat on, timer armed,
     * favorited). A muted icon for "off" answers a question nobody asked — the
     * absence of a badge already means off.
     */
    private fun drawStateBadges(canvas: Canvas, startX: Float, centerY: Float, step: Float) {
        var x = startX
        if (state.playback.shuffleEnabled) {
            iconPainter.draw(canvas, Y2Icon.SHUFFLE, x, centerY, 15f * density, Y2UiTheme.ACCENT)
            x += step
        }
        if (state.playback.repeatMode != RepeatMode.OFF) {
            iconPainter.draw(canvas, Y2Icon.REPEAT, x, centerY, 15f * density, Y2UiTheme.ACCENT)
            if (state.playback.repeatMode == RepeatMode.ONE) {
                boldPaint.textAlign = Paint.Align.CENTER
                boldPaint.textSize = Y2UiTheme.META_SP * density
                boldPaint.color = Y2UiTheme.ACCENT
                canvas.drawText("1", x, centerY + 2.5f * density, boldPaint)
                boldPaint.textAlign = Paint.Align.LEFT
            }
            x += step
        }
        if (state.playback.sleepTimerMode != SleepTimerMode.OFF) {
            iconPainter.draw(canvas, Y2Icon.TIMER, x, centerY, 14f * density, Y2UiTheme.ACCENT)
            x += step
        }
        if (cachedNowFavorite) {
            iconPainter.draw(canvas, Y2Icon.FAVORITE, x, centerY, 14f * density, Y2UiTheme.ACCENT)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawProgressBar(canvas: Canvas, left: Float, right: Float, top: Float) {
        val barHeight = Y2UiTheme.PROGRESS_HEIGHT_DP * density
        paint.style = Paint.Style.FILL
        reusableRectF.set(left, top, right, top + barHeight)
        paint.color = Y2UiTheme.DIVIDER
        canvas.drawRoundRect(reusableRectF, barHeight * .5f, barHeight * .5f, paint)
        val progress = Y2UiLogic.progressFraction(state.playback.positionMs, state.playback.durationMs)
        val progressRight = left + (right - left) * progress
        reusableRectF.set(left, top, progressRight.coerceAtLeast(left), top + barHeight)
        paint.color = Y2UiTheme.ACCENT
        canvas.drawRoundRect(reusableRectF, barHeight * .5f, barHeight * .5f, paint)
        if (progress > 0f) {
            // Clamp the knob inside the track: near 0% or 100% an unclamped knob
            // bulges past the bar's rounded ends.
            val knobRadius = 3f * density
            val knobX = progressRight.coerceIn(left + knobRadius, right - knobRadius)
            canvas.drawCircle(knobX, top + barHeight * .5f, knobRadius, paint)
        }
    }

    // ------------------------------------------------------------------ footer

    private fun drawFooter(canvas: Canvas) {
        if (isSplitHome()) return

        if (
            state.playback.currentTrackId != null &&
            state.currentScreen != Screen.NowPlaying
        ) {
            drawMiniPlayer(canvas)
        } else {
            drawCompactFooter(canvas)
        }
    }

    private fun drawMiniPlayer(canvas: Canvas) {
        val top = height - footerHeight
        paint.style = Paint.Style.FILL
        paint.color = Y2UiTheme.SURFACE_RAISED
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
        paint.color = Y2UiTheme.ACCENT
        canvas.drawRect(0f, top, width.toFloat(), top + 2f * density, paint)

        val artSize = Y2UiTheme.MINI_ART_SIZE_DP * density
        drawArtwork(canvas, 8f * density, top + 8f * density, artSize)
        val textLeft = 8f * density + artSize + 10f * density
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.MINI_TITLE_SP * density
        boldPaint.color = Y2UiTheme.PRIMARY_TEXT
        canvas.drawText(cachedMiniTitle, textLeft, top + 25f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
        paint.color = Y2UiTheme.SECONDARY_TEXT
        canvas.drawText(cachedMiniArtist, textLeft, top + 45f * density, paint)

        val controlX = width - 29f * density
        val controlY = top + footerHeight * .5f
        // Playback status, not a separate focus stop. Reserve FOCUS_SURFACE and
        // its accent outline for controls the wheel can actually focus.
        paint.color = Y2UiTheme.SURFACE
        canvas.drawCircle(controlX, controlY, 20f * density, paint)
        val playbackIcon = when (state.playback.status) {
            PlaybackStatus.PLAYING -> Y2Icon.PAUSE
            PlaybackStatus.PREPARING -> Y2Icon.PREPARING
            PlaybackStatus.ERROR -> Y2Icon.WARNING
            else -> Y2Icon.PLAY
        }
        iconPainter.draw(canvas, playbackIcon, controlX, controlY, 24f * density,
            if (state.playback.status == PlaybackStatus.ERROR) Y2UiTheme.WARNING else Y2UiTheme.ACCENT)
    }

    private fun drawCompactFooter(canvas: Canvas) {
        val top = height - footerHeight
        paint.style = Paint.Style.FILL
        paint.color = Y2UiTheme.SURFACE
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = Y2UiTheme.BADGE_SP * density
        paint.color = Y2UiTheme.SECONDARY_TEXT
        canvas.drawText(cachedFooterPosition, width - 10f * density, top + 20f * density, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
        paint.color = Y2UiTheme.MUTED_TEXT
        canvas.drawText(cachedFooterHint, 10f * density, top + 20f * density, paint)
    }

    private fun drawStatusMessage(canvas: Canvas) {
        if (cachedMessageSource == null) return
        val bottom = height - footerHeight - 8f * density
        val top = bottom - 58f * density
        reusableRectF.set(9f * density, top, width - 9f * density, bottom)
        paint.style = Paint.Style.FILL
        paint.color = Y2UiTheme.SURFACE_RAISED
        canvas.drawRoundRect(reusableRectF, Y2UiTheme.PANEL_RADIUS_DP * density, Y2UiTheme.PANEL_RADIUS_DP * density, paint)
        paint.color = if (state.playback.pauseReason == PauseReason.OUTPUT_DISCONNECTED) Y2UiTheme.WARNING else Y2UiTheme.ACCENT
        reusableRectF.set(9f * density, top + 9f * density, 12f * density, bottom - 9f * density)
        canvas.drawRoundRect(reusableRectF, 1.5f * density, 1.5f * density, paint)
        iconPainter.draw(canvas, if (state.playback.pauseReason == PauseReason.OUTPUT_DISCONNECTED) Y2Icon.DISCONNECTED else Y2Icon.INFO, 29f * density, top + 29f * density, 19f * density, paint.color)
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.BODY_SP * density
        boldPaint.color = Y2UiTheme.PRIMARY_TEXT
        canvas.drawText(cachedMessageTitle, 46f * density, top + 24f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.META_SP * density
        paint.color = Y2UiTheme.SECONDARY_TEXT
        canvas.drawText(cachedMessageBody, 46f * density, top + 42f * density, paint)
    }

    // ------------------------------------------------------------------ caches

    private fun updatePresentationCache() {
        cachedRoute = Y2UiLogic.routePresentation(state.playback.outputRoute)
        // Header status earns space only for an active choice (Bluetooth) or a real
        // problem (output lost). Speaker playback is audible; it needs no alarm icon.
        cachedHeaderRouteIcon = when (cachedRoute.icon) {
            RouteIcon.BLUETOOTH -> RouteIcon.BLUETOOTH
            RouteIcon.DISCONNECTED -> RouteIcon.DISCONNECTED
            else -> null
        }
        cachedBatteryPercent = state.device.batteryPercent
        // The glyph already shows the level; percent text appears only when it is
        // actionable information (charging or low).
        cachedBatteryText = state.device.batteryPercent?.takeIf { state.device.charging || it <= 20 }?.let { "$it%" } ?: ""
        updateFooterPosition()
        val track = state.playback.currentTrackId?.let(state.library.byId::get)
        cachedNowFavorite = track?.favorite == true
        val nowWidth = nowPlayingTextWidth()
        boldPaint.textSize = Y2UiTheme.NOW_TITLE_SP * density
        splitNowTitle(track?.title ?: "Nothing playing", nowWidth)
        paint.textSize = Y2UiTheme.NOW_ARTIST_SP * density
        cachedNowArtist = ellipsize(track?.displayArtist ?: "Select a track", nowWidth, paint)
        paint.textSize = Y2UiTheme.NOW_ALBUM_SP * density
        // Suppress the album line when it merely repeats the artist (common for
        // single-artist metadata) — two identical adjacent lines read as a bug.
        val albumText = track?.displayAlbum?.takeUnless { it == track.displayArtist } ?: ""
        cachedNowAlbum = ellipsize(albumText, nowWidth, paint)
        cachedTechnicalLine = if (track == null) "" else buildTechnicalLine(
            AudioCodecLabels.label(track.codec, track.extension),
            track.sampleRate,
            track.bitDepth
        )
        cachedDacLabel = Y2UiLogic.dacModeLabel(
            state.preferences.audioQualityMode,
            state.playback.dac.detected,
            state.playback.dac.hiFiRequestAccepted
        )
        // Only real problems surface as warnings: output lost or a DAC fallback.
        // Speaker playback is audible reality, not an anomaly, so the codec line
        // stays as the single secondary detail.
        val outputLost = cachedRoute.icon == RouteIcon.DISCONNECTED
        val dacWarning = cachedDacLabel.contains("unavailable") || cachedDacLabel.contains("fallback")
        cachedNowSecondaryWarning = outputLost || dacWarning
        val secondary = when {
            outputLost -> cachedRoute.label
            dacWarning -> cachedDacLabel
            else -> cachedTechnicalLine
        }
        paint.textSize = Y2UiTheme.META_SP * density
        cachedNowSecondary = ellipsize(secondary, nowWidth, paint)
        // Status changes always trigger a full render, so this stays cache-driven.
        cachedStatusTag = when (state.playback.status) {
            PlaybackStatus.PAUSED -> "PAUSED"
            PlaybackStatus.PREPARING -> "LOADING"
            PlaybackStatus.ERROR -> "ERROR"
            else -> ""
        }
        val miniTextWidth = (fallbackWidth() - 128f * density).coerceAtLeast(60f * density)
        boldPaint.textSize = Y2UiTheme.MINI_TITLE_SP * density
        cachedMiniTitle = if (track == null) "" else ellipsize(track.title, miniTextWidth, boldPaint)
        paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
        cachedMiniArtist = if (track == null) "" else ellipsize(track.displayArtist, miniTextWidth, paint)
        // Home player pane strings (pane is ~45% of the width, minus padding).
        val paneTextWidth = (fallbackWidth() * .45f - 40f * density).coerceAtLeast(40f * density)
        boldPaint.textSize = Y2UiTheme.MINI_TITLE_SP * density
        cachedPaneTitle = if (track == null) "" else ellipsize(track.title, paneTextWidth, boldPaint)
        paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
        cachedPaneArtist = if (track == null) "" else ellipsize(track.displayArtist, paneTextWidth, paint)
        // Idle-pane strings: computed and ellipsized here (never in onDraw) so they can
        // never cross the pane boundary, matching every other cached string.
        val count = state.library.availableTracks.size
        val stats = when (count) {
            0 -> "No music yet"
            1 -> "1 song"
            else -> String.format(java.util.Locale.US, "%,d songs", count)
        }
        boldPaint.textSize = Y2UiTheme.BODY_SP * density
        cachedHomeStats = ellipsize(stats, paneTextWidth, boldPaint)
        // The idle pane's second line guides the next action instead of a static slogan.
        val homeHint = when {
            state.library.isScanning -> "Scanning your music…"
            count == 0 -> "Add music, then rescan"
            else -> "Shuffle All to begin"
        }
        paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
        cachedHomeHint = ellipsize(homeHint, paneTextWidth, paint)
        cachedElapsed = TimeFormat.duration(state.playback.positionMs)
        cachedDuration = TimeFormat.duration(state.playback.durationMs)
        val availableWidth = fallbackWidth() - 130f * density
        boldPaint.textSize = Y2UiTheme.SCREEN_TITLE_SP * density
        cachedHeaderTitle = ellipsize(ScreenContent.title(state), availableWidth, boldPaint)
        updateDetailCache()
        updateFooterHint()
        updateMessageCache()
    }

    private fun updateDetailCache() {
        val screen = state.currentScreen
        if (screen !is Screen.AlbumSongs && screen !is Screen.ArtistSongs) {
            cachedDetailTitle = ""
            cachedDetailTitle2 = ""
            cachedDetailSubtitle = ""
            return
        }
        val title = when (screen) {
            is Screen.AlbumSongs -> screen.album
            is Screen.ArtistSongs -> screen.artist
            else -> ""
        }
        val maxWidth = (fallbackWidth() - 96f * density).coerceAtLeast(80f * density)
        boldPaint.textSize = Y2UiTheme.SECTION_TITLE_SP * density
        splitDetailTitle(title, maxWidth)
        var trackCount = 0
        var firstArtist: String? = null
        var multipleArtists = false
        val albums = if (screen is Screen.ArtistSongs) HashSet<String>() else null
        rows.forEach { row ->
            val track = (row as? ScreenRow.TrackRow)?.track ?: return@forEach
            trackCount += 1
            if (firstArtist == null) firstArtist = track.displayArtist
            else if (firstArtist != track.displayArtist) multipleArtists = true
            albums?.add(track.displayAlbum)
        }
        val detail = when (screen) {
            is Screen.AlbumSongs -> {
                val artist = if (multipleArtists) "Various artists" else firstArtist ?: "Unknown artist"
                "$artist · ${ScreenContent.trackCountLabel(trackCount)}"
            }
            is Screen.ArtistSongs -> {
                val albumCount = albums?.size ?: 0
                val albumLabel = if (albumCount == 1) "1 album" else "$albumCount albums"
                "$albumLabel · ${ScreenContent.trackCountLabel(trackCount)}"
            }
            else -> ""
        }
        paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        cachedDetailSubtitle = ellipsize(detail, maxWidth, paint)
    }

    private fun splitDetailTitle(title: String, maxWidth: Float) {
        if (title.isEmpty() || boldPaint.measureText(title) <= maxWidth) {
            cachedDetailTitle = title
            cachedDetailTitle2 = ""
            return
        }
        val fitEnd = Y2UiLogic.truncationEnd(title.length, maxWidth, 0f) { end -> boldPaint.measureText(title, 0, end) }
        val safeEnd = Y2UiLogic.safeTextBoundary(title, fitEnd).coerceAtLeast(1)
        val breakAt = title.lastIndexOf(' ', safeEnd - 1).takeIf { it > 0 } ?: safeEnd
        cachedDetailTitle = title.substring(0, breakAt).trimEnd()
        cachedDetailTitle2 = ellipsize(title.substring(breakAt).trimStart(), maxWidth, boldPaint)
    }

    /**
     * Wraps the Now Playing title onto up to two lines at a word boundary; the
     * second line ellipsizes. Runs only in the presentation cache, never in onDraw.
     * boldPaint must already carry the title text size.
     */
    private fun splitNowTitle(title: String, maxWidth: Float) {
        if (title.isEmpty() || boldPaint.measureText(title) <= maxWidth) {
            cachedNowTitle = title
            cachedNowTitle2 = ""
            return
        }
        val fitEnd = Y2UiLogic.truncationEnd(title.length, maxWidth, 0f) { prefix ->
            boldPaint.measureText(title, 0, prefix)
        }
        val safeEnd = Y2UiLogic.safeTextBoundary(title, fitEnd).coerceAtLeast(1)
        val breakAt = title.lastIndexOf(' ', safeEnd - 1).takeIf { it > 0 } ?: safeEnd
        cachedNowTitle = title.substring(0, breakAt).trimEnd()
        cachedNowTitle2 = ellipsize(title.substring(breakAt).trimStart(), maxWidth, boldPaint)
    }

    private fun fallbackWidth(): Float = (width.takeIf { it > 0 } ?: (Y2UiTheme.TARGET_WIDTH_PX * density).toInt()).toFloat()

    private fun fallbackHeight(): Float = (height.takeIf { it > 0 } ?: (Y2UiTheme.TARGET_HEIGHT_PX * density).toInt()).toFloat()

    /** Text width available on Now Playing; mirrors the wide/tall draw layouts. */
    private fun nowPlayingTextWidth(): Float {
        val w = fallbackWidth()
        val h = fallbackHeight()
        return if (w > h) {
            val contentHeight = h - headerHeight - footerHeight
            val margin = 14f * density
            val artSize = (contentHeight - 2 * margin).coerceAtMost(232f * density)
            (w - margin * 2 - artSize - 18f * density).coerceAtLeast(60f * density)
        } else {
            w - 24f * density
        }
    }

    private fun updateFooterPosition() {
        cachedFooterPosition = when {
            state.currentScreen != Screen.NowPlaying && rows.isNotEmpty() ->
                "${state.selectedIndex + 1}/${rows.size}"
            state.currentScreen == Screen.NowPlaying && state.playback.queue.isNotEmpty() ->
                state.playback.currentQueueIndex?.let { "${it + 1}/${state.playback.queue.size}" } ?: ""
            else -> ""
        }
    }

    private fun updateFooterHint() {
        val hint = when {
            state.currentScreen == Screen.NowPlaying -> "WHEEL VOLUME · L/R TRACK · HOLD L/R SEEK · HOLD CENTER OPTIONS"
            state.currentScreen == Screen.MainMenu && state.playback.currentTrackId != null -> "WHEEL NAVIGATE · RIGHT PLAYER · PLAY KEY TOGGLE"
            state.currentScreen == Screen.Brightness || state.currentScreen == Screen.ScreenTimeout ||
                state.currentScreen == Screen.SortOrder -> "WHEEL CHOOSE · CENTER APPLY"
            else -> "WHEEL NAVIGATE · CENTER SELECT"
        }
        paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
        paint.textAlign = Paint.Align.RIGHT
        val positionWidth = if (cachedFooterPosition.isEmpty()) 0f else paint.measureText(cachedFooterPosition) + 20f * density
        paint.textAlign = Paint.Align.LEFT
        cachedFooterHint = ellipsize(hint, fallbackWidth() - 20f * density - positionWidth, paint)
    }

    private fun updateMessageCache() {
        val message = state.transientMessage ?: state.library.errorMessage ?: state.playback.errorMessage
            ?: state.bluetooth.lastError ?: state.diagnostics.lastError
        cachedMessageSource = message
        if (message == null) {
            cachedMessageTitle = ""
            cachedMessageBody = ""
            return
        }
        when {
            state.playback.pauseReason == PauseReason.OUTPUT_DISCONNECTED -> {
                cachedMessageTitle = "Playback paused for safety"
                cachedMessageBody = "Private output disconnected; press Play to resume"
            }
            state.playback.errorMessage != null -> {
                cachedMessageTitle = "Playback issue"
                cachedMessageBody = friendlyMessage(message, "Choose another track or try again")
            }
            state.library.errorMessage != null -> {
                cachedMessageTitle = "Library needs attention"
                cachedMessageBody = friendlyMessage(message, "Check Storage and rescan when ready")
            }
            state.bluetooth.lastError != null -> {
                cachedMessageTitle = "Bluetooth issue"
                cachedMessageBody = friendlyMessage(message, "Check the device and try again")
            }
            else -> {
                cachedMessageTitle = "Status"
                cachedMessageBody = message
            }
        }
        paint.textSize = Y2UiTheme.META_SP * density
        cachedMessageBody = ellipsize(cachedMessageBody, fallbackWidth() - 64f * density, paint)
    }

    private fun friendlyMessage(message: String, fallback: String): String {
        if (message.length <= 72 && !message.contains("Exception") && !message.contains("java.")) return message
        return fallback
    }

    private fun buildTechnicalLine(codec: String, sampleRate: Int?, bitDepth: Int?): String {
        val rate = sampleRate?.takeIf { it > 0 }?.let { if (it % 1000 == 0) "${it / 1000} kHz" else "${it / 1000.0} kHz" }
        val depth = bitDepth?.takeIf { it > 0 }?.let { "$it-bit" }
        return when {
            rate != null && depth != null -> "$codec · $rate · $depth"
            rate != null -> "$codec · $rate"
            depth != null -> "$codec · $depth"
            else -> codec
        }
    }

    private fun requestArtwork() {
        val path = state.playback.currentTrackId?.let(state.library.byId::get)?.absolutePath
        if (path != artworkPath) {
            artworkPath = path
            artwork = null
            if (path != null) {
                // Same target size as LegacyRemoteControlController so both consumers share one
                // cache entry and one decode per track; drawBitmap scales at draw time.
                artworkLoader.load(path, SHARED_ARTWORK_SIZE_PX) { loadedPath, bitmap ->
                    if (loadedPath == artworkPath) {
                        artwork = bitmap
                        invalidate(0, headerHeight.toInt(), width, height)
                    }
                }
            }
        }
        requestDetailArtwork()
    }

    private fun requestDetailArtwork() {
        val detailPath = if (state.currentScreen is Screen.AlbumSongs) {
            (rows.firstOrNull { it is ScreenRow.TrackRow } as? ScreenRow.TrackRow)?.track?.absolutePath
        } else null
        if (detailPath == detailArtworkPath) return
        detailArtworkPath = detailPath
        detailArtwork = null
        if (detailPath == null) return
        artworkLoader.load(detailPath, SHARED_ARTWORK_SIZE_PX) { loadedPath, bitmap ->
            if (loadedPath == detailArtworkPath) {
                detailArtwork = bitmap
                invalidate(0, headerHeight.toInt(), width, rowAreaTop().toInt())
            }
        }
    }

    private fun ensureSelectionVisible() {
        if (rows.isEmpty()) {
            if (visibleStart != 0) invalidateRowCache()
            visibleStart = 0
            return
        }
        val nextStart = Y2UiLogic.firstVisibleRow(rows.size, state.selectedIndex, visibleStart, visibleRowCount())
        if (nextStart != visibleStart) {
            visibleStart = nextStart
            invalidateRowCache()
        }
    }

    /**
     * Clamped to the fixed row-cache size: the per-row caches are [MAX_VISIBLE_ROWS]
     * slots, and drawRows indexes them by (row - cachedRowStart). A panel tall
     * enough to fit more rows than the cache holds would otherwise overrun those
     * arrays. The target 480x360 panel fits four readable rows, so this only guards the
     * portrait-fallback geometry.
     */
    private fun visibleRowCount(): Int {
        val availableHeight = (rowAreaBottom() - rowAreaTop()).coerceAtLeast(0f)
        return Y2UiLogic.visibleRowCount(availableHeight, rowHeight).coerceAtMost(MAX_VISIBLE_ROWS)
    }

    private fun refreshVisibleRowCache() {
        val end = (visibleStart + visibleRowCount()).coerceAtMost(rows.size)
        if (cachedRowStart == visibleStart && cachedRowEnd == end) return
        cachedRowStart = visibleStart
        cachedRowEnd = end
        boldPaint.textSize = Y2UiTheme.ROW_TITLE_SP * density
        paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        for (slot in cachedTitles.indices) {
            if (visibleStart + slot < end) {
                val row = rows[visibleStart + slot]
                val active = isActive(row)
                val trailingText = (row as? ScreenRow.TrackRow)?.track?.durationMs
                    ?.takeIf { it > 0L }?.let(TimeFormat::duration)
                val trailingIcon = if (trailingText == null) trailingIconFor(row, active) else null
                val trailingWidth = when {
                    trailingText != null -> paint.measureText(trailingText) + 18f * density
                    trailingIcon != null -> 38f * density
                    else -> 14f * density
                }
                val maxWidth = (rowSurfaceRight() - 44f * density - trailingWidth).coerceAtLeast(36f * density)
                cachedTitles[slot] = ellipsize(row.title, maxWidth, boldPaint)
                cachedSubtitles[slot] = rowSubtitle(row)?.let { ellipsize(it, maxWidth, paint) }
                cachedIcons[slot] = iconFor(row)
                cachedTrailingText[slot] = trailingText
                cachedTrailingIcons[slot] = trailingIcon
                cachedTrackNumbers[slot] = if (state.currentScreen is Screen.AlbumSongs && row is ScreenRow.TrackRow) {
                    (row.track.trackNumber ?: (visibleStart + slot)).takeIf { it > 0 }?.toString()
                } else null
                cachedActive[slot] = active
                cachedUnavailable[slot] = isUnavailable(row)
            } else {
                cachedTitles[slot] = null
                cachedSubtitles[slot] = null
                cachedIcons[slot] = Y2Icon.INFO
                cachedTrailingText[slot] = null
                cachedTrailingIcons[slot] = null
                cachedTrackNumbers[slot] = null
                cachedActive[slot] = false
                cachedUnavailable[slot] = false
            }
        }
    }

    private fun invalidateRowCache() {
        cachedRowStart = -1
        cachedRowEnd = -1
    }

    private fun iconFor(row: ScreenRow): Y2Icon = when (row) {
        is ScreenRow.TrackRow -> if (row.track.id == state.playback.currentTrackId) Y2Icon.PLAYING else Y2Icon.SONG
        is ScreenRow.Folder -> Y2Icon.FOLDER
        is ScreenRow.Group -> when {
            // Content lists get content icons: an album row with an ⓘ icon reads
            // like an error dialog, not a library.
            state.currentScreen == Screen.Albums -> Y2Icon.ALBUM
            state.currentScreen == Screen.Artists -> Y2Icon.ARTIST
            row.key.contains("dac") -> Y2Icon.DAC
            row.key.contains("error") || row.key.contains("limit") || row.key.contains("unsupported") -> Y2Icon.WARNING
            else -> Y2Icon.INFO
        }
        is ScreenRow.Action -> iconForKey(row.key)
    }

    private fun iconForKey(key: String): Y2Icon = when {
        key == "collection_play" -> Y2Icon.PLAY
        key == "rescan" || key == "bt_refresh" -> Y2Icon.REFRESH
        key == "songs" -> Y2Icon.SONG
        key == "favorites" || key.contains("favorite") -> Y2Icon.FAVORITE
        key == "recent" || key == "playlist_recent" -> Y2Icon.RECENT
        key == "albums" || key == "np_album" -> Y2Icon.ALBUM
        key == "artists" || key == "np_artist" -> Y2Icon.ARTIST
        key.startsWith("np_playlist") -> Y2Icon.PLAYLIST
        key == "folders" -> Y2Icon.FOLDER
        key == "now_playing" || key.startsWith("track_play") || key.startsWith("queue_play") -> Y2Icon.PLAYING
        key == "queue" || key.contains("queue") -> Y2Icon.QUEUE
        key == "bluetooth" || key.startsWith("bt_") -> Y2Icon.BLUETOOTH
        key == "settings" || key == "system" || key == "playback" || key.contains("effects") || key.startsWith("eq_") -> Y2Icon.SETTINGS
        key == "storage" || key.startsWith("storage:") || key == "rescan" -> Y2Icon.STORAGE
        key == "display" || key.startsWith("brightness") || key.startsWith("timeout") || key == "keep_screen_on" -> Y2Icon.DISPLAY
        key == "diagnostics" || key.startsWith("diag_") -> Y2Icon.DIAGNOSTICS
        key == "sort" || key.startsWith("sort:") -> Y2Icon.SORT
        // Import/export are both playlist operations; without this arm they split
        // into ADD vs PLAYLIST icons and read as unrelated actions.
        key == "playlist_import_m3u" || key == "playlist_export_m3u" -> Y2Icon.PLAYLIST
        key.contains("sleep_timer") -> Y2Icon.TIMER
        key.contains("shuffle") -> Y2Icon.SHUFFLE
        key.contains("repeat") -> Y2Icon.REPEAT
        key.contains("audio_quality") -> Y2Icon.DAC
        key.contains("create") || key.contains("add") || key.contains("import") -> Y2Icon.ADD
        key.startsWith("playlist") -> Y2Icon.PLAYLIST
        else -> Y2Icon.ACTION
    }

    private fun rowSubtitle(row: ScreenRow): String? = when {
        row !is ScreenRow.TrackRow -> row.subtitle
        state.currentScreen is Screen.AlbumSongs -> null
        state.currentScreen is Screen.ArtistSongs -> row.track.displayAlbum
        else -> row.subtitle
    }

    private fun trailingIconFor(row: ScreenRow, active: Boolean): Y2Icon? {
        val action = row as? ScreenRow.Action ?: return null
        if (active) return Y2Icon.CHECK
        val key = action.key
        return if (key in NAVIGATION_KEYS || key.startsWith("storage:") || key.startsWith("playlist:") ||
            key.startsWith("np_album") || key.startsWith("np_artist") || key.startsWith("track_playlist")
        ) Y2Icon.CHEVRON else null
    }

    private fun isActive(row: ScreenRow): Boolean {
        if (row is ScreenRow.TrackRow) return row.track.id == state.playback.currentTrackId &&
            (state.playback.status == PlaybackStatus.PLAYING || state.playback.status == PlaybackStatus.PREPARING)
        val action = row as? ScreenRow.Action ?: return false
        return when {
            action.key == "collection_play" -> state.playback.currentTrackId?.let { currentId ->
                rows.any { (it as? ScreenRow.TrackRow)?.track?.id == currentId }
            } == true
            action.key == "now_playing" -> state.playback.currentTrackId != null
            action.key == "shuffle" -> state.playback.shuffleEnabled
            action.key == "repeat" -> state.playback.repeatMode != RepeatMode.OFF
            action.key == "gapless" -> state.preferences.gaplessEnabled && state.preferences.crossfadeMs == 0
            action.key == "pause_disconnect" -> !state.preferences.pauseOnDisconnect
            action.key == "resume_position" -> state.preferences.resumePosition
            action.key == "keep_screen_on" -> state.preferences.keepScreenOnWhilePlaying
            action.key == "effects_toggle" -> state.preferences.audioEffectsEnabled
            action.key == "audio_quality" -> state.preferences.audioQualityMode == AudioQualityMode.DIRECT_DAC
            action.key == "bt_toggle" -> state.bluetooth.adapterMode == BluetoothAdapterMode.ON
            action.key.startsWith("bt_device:") -> state.bluetooth.devices.any {
                "bt_device:${it.address}" == action.key && (it.audioStreaming || it.linkState == BluetoothLinkState.CONNECTED)
            }
            action.key.startsWith("sort:") -> action.key.substringAfter(':') == state.preferences.sortOrder.storageId
            action.key.startsWith("brightness:") -> action.key.substringAfter(':').toIntOrNull()?.let {
                kotlin.math.abs(state.display.brightnessPercent - it) <= 5
            } == true
            action.key.startsWith("timeout:") -> action.key.substringAfter(':').toIntOrNull() == state.display.screenTimeoutMs
            else -> false
        }
    }

    private fun isUnavailable(row: ScreenRow): Boolean {
        if (row is ScreenRow.TrackRow) return !row.track.available
        val subtitle = row.subtitle ?: return false
        return subtitle.contains("unavailable", ignoreCase = true) ||
            subtitle.contains("not mounted", ignoreCase = true) ||
            subtitle.contains("unsupported", ignoreCase = true)
    }

    private fun routeIcon(icon: RouteIcon): Y2Icon = when (icon) {
        RouteIcon.HEADPHONES -> Y2Icon.HEADPHONES
        RouteIcon.BLUETOOTH -> Y2Icon.BLUETOOTH
        RouteIcon.SPEAKER -> Y2Icon.SPEAKER
        RouteIcon.DISCONNECTED -> Y2Icon.DISCONNECTED
        RouteIcon.UNKNOWN -> Y2Icon.UNKNOWN
    }

    private fun ellipsize(text: String, maxWidth: Float, targetPaint: Paint): String {
        val safeWidth = maxWidth.coerceAtLeast(0f)
        if (text.isEmpty() || targetPaint.measureText(text) <= safeWidth) return text
        val suffix = "…"
        val suffixWidth = targetPaint.measureText(suffix)
        if (suffixWidth > safeWidth) return ""
        val measuredEnd = Y2UiLogic.truncationEnd(text.length, safeWidth, suffixWidth) { prefix ->
            targetPaint.measureText(text, 0, prefix)
        }
        val end = Y2UiLogic.safeTextBoundary(text, measuredEnd)
        return text.substring(0, end) + suffix
    }

    private fun buildContentDescription(value: AppState): String {
        val selected = rows.getOrNull(value.selectedIndex)
        return buildString {
            append(ScreenContent.title(value))
            selected?.let {
                append(", selected ${it.title}")
                it.subtitle?.let { subtitle -> append(", $subtitle") }
            }
            append(", output ${cachedRoute.label}")
        }
    }

    companion object {
        private const val MAX_VISIBLE_ROWS = 12
        const val SHARED_ARTWORK_SIZE_PX = 256
        private val NAVIGATION_KEYS = setOf(
            "songs", "albums", "artists", "playlists", "folders", "settings",
            "playlist_favorites", "playlist_recent", "playback", "sort", "bluetooth",
            "display", "storage", "system", "diagnostics", "android_settings", "about",
            "sound", "brightness", "timeout", "queue"
        )
    }
}
