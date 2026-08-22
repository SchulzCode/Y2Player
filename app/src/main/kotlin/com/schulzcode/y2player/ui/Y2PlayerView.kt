package com.schulzcode.y2player.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import com.schulzcode.y2player.artwork.AlbumArtworkLoader
import com.schulzcode.y2player.core.model.AudioCodecLabels
import com.schulzcode.y2player.core.model.PauseReason
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.SleepTimerMode
import com.schulzcode.y2player.core.state.AppAction
import com.schulzcode.y2player.core.state.AppState
import com.schulzcode.y2player.core.state.Screen
import com.schulzcode.y2player.core.state.ScreenContent
import com.schulzcode.y2player.core.state.ScreenRow
import com.schulzcode.y2player.core.state.SleepTimerPresentation
import com.schulzcode.y2player.core.state.isRadialMenu
import com.schulzcode.y2player.core.state.isProgressOnlyUpdate
import com.schulzcode.y2player.util.TimeFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@SuppressLint("ViewConstructor")
@Suppress("DEPRECATION")
class Y2PlayerView(
    context: Context,
    private val dispatch: (AppAction) -> Unit,
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
    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    private val reusableRect = Rect()
    private val reusableRectF = RectF()
    private val textScroller = SelectedTextScroller(MARQUEE_SPEED_DP_PER_SECOND * density)
    private val nowTextScrollers = Array(3) {
        SelectedTextScroller(MARQUEE_SPEED_DP_PER_SECOND * density)
    }
    private var nowScrollTrackId: Long? = null
    private var textAnimationsVisible = false
    private var attached = false
    private var textScrollCallbackScheduled = false
    private val textScrollRunnable = object : Runnable {
        override fun run() {
            textScrollCallbackScheduled = false
            val delay = advanceActiveTextScrollers(SystemClock.uptimeMillis())
            if (delay == SelectedTextScroller.NO_CALLBACK) return
            invalidateTextScrollTarget()
            scheduleTextScroll(delay)
        }
    }
    private var sleepTimerCountdownCallbackScheduled = false
    private val sleepTimerCountdownRunnable = object : Runnable {
        override fun run() {
            sleepTimerCountdownCallbackScheduled = false
            if (!shouldRefreshSleepTimerCountdown()) return
            refreshSleepTimerCountdownRows()
            updateSleepTimerCountdownActivity(refreshRows = false)
        }
    }

    private var state: AppState = AppState()

    private var palette: Y2Palette = Y2Palette.DARK

    private fun applyPalette(lightTheme: Boolean): Boolean {
        val next = Y2Palette.of(lightTheme)
        if (next == palette) return false
        palette = next
        return true
    }
    private var rows = rowsForState(state)
    private var artwork: Bitmap? = null
    private var artworkPath: String? = null
    private var artworkIdentity: Triple<Long, Long, Long>? = null
    private var detailArtwork: Bitmap? = null
    private var detailArtworkPath: String? = null
    private var visibleStart = 0
    private val rowHeight = Y2UiTheme.ROW_HEIGHT_DP * density
    private val headerHeight = Y2UiTheme.HEADER_HEIGHT_DP * density
    private val detailHeaderHeight = Y2UiTheme.DETAIL_HEADER_HEIGHT_DP * density
    private val footerHeight: Float
        get() = when {
            isSplitHome() -> 0f

            isNowPlayingSurface() ->
                Y2UiTheme.COMPACT_FOOTER_HEIGHT_DP * density

            state.playback.currentTrackId != null ->
                Y2UiTheme.MINI_PLAYER_HEIGHT_DP * density

            else ->
                Y2UiTheme.COMPACT_FOOTER_HEIGHT_DP * density
        }
    private var pendingTouchIndex: Int? = null
    private var pendingTouchActive = false
    private var radialAnimationStartedAt = 0L
    private var radialAnimationOpening = false
    private var radialAnimationActive = false
    private var closingRadialState: AppState? = null
    private var closingRadialRows: List<ScreenRow> = emptyList()

    private val cachedTitles = arrayOfNulls<String>(MAX_VISIBLE_ROWS)
    private val cachedSubtitles = arrayOfNulls<String>(MAX_VISIBLE_ROWS)
    private val cachedIcons = Array(MAX_VISIBLE_ROWS) { Y2Icon.INFO }
    private val cachedTrailingIcons = arrayOfNulls<Y2Icon>(MAX_VISIBLE_ROWS)
    private val cachedTrailingText = arrayOfNulls<String>(MAX_VISIBLE_ROWS)
    private val cachedTrackNumbers = arrayOfNulls<String>(MAX_VISIBLE_ROWS)
    private val cachedTitleWidths = FloatArray(MAX_VISIBLE_ROWS)
    private val cachedTitleAvailableWidths = FloatArray(MAX_VISIBLE_ROWS)
    private val cachedActive = BooleanArray(MAX_VISIBLE_ROWS)
    private val cachedUnavailable = BooleanArray(MAX_VISIBLE_ROWS)
    private val visibleRowArtworks = HashMap<Int, VisibleRowArtworkSlot>(MAX_VISIBLE_ROWS)
    private var visibleRowArtworkGeneration = 0L
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
    private var cachedNowTitleFull = "Nothing playing"
    private var cachedNowTitleWidth = 0f
    private var cachedNowArtist = "Select a track"
    private var cachedNowArtistFull = "Select a track"
    private var cachedNowArtistWidth = 0f
    private var cachedNowAlbum = ""
    private var cachedNowAlbumFull = ""
    private var cachedNowAlbumWidth = 0f
    private var cachedNowGenre = ""
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
    private var cachedScanProgressFraction: Float? = null
    private var cachedScanProgressPhase = 0f
    private var cachedDetailTitle = ""
    private var cachedDetailTitle2 = ""
    private var cachedDetailSubtitle = ""

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updatePresentationCache()
    }

    fun setTextAnimationsVisible(visible: Boolean) {
        if (textAnimationsVisible == visible) return
        textAnimationsVisible = visible
        updateTextScrollActivity()
        updateSleepTimerCountdownActivity()
    }

    fun onNavigationInput() {
        val now = SystemClock.uptimeMillis()
        val hasTarget = if (isNowPlayingSurface()) {
            var found = false
            for (scroller in nowTextScrollers) {
                if (scroller.hasTarget) scroller.restart(now)
                found = found || scroller.hasTarget
            }
            found
        } else {
            if (textScroller.hasTarget) textScroller.restart(now)
            textScroller.hasTarget
        }
        if (!hasTarget) return
        rescheduleTextScroll()
        invalidateTextScrollTarget()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        updateTextScrollActivity()
        updateSleepTimerCountdownActivity()
        requestVisibleRowArtworks()
    }

    override fun onDetachedFromWindow() {
        attached = false
        cancelVisibleRowArtworkRequests()
        stopTextScrollCallbacks()
        stopSleepTimerCountdownCallback()
        setTextScrollersActive(false, SystemClock.uptimeMillis())
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateTextScrollActivity()
        updateSleepTimerCountdownActivity()
        if (visibility == VISIBLE) requestVisibleRowArtworks() else cancelVisibleRowArtworkRequests()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateTextScrollActivity()
        updateSleepTimerCountdownActivity()
        if (visibility == VISIBLE) requestVisibleRowArtworks() else cancelVisibleRowArtworkRequests()
    }

    fun render(newState: AppState) {
        val oldState = state
        val openingRadial = !oldState.currentScreen.isRadialMenu() &&
            newState.currentScreen.isRadialMenu()
        val closingRadial = oldState.currentScreen.isRadialMenu() &&
            !newState.currentScreen.isRadialMenu()
        if (closingRadial) {
            closingRadialState = oldState
            closingRadialRows = rowsForState(oldState)
        }
        when {
            openingRadial -> startRadialAnimation(opening = true)
            closingRadial -> startRadialAnimation(opening = false)
        }
        val themeChanged = applyPalette(newState.preferences.lightTheme)
        val progressOnly = !themeChanged && isProgressOnlyUpdate(oldState.playback, newState.playback) &&
            oldState.copy(playback = newState.playback) == newState
        val sameScreenPath = oldState.screenStack.size == newState.screenStack.size &&
            oldState.screenStack.indices.all { oldState.screenStack[it].screen == newState.screenStack[it].screen }
        val selectionOnly = !themeChanged && sameScreenPath &&
            oldState.copy(screenStack = newState.screenStack) == newState

        state = newState
        updateSleepTimerCountdownActivity(refreshRows = false)
        if (selectionOnly) {
            if (ensureSelectionVisible()) requestVisibleRowArtworks()
            updateFooterPosition()
            updateContentDescription(newState)
            invalidate(0, headerHeight.toInt(), width, height)
            return
        }
        if (progressOnly) {
            cachedElapsed = TimeFormat.duration(newState.playback.positionMs)
            cachedDuration = TimeFormat.duration(newState.playback.durationMs)
            when (newState.currentScreen) {
                Screen.NowPlaying -> invalidate(0, headerHeight.toInt(), width, (height - footerHeight).toInt())
                Screen.MainMenu -> if (isSplitHome() && newState.playback.currentTrackId != null) {
                    invalidate(rowAreaRight().toInt(), headerHeight.toInt(), width, (height - footerHeight).toInt())
                } else if (newState.playback.currentTrackId != null) {
                    invalidate(0, (height - footerHeight).toInt(), width, height)
                }
                Screen.NowPlayingOptions, Screen.QueueManagement, is Screen.QueueOptions -> {
                    if (oldState.playback.sleepTimerRemainingMs != newState.playback.sleepTimerRemainingMs) {
                        rows = rowsForState(newState)
                        invalidateRowCache()
                    }
                    invalidate()
                }
                else -> if (newState.playback.currentTrackId != null) {
                    invalidate(0, (height - footerHeight).toInt(), width, height)
                }
            }
            return
        }

        rows = rowsForState(newState)
        requestArtwork()
        updatePresentationCache()
        invalidateRowCache()
        ensureSelectionVisible()
        requestVisibleRowArtworks()
        updateContentDescription(newState)
        invalidate()
    }

    fun trimMemory() {
        artwork = null
        artworkPath = null
        artworkIdentity = null
        detailArtwork = null
        detailArtworkPath = null
        cancelVisibleRowArtworkRequests()
        artworkLoader.trimMemory()
        invalidate()
    }

    fun release() {
        stopTextScrollCallbacks()
        textScroller.clear()
        for (scroller in nowTextScrollers) scroller.clear()
        nowScrollTrackId = null
        artwork = null
        artworkPath = null
        artworkIdentity = null
        detailArtwork = null
        detailArtworkPath = null
        cancelVisibleRowArtworkRequests()
        stopSleepTimerCountdownCallback()
        radialAnimationActive = false
        closingRadialState = null
        closingRadialRows = emptyList()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        invalidateRowCache()
        updatePresentationCache()
        ensureSelectionVisible()
        requestVisibleRowArtworks()
    }

    private fun updateTextScrollActivity() {
        val active = attached && textAnimationsVisible &&
            windowVisibility == VISIBLE && visibility == VISIBLE
        val changed = setTextScrollersActive(active, SystemClock.uptimeMillis())
        stopTextScrollCallbacks()
        if (active) rescheduleTextScroll()
        if (changed) invalidateTextScrollTarget()
    }

    private fun rowsForState(value: AppState): List<ScreenRow> {
        if (value.currentScreen != Screen.NowPlayingOptions) return ScreenContent.rows(value)
        val remaining = SleepTimerPresentation.remainingMs(value.playback, SystemClock.elapsedRealtime())
            ?: return ScreenContent.rows(value)
        return ScreenContent.rows(value.copy(
            playback = value.playback.copy(sleepTimerRemainingMs = remaining)
        ))
    }

    private fun shouldRefreshSleepTimerCountdown(): Boolean {
        val uiVisible = attached && textAnimationsVisible &&
            windowVisibility == VISIBLE && visibility == VISIBLE
        return SleepTimerPresentation.shouldRefresh(
            state.currentScreen,
            state.playback.sleepTimerMode,
            state.playback.sleepTimerDeadlineElapsedMs,
            uiVisible
        )
    }

    private fun updateSleepTimerCountdownActivity(refreshRows: Boolean = true) {
        if (!shouldRefreshSleepTimerCountdown()) {
            stopSleepTimerCountdownCallback()
            return
        }
        if (refreshRows) refreshSleepTimerCountdownRows()
        if (sleepTimerCountdownCallbackScheduled) return
        sleepTimerCountdownCallbackScheduled = true
        postDelayed(sleepTimerCountdownRunnable, SLEEP_TIMER_REFRESH_MS)
    }

    private fun refreshSleepTimerCountdownRows() {
        val updated = rowsForState(state)
        if (updated == rows) return
        rows = updated
        invalidateRowCache()
        updateContentDescription(state)
        invalidate()
    }

    private fun stopSleepTimerCountdownCallback() {
        if (!sleepTimerCountdownCallbackScheduled) return
        removeCallbacks(sleepTimerCountdownRunnable)
        sleepTimerCountdownCallbackScheduled = false
    }

    private fun rescheduleTextScroll() {
        stopTextScrollCallbacks()
        val delay = advanceActiveTextScrollers(SystemClock.uptimeMillis())
        if (delay != SelectedTextScroller.NO_CALLBACK) scheduleTextScroll(delay)
    }

    private fun setTextScrollersActive(active: Boolean, nowMs: Long): Boolean {
        var changed = textScroller.setActive(active, nowMs)
        for (scroller in nowTextScrollers) changed = scroller.setActive(active, nowMs) || changed
        return changed
    }

    private fun advanceActiveTextScrollers(nowMs: Long): Long {
        if (!isNowPlayingSurface()) return textScroller.advance(nowMs)
        var delay = SelectedTextScroller.NO_CALLBACK
        for (scroller in nowTextScrollers) {
            delay = earlierScrollDelay(delay, scroller.advance(nowMs))
        }
        return delay
    }

    private fun earlierScrollDelay(first: Long, second: Long): Long = when {
        first == SelectedTextScroller.NO_CALLBACK -> second
        second == SelectedTextScroller.NO_CALLBACK -> first
        else -> minOf(first, second)
    }

    private fun scheduleTextScroll(delayMs: Long) {
        if (textScrollCallbackScheduled) return
        textScrollCallbackScheduled = true
        if (delayMs == SelectedTextScroller.NEXT_FRAME) {
            // Ten updates per second remain readable on the 480x360 panel without
            // making the single Canvas view compete with audio decoding for CPU.
            postDelayed(textScrollRunnable, MARQUEE_FRAME_MS)
        } else {
            postDelayed(textScrollRunnable, delayMs.coerceAtLeast(1L))
        }
    }

    private fun stopTextScrollCallbacks() {
        if (!textScrollCallbackScheduled) return
        removeCallbacks(textScrollRunnable)
        textScrollCallbackScheduled = false
    }

    private fun invalidateTextScrollTarget() {
        if (isNowPlayingSurface()) {
            invalidate(0, headerHeight.toInt(), width, (height - footerHeight).toInt())
            return
        }
        when (textScroller.kind) {
            SelectedTextScroller.TARGET_LIST -> {
                val index = textScroller.index
                if (index in cachedRowStart until cachedRowEnd) {
                    val top = rowAreaTop() + (index - visibleStart) * rowHeight
                    invalidate(0, top.toInt(), rowAreaRight().toInt(), (top + rowHeight).toInt())
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(palette.background)
        drawHeader(canvas)
        if (isNowPlayingSurface()) {
            drawNowPlaying(canvas)
        } else {
            if (hasDetailHeader()) drawDetailHeader(canvas)
            drawRows(canvas)
        }
        if (isSplitHome()) drawHomePane(canvas)
        drawFooter(canvas)
        drawStatusMessage(canvas)
        if (state.currentScreen.isRadialMenu()) {
            drawRadialOptionsOverlay(canvas, state, rows, radialAnimationProgress())
        } else {
            val closingState = closingRadialState
            if (closingState != null) {
                drawRadialOptionsOverlay(canvas, closingState, closingRadialRows, radialAnimationProgress())
            }
        }
    }

    private fun isNowPlayingSurface(): Boolean =
        state.currentScreen == Screen.NowPlaying || state.currentScreen.isRadialMenu()

    private fun isSplitHome(): Boolean = state.currentScreen == Screen.MainMenu && width > height

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
                    if (state.currentScreen.isRadialMenu()) {
                        pendingTouchIndex = null
                        performClick()
                        return true
                    }
                    if (isSplitHome() && event.x >= rowAreaRight() && event.y >= headerHeight && event.y < height - footerHeight) {
                        pendingTouchIndex = null
                        dispatch(AppAction.ShowNowPlaying)
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
        if (state.currentScreen == Screen.NowPlaying || state.currentScreen.isRadialMenu()) {
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

    private fun drawHeader(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = palette.surface
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
                palette.primaryText
            )
            canvas.restoreToCount(save)
        }

        boldPaint.color = palette.primaryText
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
                paint.color = palette.warning
                canvas.drawText("SAFE", markerX, 29f * density, paint)
            } else {
                paint.color = palette.accent
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
                    palette.warning
                } else {
                    palette.accent
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

    private fun drawBattery(canvas: Canvas, rightEdge: Float): Float {
        val color = when {
            state.device.charging -> palette.success
            (cachedBatteryPercent ?: 100) <= 15 -> palette.warning
            else -> palette.secondaryText
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = Y2UiTheme.BADGE_SP * density
        paint.color = color
        val textWidth = if (cachedBatteryText.isEmpty()) 0f else {
            canvas.drawText(cachedBatteryText, rightEdge, 28f * density, paint)
            paint.measureText(BATTERY_TEXT_REFERENCE) + 5f * density
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

    private fun drawRows(canvas: Canvas) {
        if (rows.isEmpty()) {
            drawEmptyState(canvas)
            return
        }
        refreshVisibleRowCache()
        prepareListTextScrollTarget()
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
                RowVisualState.FOCUSED, RowVisualState.FOCUSED_ACTIVE, RowVisualState.ACTIVE -> palette.accent
                RowVisualState.UNAVAILABLE -> palette.mutedText
                RowVisualState.NORMAL -> palette.secondaryText
            }
            val rowArtwork = visibleRowArtworks[index]?.bitmap
            val trackNumber = cachedTrackNumbers[slot]
            if (rowArtwork != null) {
                drawArtwork(
                    canvas,
                    ROW_ARTWORK_LEFT_DP * density,
                    top + (rowHeight - ROW_ARTWORK_SIZE_DP * density) * .5f,
                    ROW_ARTWORK_SIZE_DP * density,
                    rowArtwork
                )
            } else if (trackNumber != null && !cachedActive[slot]) {
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
                RowVisualState.UNAVAILABLE -> palette.mutedText
                RowVisualState.ACTIVE -> palette.accent
                else -> palette.primaryText
            }
            val titleBaseline = if (subtitle == null) top + rowHeight * .5f + 6f * density else top + 24f * density
            val fullTitle = rows[index].title
            if (focused && textScroller.isTarget(
                    SelectedTextScroller.TARGET_LIST,
                    state.currentScreen,
                    index,
                    fullTitle
                ) && textScroller.drawsFullText
            ) {
                drawScrollingText(
                    canvas,
                    fullTitle,
                    50f * density,
                    50f * density + cachedTitleAvailableWidths[slot],
                    titleBaseline,
                    boldPaint,
                    textScroller.offsetPx
                )
            } else {
                canvas.drawText(cachedTitles[slot].orEmpty(), 50f * density, titleBaseline, boldPaint)
            }
            if (subtitle != null) {
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
                paint.color = if (visual == RowVisualState.UNAVAILABLE) palette.mutedText else palette.secondaryText
                canvas.drawText(subtitle, 50f * density, top + 45f * density, paint)
            }

            val surfaceRight = rowSurfaceRight()
            cachedTrailingText[slot]?.let { trailing ->
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.RIGHT
                paint.textSize = Y2UiTheme.META_SP * density
                paint.color = if (visual == RowVisualState.UNAVAILABLE) palette.mutedText else palette.secondaryText
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

    private fun prepareListTextScrollTarget() {
        var changed = false
        for (scroller in nowTextScrollers) changed = scroller.clear() || changed
        nowScrollTrackId = null
        val selected = state.selectedIndex
        val slot = selected - cachedRowStart
        val row = rows.getOrNull(selected)
        changed = (if (row == null || slot !in cachedTitles.indices) {
            textScroller.clear()
        } else {
            textScroller.setTarget(
                SelectedTextScroller.TARGET_LIST,
                state.currentScreen,
                selected,
                row.title,
                cachedTitleWidths[slot],
                cachedTitleAvailableWidths[slot],
                SystemClock.uptimeMillis()
            )
        }) || changed
        if (changed) rescheduleTextScroll()
    }

    private fun prepareNowPlayingTextScrollTarget() {
        val available = nowPlayingTextWidth()
        val now = SystemClock.uptimeMillis()
        var changed = textScroller.clear()
        val trackId = state.playback.currentTrackId
        if (nowScrollTrackId != trackId) {
            nowScrollTrackId = trackId
            for (scroller in nowTextScrollers) changed = scroller.clear() || changed
        }
        changed = nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_TITLE).setTarget(
            SelectedTextScroller.TARGET_NOW_TITLE, Screen.NowPlaying, 0,
            cachedNowTitleFull, cachedNowTitleWidth, available, now
        ) || changed
        changed = nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_ARTIST).setTarget(
            SelectedTextScroller.TARGET_NOW_ARTIST, Screen.NowPlaying, 0,
            cachedNowArtistFull, cachedNowArtistWidth, available, now
        ) || changed
        changed = nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_ALBUM).setTarget(
            SelectedTextScroller.TARGET_NOW_ALBUM, Screen.NowPlaying, 0,
            cachedNowAlbumFull, cachedNowAlbumWidth, available, now
        ) || changed
        if (changed) rescheduleTextScroll()
    }

    private fun nowPlayingTextScroller(kind: Int): SelectedTextScroller = when (kind) {
        in SelectedTextScroller.TARGET_NOW_TITLE..SelectedTextScroller.TARGET_NOW_ALBUM ->
            nowTextScrollers[kind - SelectedTextScroller.TARGET_NOW_TITLE]
        else -> error("Invalid Now Playing marquee target")
    }

    private fun isNowPlayingScrollTarget(kind: Int, text: String): Boolean {
        val scroller = nowPlayingTextScroller(kind)
        return scroller.drawsFullText && scroller.isTarget(kind, Screen.NowPlaying, 0, text)
    }

    private fun drawScrollingText(
        canvas: Canvas,
        text: String,
        clipLeft: Float,
        clipRight: Float,
        baseline: Float,
        targetPaint: Paint,
        offsetPx: Float
    ) {
        val alignment = targetPaint.textAlign
        val saveCount = canvas.save()
        canvas.clipRect(clipLeft, baseline - targetPaint.textSize, clipRight, baseline + 4f * density)
        targetPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(text, clipLeft - offsetPx, baseline, targetPaint)
        targetPaint.textAlign = alignment
        canvas.restoreToCount(saveCount)
    }

    private fun drawFocus(canvas: Canvas, top: Float, bottom: Float) {
        val left = Y2UiTheme.ROW_FOCUS_INSET_DP * density
        val right = rowSurfaceRight()

        reusableRectF.set(
            left,
            top + 2.5f * density,
            right,
            bottom - 2.5f * density
        )

        paint.style = Paint.Style.FILL
        paint.color = palette.focusSurface

        canvas.drawRoundRect(
            reusableRectF,
            Y2UiTheme.ROW_RADIUS_DP * density,
            Y2UiTheme.ROW_RADIUS_DP * density,
            paint
        )

        val barWidth = Y2UiTheme.FOCUS_BAR_WIDTH_DP * density

        reusableRectF.set(
            left,
            top + 9f * density,
            left + barWidth,
            bottom - 9f * density
        )

        paint.color = palette.accent

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
        paint.color = palette.activeSurface
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(reusableRectF, Y2UiTheme.ROW_RADIUS_DP * density, Y2UiTheme.ROW_RADIUS_DP * density, paint)
    }

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
        paint.color = palette.divider
        val trackRight = right - Y2UiTheme.SCROLLBAR_END_INSET_DP * density
        val trackLeft = trackRight - Y2UiTheme.SCROLLBAR_WIDTH_DP * density
        reusableRectF.set(trackLeft, trackTop, trackRight, trackBottom)
        canvas.drawRoundRect(reusableRectF, density, density, paint)
        val thumbHeight = (trackHeight * visibleCount / rows.size.toFloat()).coerceAtLeast(24f * density)
        val maxStart = (rows.size - visibleCount).coerceAtLeast(1)
        val thumbTop = trackTop + (trackHeight - thumbHeight) * (visibleStart.toFloat() / maxStart)
        paint.color = palette.accent
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
            recentScreen = state.currentScreen == Screen.RecentlyPlayed,
            audiobooksScreen = state.currentScreen == Screen.Audiobooks,
            drilldownScreen = state.currentScreen is Screen.AlbumSongs ||
                state.currentScreen is Screen.ArtistSongs ||
                state.currentScreen is Screen.ArtistAlbums ||
                state.currentScreen is Screen.AudiobookChapters
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
            EmptyStateKind.EMPTY_LIST -> {
                title = "Nothing here"
                detail = "These files are no longer on the card"
                icon = Y2Icon.WARNING
            }
            EmptyStateKind.EMPTY_AUDIOBOOKS -> {
                title = "No audiobooks found"
                detail = "Put books inside an AUDIOBOOKS folder"
                icon = Y2Icon.BOOK
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
        paint.color = palette.surfaceRaised
        canvas.drawCircle(centerX, centerY - 34f * density, 26f * density, paint)
        iconPainter.draw(canvas, icon, centerX, centerY - 34f * density, 27f * density, if (kind == EmptyStateKind.STORAGE_MISSING) palette.warning else palette.accent)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = Y2UiTheme.SECTION_TITLE_SP * density
        boldPaint.color = palette.primaryText
        canvas.drawText(title, centerX, centerY + 12f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = Y2UiTheme.BODY_SP * density
        paint.color = palette.secondaryText
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
            paint.color = palette.focusSurface
            canvas.drawRoundRect(reusableRectF, 8f * density, 8f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Y2UiTheme.FOCUS_OUTLINE_DP * density
            paint.color = palette.accent
            canvas.drawRoundRect(reusableRectF, 8f * density, 8f * density, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
            paint.color = palette.primaryText
            canvas.drawText(action, centerX, centerY + 61f * density, paint)
        }
        paint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawDetailHeader(canvas: Canvas) {
        val top = headerHeight
        val bottom = top + detailHeaderHeight
        paint.style = Paint.Style.FILL
        paint.color = palette.surface
        canvas.drawRect(0f, top, width.toFloat(), bottom, paint)

        val artSize = 60f * density
        val artLeft = Y2UiTheme.EDGE_DP * density
        val artTop = top + 8f * density
        val fallback = if (state.currentScreen is Screen.ArtistSongs) Y2Icon.ARTIST else Y2Icon.ALBUM
        drawArtwork(canvas, artLeft, artTop, artSize, detailArtwork, fallback)

        val textLeft = artLeft + artSize + 12f * density
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.SECTION_TITLE_SP * density
        boldPaint.color = palette.primaryText
        canvas.drawText(cachedDetailTitle, textLeft, top + 25f * density, boldPaint)
        if (cachedDetailTitle2.isNotEmpty()) {
            canvas.drawText(cachedDetailTitle2, textLeft, top + 44f * density, boldPaint)
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.ROW_SUBTITLE_SP * density
        paint.color = palette.secondaryText
        canvas.drawText(cachedDetailSubtitle, textLeft, if (cachedDetailTitle2.isEmpty()) top + 51f * density else top + 66f * density, paint)
    }

    private fun drawNowPlaying(canvas: Canvas) {
        prepareNowPlayingTextScrollTarget()
        if (Y2UiLogic.playerLayout(width, height) == PlayerLayout.WIDE) drawNowPlayingWide(canvas) else drawNowPlayingTall(canvas)
    }

    private fun startRadialAnimation(opening: Boolean) {
        radialAnimationOpening = opening
        radialAnimationStartedAt = SystemClock.uptimeMillis()
        radialAnimationActive = true
        if (opening) {
            closingRadialState = null
            closingRadialRows = emptyList()
        }
        postInvalidateOnAnimation()
    }

    private fun radialAnimationProgress(): Float {
        if (!radialAnimationActive) {
            return if (state.currentScreen.isRadialMenu()) 1f else 0f
        }
        val duration = if (radialAnimationOpening) RADIAL_OPEN_MS else RADIAL_CLOSE_MS
        val raw = ((SystemClock.uptimeMillis() - radialAnimationStartedAt).toFloat() / duration)
            .coerceIn(0f, 1f)
        val eased = raw * raw * (3f - 2f * raw)
        if (raw < 1f) {
            postInvalidateOnAnimation()
        } else {
            radialAnimationActive = false
            if (!radialAnimationOpening) {
                closingRadialState = null
                closingRadialRows = emptyList()
            }
        }
        return if (radialAnimationOpening) eased else 1f - eased
    }

    private fun drawRadialOptionsOverlay(
        canvas: Canvas,
        renderState: AppState,
        optionRows: List<ScreenRow>,
        progress: Float
    ) {
        if (progress <= 0f || optionRows.isEmpty()) return
        paint.style = Paint.Style.FILL
        paint.color = colorAtAlpha(0xFF000000.toInt(), .46f * progress)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val contentTop = headerHeight
        val contentBottom = height - Y2UiTheme.COMPACT_FOOTER_HEIGHT_DP * density
        val centerX = width * .5f
        val centerY = (contentTop + contentBottom) * .5f
        val ringRadius = min(width * .215f, (contentBottom - contentTop) * .36f)
        val panelRadius = ringRadius + 25f * density
        val scale = .95f + .05f * progress
        val selectedIndex = renderState.selectedIndex.coerceIn(0, optionRows.lastIndex)
        val angleStep = 360f / optionRows.size
        val selectedAngle = -90f + angleStep * selectedIndex

        val saveCount = canvas.save()
        canvas.scale(scale, scale, centerX, centerY)

        paint.style = Paint.Style.FILL
        paint.color = colorAtAlpha(palette.surface, .91f * progress)
        canvas.drawCircle(centerX, centerY, panelRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = colorAtAlpha(palette.divider, .72f * progress)
        canvas.drawCircle(centerX, centerY, ringRadius, paint)
        paint.color = colorAtAlpha(palette.divider, .9f * progress)
        canvas.drawCircle(centerX, centerY, panelRadius, paint)

        reusableRectF.set(
            centerX - ringRadius,
            centerY - ringRadius,
            centerX + ringRadius,
            centerY + ringRadius
        )
        paint.strokeWidth = 2.5f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = colorAtAlpha(palette.accent, progress)
        canvas.drawArc(
            reusableRectF,
            selectedAngle - angleStep * .26f,
            angleStep * .52f,
            false,
            paint
        )
        paint.strokeCap = Paint.Cap.BUTT

        optionRows.forEachIndexed { index, row ->
            val angle = (-90f + angleStep * index) * (PI.toFloat() / 180f)
            val nodeX = centerX + cos(angle) * ringRadius
            val nodeY = centerY + sin(angle) * ringRadius
            val selected = index == selectedIndex
            val active = Y2RowState.isActive(row, renderState)
            val nodeRadius = when {
                selected -> 21f
                active -> 17f
                else -> 15f
            } * density
            paint.style = Paint.Style.FILL
            paint.color = colorAtAlpha(
                when {
                    selected -> palette.focusSurface
                    active -> palette.activeSurface
                    else -> palette.surface
                },
                progress
            )
            canvas.drawCircle(nodeX, nodeY, nodeRadius, paint)
            val icon = Y2RowIcons.forRow(
                row,
                renderState.currentScreen,
                renderState.playback.currentTrackId,
                active
            )
            iconPainter.draw(
                canvas,
                icon,
                nodeX,
                nodeY,
                (if (selected) 22f else 18f) * density,
                colorAtAlpha(if (selected || active) palette.accent else palette.secondaryText, progress)
            )
            if (active && !selected) {
                paint.style = Paint.Style.FILL
                paint.color = colorAtAlpha(palette.accent, progress)
                canvas.drawCircle(nodeX, nodeY + nodeRadius + 4f * density, 2f * density, paint)
            }
        }

        val selectedRow = optionRows[selectedIndex]
        val selectedActive = Y2RowState.isActive(selectedRow, renderState)
        val centerIcon = Y2RowIcons.forRow(
            selectedRow,
            renderState.currentScreen,
            renderState.playback.currentTrackId,
            selectedActive
        )
        iconPainter.draw(
            canvas,
            centerIcon,
            centerX,
            centerY - 24f * density,
            24f * density,
            colorAtAlpha(if (selectedActive) palette.accent else palette.primaryText, progress)
        )
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = Y2UiTheme.MINI_TITLE_SP * density
        boldPaint.color = colorAtAlpha(palette.primaryText, progress)
        val centerTextWidth = 112f * density
        canvas.drawText(
            ellipsize(selectedRow.title, centerTextWidth, boldPaint),
            centerX,
            centerY + 7f * density,
            boldPaint
        )
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = Y2UiTheme.BADGE_SP * density
        paint.color = colorAtAlpha(if (selectedActive) palette.accent else palette.secondaryText, progress)
        val value = selectedRow.subtitle?.takeIf { it.isNotBlank() } ?: "Center to open"
        canvas.drawText(
            ellipsize(value, centerTextWidth, paint),
            centerX,
            centerY + 27f * density,
            paint
        )
        paint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
        canvas.restoreToCount(saveCount)
        paint.style = Paint.Style.FILL
    }

    private fun colorAtAlpha(color: Int, fraction: Float): Int {
        val sourceAlpha = color ushr 24 and 0xFF
        val alpha = (sourceAlpha * fraction.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        return color and 0x00FFFFFF or (alpha shl 24)
    }

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

        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.NOW_TITLE_SP * density
        boldPaint.color = palette.primaryText
        var y = artTop + 26f * density
        if (isNowPlayingScrollTarget(SelectedTextScroller.TARGET_NOW_TITLE, cachedNowTitleFull)) {
            drawScrollingText(canvas, cachedNowTitleFull, colLeft, colRight, y, boldPaint,
                nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_TITLE).offsetPx)
        } else canvas.drawText(cachedNowTitle, colLeft, y, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.NOW_ARTIST_SP * density
        paint.color = palette.secondaryText
        y += 22f * density
        if (isNowPlayingScrollTarget(SelectedTextScroller.TARGET_NOW_ARTIST, cachedNowArtistFull)) {
            drawScrollingText(canvas, cachedNowArtistFull, colLeft, colRight, y, paint,
                nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_ARTIST).offsetPx)
        } else canvas.drawText(cachedNowArtist, colLeft, y, paint)
        if (cachedNowAlbum.isNotEmpty()) {
            paint.textSize = Y2UiTheme.NOW_ALBUM_SP * density
            paint.color = palette.mutedText
            y += 19f * density
            if (isNowPlayingScrollTarget(SelectedTextScroller.TARGET_NOW_ALBUM, cachedNowAlbumFull)) {
                drawScrollingText(canvas, cachedNowAlbumFull, colLeft, colRight, y, paint,
                    nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_ALBUM).offsetPx)
            } else canvas.drawText(cachedNowAlbum, colLeft, y, paint)
        }
        if (cachedNowGenre.isNotEmpty()) {
            paint.textSize = Y2UiTheme.META_SP * density
            paint.color = palette.mutedText
            y += 18f * density
            canvas.drawText(cachedNowGenre, colLeft, y, paint)
        }
        if (cachedNowSecondary.isNotEmpty()) {
            paint.textSize = Y2UiTheme.META_SP * density
            paint.color = if (cachedNowSecondaryWarning) palette.warning else palette.mutedText
            y += 18f * density
            canvas.drawText(cachedNowSecondary, colLeft, y, paint)
        }
        drawStateBadges(canvas, colLeft + 8f * density, (y + 25f * density).coerceAtMost(barTop - 18f * density), 28f * density)

        if (cachedStatusTag.isNotEmpty()) {
            boldPaint.textAlign = Paint.Align.LEFT
            boldPaint.textSize = Y2UiTheme.BADGE_SP * density
            boldPaint.color = if (state.playback.status == PlaybackStatus.ERROR) palette.warning else palette.accent
            canvas.drawText(cachedStatusTag, colLeft, barTop - 8f * density, boldPaint)
        }
        drawProgressBar(canvas, colLeft, colRight, barTop)
        val timesY = barTop + 19f * density
        paint.style = Paint.Style.FILL
        paint.textSize = Y2UiTheme.META_SP * density
        paint.color = palette.secondaryText
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
        iconPainter.draw(canvas, Y2Icon.PREVIOUS, centerX - sideOffset, centerY, 24f * density, palette.primaryText)
        iconPainter.draw(canvas, Y2Icon.NEXT, centerX + sideOffset, centerY, 24f * density, palette.primaryText)

        paint.style = Paint.Style.FILL
        paint.color = palette.focusSurface
        canvas.drawCircle(centerX, centerY, 22f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Y2UiTheme.FOCUS_OUTLINE_DP * density
        paint.color = palette.accent
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
            if (state.playback.status == PlaybackStatus.ERROR) palette.warning else palette.accent
        )
    }

    private fun drawHomePane(canvas: Canvas) {
        val paneLeft = rowAreaRight() + 6f * density
        val paneRight = width - 10f * density
        val paneTop = headerHeight + 10f * density
        val paneBottom = height - footerHeight - 10f * density
        reusableRectF.set(paneLeft, paneTop, paneRight, paneBottom)
        paint.style = Paint.Style.FILL
        paint.color = palette.surface
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
            boldPaint.color = palette.primaryText
            canvas.drawText(cachedPaneTitle, centerX, artTop + artSize + 20f * density, boldPaint)
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
            paint.color = palette.secondaryText
            canvas.drawText(cachedPaneArtist, centerX, artTop + artSize + 36f * density, paint)
            val barTop = paneBottom - Y2UiTheme.HOME_PROGRESS_BOTTOM_INSET_DP * density
            val timeBaseline = paneBottom - Y2UiTheme.HOME_PROGRESS_TIME_BOTTOM_INSET_DP * density
            drawProgressBar(canvas, paneLeft + 16f * density, paneRight - 16f * density, barTop)
            paint.style = Paint.Style.FILL
            paint.textSize = Y2UiTheme.BADGE_SP * density
            paint.color = palette.mutedText
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(cachedElapsed, paneLeft + 16f * density, timeBaseline, paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(cachedDuration, paneRight - 16f * density, timeBaseline, paint)
        } else {
            val centerY = (paneTop + paneBottom) * .5f
            iconPainter.draw(canvas, Y2Icon.SONG, centerX, centerY - 34f * density, 34f * density, palette.accent)
            paint.style = Paint.Style.FILL
            boldPaint.textAlign = Paint.Align.CENTER
            boldPaint.textSize = Y2UiTheme.BODY_SP * density
            boldPaint.color = palette.primaryText
            canvas.drawText(cachedHomeStats, centerX, centerY + 6f * density, boldPaint)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
            paint.color = palette.mutedText
            canvas.drawText(cachedHomeHint, centerX, centerY + 24f * density, paint)
            if (state.library.isScanning) {
                drawScanProgressBar(
                    canvas,
                    paneLeft + 16f * density,
                    paneRight - 16f * density,
                    centerY + 36f * density
                )
            }
        }
        paint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawNowPlayingTall(canvas: Canvas) {
        val centerX = width * .5f
        val artSize = (width * .46f).coerceIn(124f * density, 156f * density)
        val artTop = headerHeight + 10f * density
        drawArtwork(canvas, centerX - artSize * .5f, artTop, artSize)

        val titleTop = artTop + artSize + 9f * density
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = Y2UiTheme.NOW_TITLE_SP * density
        boldPaint.color = palette.primaryText
        if (isNowPlayingScrollTarget(SelectedTextScroller.TARGET_NOW_TITLE, cachedNowTitleFull)) {
            drawScrollingText(canvas, cachedNowTitleFull, 12f * density, width - 12f * density,
                titleTop + 19f * density, boldPaint,
                nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_TITLE).offsetPx)
        } else canvas.drawText(cachedNowTitle, centerX, titleTop + 19f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = Y2UiTheme.NOW_ARTIST_SP * density
        paint.color = palette.secondaryText
        if (isNowPlayingScrollTarget(SelectedTextScroller.TARGET_NOW_ARTIST, cachedNowArtistFull)) {
            drawScrollingText(canvas, cachedNowArtistFull, 12f * density, width - 12f * density,
                titleTop + 40f * density, paint,
                nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_ARTIST).offsetPx)
        } else canvas.drawText(cachedNowArtist, centerX, titleTop + 40f * density, paint)
        paint.textSize = Y2UiTheme.NOW_ALBUM_SP * density
        paint.color = palette.mutedText
        if (isNowPlayingScrollTarget(SelectedTextScroller.TARGET_NOW_ALBUM, cachedNowAlbumFull)) {
            drawScrollingText(canvas, cachedNowAlbumFull, 12f * density, width - 12f * density,
                titleTop + 58f * density, paint,
                nowPlayingTextScroller(SelectedTextScroller.TARGET_NOW_ALBUM).offsetPx)
        } else canvas.drawText(cachedNowAlbum, centerX, titleTop + 58f * density, paint)
        var metadataY = titleTop + 58f * density
        paint.textSize = Y2UiTheme.META_SP * density
        if (cachedNowGenre.isNotEmpty()) {
            metadataY += 17f * density
            paint.color = palette.mutedText
            canvas.drawText(cachedNowGenre, centerX, metadataY, paint)
        }
        if (cachedNowSecondary.isNotEmpty()) {
            metadataY += 17f * density
            paint.color = if (cachedNowSecondaryWarning) palette.warning else palette.mutedText
            canvas.drawText(cachedNowSecondary, centerX, metadataY, paint)
        }

        val barTop = (titleTop + 88f * density).coerceAtLeast(metadataY + 13f * density)
        drawProgressBar(canvas, 20f * density, width - 20f * density, barTop)
        paint.style = Paint.Style.FILL
        paint.textSize = Y2UiTheme.META_SP * density
        paint.color = palette.secondaryText
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
        paint.color = palette.surfaceRaised
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
            paint.color = palette.divider
            canvas.drawCircle(centerX, centerY, size * .29f, paint)
            canvas.drawCircle(centerX, centerY, size * .17f, paint)
            paint.style = Paint.Style.FILL
            iconPainter.draw(canvas, fallbackIcon, centerX, centerY, size * .28f, palette.accent)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = palette.divider
        reusableRectF.set(left, top, left + size, top + size)
        canvas.drawRoundRect(reusableRectF, Y2UiTheme.PANEL_RADIUS_DP * density, Y2UiTheme.PANEL_RADIUS_DP * density, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawStateBadges(canvas: Canvas, startX: Float, centerY: Float, step: Float) {
        var x = startX
        if (state.playback.sleepTimerMode != SleepTimerMode.OFF) {
            iconPainter.draw(canvas, Y2Icon.TIMER, x, centerY, 14f * density, palette.accent)
            x += step
        }
        if (cachedNowFavorite) {
            iconPainter.draw(canvas, Y2Icon.FAVORITE_FILLED, x, centerY, 14f * density, palette.accent)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawProgressBar(canvas: Canvas, left: Float, right: Float, top: Float) {
        val barHeight = Y2UiTheme.PROGRESS_HEIGHT_DP * density
        paint.style = Paint.Style.FILL
        reusableRectF.set(left, top, right, top + barHeight)
        paint.color = palette.divider
        canvas.drawRoundRect(reusableRectF, barHeight * .5f, barHeight * .5f, paint)
        val progress = Y2UiLogic.progressFraction(state.playback.positionMs, state.playback.durationMs)
        val progressRight = left + (right - left) * progress
        reusableRectF.set(left, top, progressRight.coerceAtLeast(left), top + barHeight)
        paint.color = palette.accent
        canvas.drawRoundRect(reusableRectF, barHeight * .5f, barHeight * .5f, paint)
        if (progress > 0f) {
            val knobRadius = 3f * density
            val knobX = progressRight.coerceIn(left + knobRadius, right - knobRadius)
            canvas.drawCircle(knobX, top + barHeight * .5f, knobRadius, paint)
        }
    }

    private fun drawScanProgressBar(canvas: Canvas, left: Float, right: Float, top: Float) {
        val barHeight = Y2UiTheme.PROGRESS_HEIGHT_DP * density
        paint.style = Paint.Style.FILL
        reusableRectF.set(left, top, right, top + barHeight)
        paint.color = palette.divider
        canvas.drawRoundRect(reusableRectF, barHeight * .5f, barHeight * .5f, paint)

        val width = right - left
        val fraction = cachedScanProgressFraction
        val fillLeft: Float
        val fillRight: Float
        if (fraction != null) {
            fillLeft = left
            fillRight = left + width * fraction
        } else {
            val segmentWidth = width * .28f
            fillLeft = left + (width - segmentWidth) * cachedScanProgressPhase
            fillRight = fillLeft + segmentWidth
        }
        reusableRectF.set(fillLeft, top, fillRight.coerceAtLeast(fillLeft), top + barHeight)
        paint.color = palette.accent
        canvas.drawRoundRect(reusableRectF, barHeight * .5f, barHeight * .5f, paint)
    }

    private fun drawFooter(canvas: Canvas) {
        if (isSplitHome()) return

        if (
            state.playback.currentTrackId != null &&
            !isNowPlayingSurface()
        ) {
            drawMiniPlayer(canvas)
        } else {
            drawCompactFooter(canvas)
        }
    }

    private fun drawMiniPlayer(canvas: Canvas) {
        val top = height - footerHeight
        paint.style = Paint.Style.FILL
        paint.color = palette.surfaceRaised
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
        paint.color = palette.accent
        val progress = Y2UiLogic.miniPlayerProgressFraction(
            state.playback.status,
            state.playback.positionMs,
            state.playback.durationMs
        )
        canvas.drawRect(0f, top, width * progress, top + 2f * density, paint)

        val artSize = Y2UiTheme.MINI_ART_SIZE_DP * density
        drawArtwork(canvas, 8f * density, top + 8f * density, artSize)
        val textLeft = 8f * density + artSize + 10f * density
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.MINI_TITLE_SP * density
        boldPaint.color = palette.primaryText
        canvas.drawText(cachedMiniTitle, textLeft, top + 25f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
        paint.color = palette.secondaryText
        canvas.drawText(cachedMiniArtist, textLeft, top + 45f * density, paint)

        val controlX = width - 29f * density
        val controlY = top + footerHeight * .5f
        paint.color = palette.surface
        canvas.drawCircle(controlX, controlY, 20f * density, paint)
        val playbackIcon = when (state.playback.status) {
            PlaybackStatus.PLAYING -> Y2Icon.PAUSE
            PlaybackStatus.PREPARING -> Y2Icon.PREPARING
            PlaybackStatus.ERROR -> Y2Icon.WARNING
            else -> Y2Icon.PLAY
        }
        iconPainter.draw(canvas, playbackIcon, controlX, controlY, 24f * density,
            if (state.playback.status == PlaybackStatus.ERROR) palette.warning else palette.accent)
    }

    private fun drawCompactFooter(canvas: Canvas) {
        val top = height - footerHeight
        paint.style = Paint.Style.FILL
        paint.color = palette.surface
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = Y2UiTheme.BADGE_SP * density
        paint.color = palette.secondaryText
        canvas.drawText(cachedFooterPosition, width - 10f * density, top + 20f * density, paint)
        if (isNowPlayingSurface()) {
            drawNowPlayingFooterStatuses(canvas, top)
            return
        }
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
        paint.color = palette.mutedText
        canvas.drawText(cachedFooterHint, 10f * density, top + 20f * density, paint)
    }

    private fun drawNowPlayingFooterStatuses(canvas: Canvas, top: Float) {
        var x = 10f * density
        x = drawFooterStatus(
            canvas,
            x,
            top,
            Y2Icon.SHUFFLE,
            "Shuffle",
            Y2UiLogic.shuffleStatusLabel(state.playback.shuffleEnabled),
            state.playback.shuffleEnabled
        )
        paint.style = Paint.Style.FILL
        paint.color = palette.divider
        canvas.drawRect(x + 8f * density, top + 7f * density, x + 9f * density, top + 23f * density, paint)
        drawFooterStatus(
            canvas,
            x + 18f * density,
            top,
            Y2Icon.REPEAT,
            "Repeat",
            Y2UiLogic.repeatStatusLabel(state.playback.repeatMode),
            state.playback.repeatMode != RepeatMode.OFF
        )
    }

    private fun drawFooterStatus(
        canvas: Canvas,
        startX: Float,
        top: Float,
        icon: Y2Icon,
        label: String,
        value: String,
        active: Boolean
    ): Float {
        val centerY = top + footerHeight * .5f
        val color = if (active) palette.accent else palette.mutedText
        iconPainter.draw(canvas, icon, startX + 7f * density, centerY, 14f * density, color)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.BADGE_SP * density
        paint.color = color
        val text = "$label  $value"
        val textX = startX + 19f * density
        canvas.drawText(text, textX, top + 19f * density, paint)
        val endX = textX + paint.measureText(text)
        if (active) {
            paint.color = palette.accent
            reusableRectF.set(
                startX,
                height - 2f * density,
                endX,
                height.toFloat()
            )
            canvas.drawRoundRect(reusableRectF, density, density, paint)
        }
        return endX
    }

    private fun drawStatusMessage(canvas: Canvas) {
        if (cachedMessageSource == null) return
        val bottom = height - footerHeight - 8f * density
        val top = bottom - 58f * density
        reusableRectF.set(9f * density, top, width - 9f * density, bottom)
        paint.style = Paint.Style.FILL
        paint.color = palette.surfaceRaised
        canvas.drawRoundRect(reusableRectF, Y2UiTheme.PANEL_RADIUS_DP * density, Y2UiTheme.PANEL_RADIUS_DP * density, paint)
        paint.color = if (state.playback.pauseReason == PauseReason.OUTPUT_DISCONNECTED) palette.warning else palette.accent
        reusableRectF.set(9f * density, top + 9f * density, 12f * density, bottom - 9f * density)
        canvas.drawRoundRect(reusableRectF, 1.5f * density, 1.5f * density, paint)
        iconPainter.draw(canvas, if (state.playback.pauseReason == PauseReason.OUTPUT_DISCONNECTED) Y2Icon.DISCONNECTED else Y2Icon.INFO, 29f * density, top + 29f * density, 19f * density, paint.color)
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = Y2UiTheme.BODY_SP * density
        boldPaint.color = palette.primaryText
        canvas.drawText(cachedMessageTitle, 46f * density, top + 24f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = Y2UiTheme.META_SP * density
        paint.color = palette.secondaryText
        canvas.drawText(cachedMessageBody, 46f * density, top + 42f * density, paint)
    }

    private fun updatePresentationCache() {
        cachedRoute = Y2UiLogic.routePresentation(state.playback.outputRoute)
        cachedHeaderRouteIcon = when (cachedRoute.icon) {
            RouteIcon.BLUETOOTH -> RouteIcon.BLUETOOTH
            RouteIcon.DISCONNECTED -> RouteIcon.DISCONNECTED
            else -> null
        }
        cachedBatteryPercent = state.device.batteryPercent
        cachedBatteryText = state.device.batteryPercent?.let { "$it%" } ?: ""
        updateFooterPosition()
        val track = state.playback.currentTrackId?.let(state.library.byId::get)
        cachedNowFavorite = track?.favorite == true
        val nowWidth = nowPlayingTextWidth()
        boldPaint.textSize = Y2UiTheme.NOW_TITLE_SP * density
        cachedNowTitleFull = track?.title ?: "Nothing playing"
        cachedNowTitleWidth = boldPaint.measureText(cachedNowTitleFull)
        cachedNowTitle = ellipsize(cachedNowTitleFull, nowWidth, boldPaint)
        val book = track?.audiobookFolderKey?.let { ScreenContent.audiobookEntry(state, it) }
        paint.textSize = Y2UiTheme.NOW_ARTIST_SP * density
        val artistText = when {
            track == null -> "Select a track"
            book != null -> Y2UiLogic.audiobookArtistLine(book.name, track.artist)
            else -> track.displayArtist
        }
        cachedNowArtistFull = artistText
        cachedNowArtistWidth = paint.measureText(cachedNowArtistFull)
        cachedNowArtist = ellipsize(cachedNowArtistFull, nowWidth, paint)
        paint.textSize = Y2UiTheme.NOW_ALBUM_SP * density
        val albumText = when {
            track == null -> ""
            book != null -> Y2UiLogic.audiobookChapterLine(
                book.chapterIds.indexOf(track.id).takeIf { it >= 0 }?.plus(1),
                book.chapterCount
            )
            else -> Y2UiLogic.albumLine(
                album = track.displayAlbum,
                year = track.year,
                includeYear = state.preferences.extraTrackInfo
            )
        }
        cachedNowAlbumFull = albumText
        cachedNowAlbumWidth = paint.measureText(cachedNowAlbumFull)
        cachedNowAlbum = ellipsize(cachedNowAlbumFull, nowWidth, paint)
        paint.textSize = Y2UiTheme.META_SP * density
        cachedNowGenre = if (track != null && state.preferences.extraTrackInfo) {
            ellipsize(Y2UiLogic.genreLine(track.genre), nowWidth, paint)
        } else ""
        cachedTechnicalLine = if (track == null) "" else Y2UiLogic.technicalLine(
            AudioCodecLabels.label(track.codec, track.extension),
            track.sampleRate,
            track.bitDepth,
            if (state.preferences.extraTrackInfo) track.bitrate else null
        )
        cachedDacLabel = Y2UiLogic.dacModeLabel(
            state.preferences.audioQualityMode,
            state.playback.dac.detected,
            state.playback.dac.hiFiRequestAccepted
        )
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
        val paneTextWidth = (fallbackWidth() * .45f - 40f * density).coerceAtLeast(40f * density)
        boldPaint.textSize = Y2UiTheme.MINI_TITLE_SP * density
        cachedPaneTitle = if (track == null) "" else ellipsize(track.title, paneTextWidth, boldPaint)
        paint.textSize = Y2UiTheme.NAV_LABEL_SP * density
        cachedPaneArtist = if (track == null) "" else ellipsize(track.displayArtist, paneTextWidth, paint)
        val count = state.library.musicTracks.size
        val processed = state.library.scanProgress.processedFiles.coerceAtLeast(0)
        cachedScanProgressFraction = Y2UiLogic.scanProgressFraction(processed, count)
        cachedScanProgressPhase =
            (processed % SCAN_PROGRESS_PHASE_STEPS).toFloat() / SCAN_PROGRESS_PHASE_STEPS
        val stats = if (state.library.isScanning) {
            if (cachedScanProgressFraction != null) {
                String.format(java.util.Locale.US, "%,d / %,d", processed, count)
            } else {
                String.format(java.util.Locale.US, "%,d processed", processed)
            }
        } else when (count) {
            0 -> "No music yet"
            1 -> "1 song"
            else -> String.format(java.util.Locale.US, "%,d songs", count)
        }
        boldPaint.textSize = Y2UiTheme.BODY_SP * density
        cachedHomeStats = ellipsize(stats, paneTextWidth, boldPaint)
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
        val headerTitle = when (state.currentScreen) {
            Screen.NowPlayingOptions -> "Now Playing"
            else -> ScreenContent.title(state)
        }
        cachedHeaderTitle = ellipsize(headerTitle, availableWidth, boldPaint)
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

    private fun fallbackWidth(): Float = (width.takeIf { it > 0 } ?: (Y2UiTheme.TARGET_WIDTH_PX * density).toInt()).toFloat()

    private fun fallbackHeight(): Float = (height.takeIf { it > 0 } ?: (Y2UiTheme.TARGET_HEIGHT_PX * density).toInt()).toFloat()

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
            !isNowPlayingSurface() && rows.isNotEmpty() ->
                "${state.selectedIndex + 1}/${rows.size}"
            isNowPlayingSurface() && state.playback.queue.isNotEmpty() ->
                "${(state.playback.queue.size - 1).coerceAtLeast(0)} next"
            else -> ""
        }
    }

    private fun updateFooterHint() {
        val hint = when {
            isNowPlayingSurface() -> ""
            state.currentScreen is Screen.QueueMove -> "WHEEL MOVE · CENTER CONFIRM · BACK CANCEL"
            state.currentScreen == Screen.EqualizerBands -> "WHEEL BAND · CENTER + · HOLD CENTER - · L/R TRACK"
            state.currentScreen == Screen.Brightness || state.currentScreen == Screen.ScreenTimeout ||
                state.currentScreen == Screen.SortOrder -> "WHEEL CHOOSE · CENTER APPLY · L/R TRACK"
            else -> "WHEEL NAVIGATE · CENTER SELECT · L/R TRACK"
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

    private fun requestArtwork() {
        val track = state.playback.currentTrackId?.let(state.library.byId::get)
        val path = track?.absolutePath
        val identity = track?.let { Triple(it.id, it.modifiedAt, state.library.tracksRevision) }
        if (path != artworkPath || identity != artworkIdentity) {
            artworkPath = path
            artworkIdentity = identity
            artwork = null
            if (track != null) {
                artworkLoader.load(
                    track.absolutePath,
                    track.modifiedAt,
                    state.library.tracksRevision,
                    SHARED_ARTWORK_SIZE_PX
                ) { loadedPath, bitmap ->
                    if (loadedPath == artworkPath && identity == artworkIdentity) {
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

    private fun ensureSelectionVisible(): Boolean {
        if (rows.isEmpty()) {
            val changed = visibleStart != 0
            if (changed) invalidateRowCache()
            visibleStart = 0
            return changed
        }
        val nextStart = Y2UiLogic.firstVisibleRow(rows.size, state.selectedIndex, visibleStart, visibleRowCount())
        if (nextStart != visibleStart) {
            visibleStart = nextStart
            invalidateRowCache()
            return true
        }
        return false
    }

    private fun requestVisibleRowArtworks() {
        if (!attached || visibility != VISIBLE || windowVisibility != VISIBLE || width <= 0 || height <= 0) return
        val requests = VisibleRowArtworkPlanner.requests(state, rows, visibleStart, visibleRowCount())
        val generation = ++visibleRowArtworkGeneration
        val retained = HashMap<Int, VisibleRowArtworkSlot>(requests.size)
        val pending = ArrayList<VisibleRowArtworkLoad>(requests.size)
        requests.forEach { request ->
            val track = request.track
            val key = VisibleRowArtworkKey(
                trackId = track.id,
                path = track.absolutePath,
                modifiedAt = track.modifiedAt,
                libraryRevision = state.library.tracksRevision
            )
            val existing = visibleRowArtworks[request.rowIndex]
            if (existing?.key == key) {
                retained[request.rowIndex] = existing
                if (!existing.resolved) pending += VisibleRowArtworkLoad(request.rowIndex, key)
            } else {
                retained[request.rowIndex] = VisibleRowArtworkSlot(key)
                pending += VisibleRowArtworkLoad(request.rowIndex, key)
            }
        }
        visibleRowArtworks.clear()
        visibleRowArtworks.putAll(retained)
        loadNextVisibleRowArtwork(pending, 0, generation)
    }

    private fun loadNextVisibleRowArtwork(
        pending: List<VisibleRowArtworkLoad>,
        position: Int,
        generation: Long
    ) {
        if (generation != visibleRowArtworkGeneration || position >= pending.size) return
        val request = pending[position]
        if (visibleRowArtworks[request.rowIndex]?.key != request.key || !isRowVisible(request.rowIndex)) {
            loadNextVisibleRowArtwork(pending, position + 1, generation)
            return
        }
        artworkLoader.load(
            request.key.path,
            request.key.modifiedAt,
            request.key.libraryRevision,
            ROW_ARTWORK_SIZE_PX
        ) { loadedPath, bitmap ->
            val slot = visibleRowArtworks[request.rowIndex]
            if (generation == visibleRowArtworkGeneration && loadedPath == request.key.path &&
                slot?.key == request.key && isRowVisible(request.rowIndex)
            ) {
                slot.bitmap = bitmap
                slot.resolved = true
                invalidateRowArtwork(request.rowIndex)
            }
            loadNextVisibleRowArtwork(pending, position + 1, generation)
        }
    }

    private fun isRowVisible(index: Int): Boolean =
        index >= visibleStart && index < (visibleStart + visibleRowCount()).coerceAtMost(rows.size)

    private fun invalidateRowArtwork(index: Int) {
        if (!isRowVisible(index)) return
        val top = rowAreaTop() + (index - visibleStart) * rowHeight
        invalidate(0, top.toInt(), (50f * density).toInt(), (top + rowHeight).toInt())
    }

    private fun cancelVisibleRowArtworkRequests() {
        visibleRowArtworkGeneration += 1
        visibleRowArtworks.clear()
    }

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
                cachedTitleWidths[slot] = boldPaint.measureText(row.title)
                cachedTitleAvailableWidths[slot] = maxWidth
                cachedTitles[slot] = ellipsize(row.title, maxWidth, boldPaint)
                cachedSubtitles[slot] = rowSubtitle(row)?.let { ellipsize(it, maxWidth, paint) }
                cachedIcons[slot] = iconFor(row, active)
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
                cachedTitleWidths[slot] = 0f
                cachedTitleAvailableWidths[slot] = 0f
                cachedActive[slot] = false
                cachedUnavailable[slot] = false
            }
        }
    }

    private fun invalidateRowCache() {
        cachedRowStart = -1
        cachedRowEnd = -1
    }

    private fun iconFor(row: ScreenRow, active: Boolean): Y2Icon =
        Y2RowIcons.forRow(row, state.currentScreen, state.playback.currentTrackId, active)

    private fun rowSubtitle(row: ScreenRow): String? = when {
        row !is ScreenRow.TrackRow -> row.subtitle
        state.currentScreen is Screen.AlbumSongs -> null
        state.currentScreen is Screen.ArtistSongs -> row.track.displayAlbum
        (state.currentScreen as? Screen.FacetTracks)?.artist != null -> row.track.displayAlbum
        else -> row.subtitle
    }

    private fun trailingIconFor(row: ScreenRow, active: Boolean): Y2Icon? {
        val action = row as? ScreenRow.Action ?: return null
        val key = action.key
        // A row that opens a screen shows where it goes. CHECK is reserved for the
        // selected value of a setting, so navigation wins over the active mark.
        if (key in NAVIGATION_KEYS || key.startsWith("storage:") || key.startsWith("playlist:") ||
            key.startsWith("audiobook:") || key.startsWith("audiobook_chapters:") ||
            key.startsWith("track_playlist") || key.startsWith("track_browse") || key.startsWith("track_details") ||
            key.startsWith("track_album") || key.startsWith("track_artist") || key.startsWith("np_playlist") ||
            key.startsWith("np_track_options")
        ) return Y2Icon.CHEVRON
        return if (active) Y2Icon.CHECK else null
    }

    private fun isActive(row: ScreenRow): Boolean = Y2RowState.isActive(row, state)

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

    private fun updateContentDescription(value: AppState) {
        if (accessibilityManager?.isEnabled != true) return
        contentDescription = buildContentDescription(value)
    }

    private fun buildContentDescription(value: AppState): String {
        val selected = rows.getOrNull(value.selectedIndex)
        return buildString {
            append(ScreenContent.title(value))
            selected?.let {
                append(", selected ${it.title}")
                it.subtitle?.let { subtitle -> append(", $subtitle") }
            }
            if (value.currentScreen == Screen.NowPlaying) {
                append(", shuffle ${Y2UiLogic.shuffleStatusLabel(value.playback.shuffleEnabled)}")
                append(", repeat ${Y2UiLogic.repeatStatusLabel(value.playback.repeatMode)}")
            }
            append(", output ${cachedRoute.label}")
        }
    }

    companion object {
        private const val MAX_VISIBLE_ROWS = 12
        private const val SCAN_PROGRESS_PHASE_STEPS = 200
        private const val MARQUEE_SPEED_DP_PER_SECOND = 28f
        private const val MARQUEE_FRAME_MS = 100L
        private const val SLEEP_TIMER_REFRESH_MS = 1_000L
        private const val ROW_ARTWORK_LEFT_DP = 8f
        private const val ROW_ARTWORK_SIZE_DP = 40f
        private const val ROW_ARTWORK_SIZE_PX = 64
        private const val RADIAL_OPEN_MS = 160f
        private const val RADIAL_CLOSE_MS = 120f

        private const val BATTERY_TEXT_REFERENCE = "100%"
        const val SHARED_ARTWORK_SIZE_PX = 256
        private val NAVIGATION_KEYS = setOf(
            "music", "audiobooks", "songs", "albums", "artists", "playlists",
            "folders", "favorites", "recent", "audio", "settings", "output", "sort", "bluetooth",
            "sound_effects", "reset", "extra_track_info",
            "display", "controls", "storage", "system", "diagnostics", "android_settings", "about",
            "interface", "library_settings", "sound", "balance", "brightness", "timeout", "queue",
            "queue_management", "playback_transitions", "playback_seeking", "playback_volume",
            "playback_interruptions", "equalizer", "sound_dynamics", "output_information", "artist_all_songs"
        )
    }

    private data class VisibleRowArtworkKey(
        val trackId: Long,
        val path: String,
        val modifiedAt: Long,
        val libraryRevision: Long
    )

    private data class VisibleRowArtworkSlot(
        val key: VisibleRowArtworkKey,
        var bitmap: Bitmap? = null,
        var resolved: Boolean = false
    )

    private data class VisibleRowArtworkLoad(
        val rowIndex: Int,
        val key: VisibleRowArtworkKey
    )
}
