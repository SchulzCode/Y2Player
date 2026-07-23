package com.schulzcode.y2player.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

enum class Y2Icon {
    SONG, FAVORITE, RECENT, ALBUM, ARTIST, FOLDER, PLAYLIST, PLAYING, QUEUE,
    BLUETOOTH, SETTINGS, STORAGE, DISPLAY, DIAGNOSTICS, SORT, ADD, INFO,
    ACTION, REFRESH, HEADPHONES, SPEAKER, DISCONNECTED, UNKNOWN, CHEVRON, PLAY, PAUSE,
    PREVIOUS, NEXT, CHECK, PREPARING, WARNING, SHUFFLE, REPEAT, TIMER, DAC
}

/** Small monochrome Canvas icon set; one Path and RectF are reused for every draw. */
class Y2IconPainter(private val paint: Paint, density: Float) {
    private val path = Path()
    private val rect = RectF()
    private val stroke = 1.65f * density

    fun draw(canvas: Canvas, icon: Y2Icon, centerX: Float, centerY: Float, size: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val left = centerX - size * 0.5f
        val top = centerY - size * 0.5f
        when (icon) {
            Y2Icon.SONG -> drawSong(canvas, left, top, size)
            Y2Icon.FAVORITE -> drawFavorite(canvas, left, top, size)
            Y2Icon.RECENT -> drawRecent(canvas, left, top, size)
            Y2Icon.ALBUM -> drawAlbum(canvas, left, top, size)
            Y2Icon.ARTIST -> drawArtist(canvas, left, top, size)
            Y2Icon.FOLDER -> drawFolder(canvas, left, top, size)
            Y2Icon.PLAYLIST -> drawPlaylist(canvas, left, top, size)
            Y2Icon.PLAYING, Y2Icon.PLAY -> drawPlay(canvas, left, top, size)
            Y2Icon.QUEUE -> drawQueue(canvas, left, top, size)
            Y2Icon.BLUETOOTH -> drawBluetooth(canvas, left, top, size)
            Y2Icon.SETTINGS -> drawSettings(canvas, centerX, centerY, size)
            Y2Icon.STORAGE -> drawStorage(canvas, left, top, size)
            Y2Icon.DISPLAY -> drawDisplay(canvas, left, top, size)
            Y2Icon.DIAGNOSTICS -> drawDiagnostics(canvas, left, top, size)
            Y2Icon.SORT -> drawSort(canvas, left, top, size)
            Y2Icon.ADD -> drawAdd(canvas, centerX, centerY, size)
            Y2Icon.INFO -> drawInfo(canvas, centerX, centerY, size)
            Y2Icon.ACTION -> drawAction(canvas, left, top, size)
            Y2Icon.REFRESH -> drawRefresh(canvas, left, top, size)
            Y2Icon.HEADPHONES -> drawHeadphones(canvas, left, top, size)
            Y2Icon.SPEAKER -> drawSpeaker(canvas, left, top, size)
            Y2Icon.DISCONNECTED -> drawDisconnected(canvas, left, top, size)
            Y2Icon.UNKNOWN -> drawUnknown(canvas, centerX, centerY, size)
            Y2Icon.CHEVRON -> drawChevron(canvas, left, top, size)
            Y2Icon.PAUSE -> drawPause(canvas, left, top, size)
            Y2Icon.PREVIOUS -> drawPrevious(canvas, left, top, size)
            Y2Icon.NEXT -> drawNext(canvas, left, top, size)
            Y2Icon.CHECK -> drawCheck(canvas, left, top, size)
            Y2Icon.PREPARING -> drawPreparing(canvas, left, top, size)
            Y2Icon.WARNING -> drawWarning(canvas, left, top, size)
            Y2Icon.SHUFFLE -> drawShuffle(canvas, left, top, size)
            Y2Icon.REPEAT -> drawRepeat(canvas, left, top, size)
            Y2Icon.TIMER -> drawTimer(canvas, left, top, size)
            Y2Icon.DAC -> drawDac(canvas, left, top, size)
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawSong(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawLine(x + s * .62f, y + s * .20f, x + s * .62f, y + s * .70f, paint)
        canvas.drawLine(x + s * .62f, y + s * .20f, x + s * .82f, y + s * .16f, paint)
        canvas.drawCircle(x + s * .45f, y + s * .73f, s * .16f, paint)
    }

    private fun drawFavorite(canvas: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x + s * .50f, y + s * .82f)
        path.cubicTo(x + s * .10f, y + s * .58f, x + s * .14f, y + s * .22f, x + s * .36f, y + s * .22f)
        path.cubicTo(x + s * .45f, y + s * .22f, x + s * .50f, y + s * .30f, x + s * .50f, y + s * .30f)
        path.cubicTo(x + s * .50f, y + s * .30f, x + s * .56f, y + s * .22f, x + s * .66f, y + s * .22f)
        path.cubicTo(x + s * .88f, y + s * .22f, x + s * .90f, y + s * .58f, x + s * .50f, y + s * .82f)
        canvas.drawPath(path, paint)
    }

    private fun drawRecent(canvas: Canvas, x: Float, y: Float, s: Float) {
        rect.set(x + s * .16f, y + s * .16f, x + s * .84f, y + s * .84f)
        canvas.drawArc(rect, -65f, 300f, false, paint)
        canvas.drawLine(x + s * .17f, y + s * .31f, x + s * .17f, y + s * .14f, paint)
        canvas.drawLine(x + s * .17f, y + s * .31f, x + s * .34f, y + s * .30f, paint)
        canvas.drawLine(x + s * .50f, y + s * .32f, x + s * .50f, y + s * .52f, paint)
        canvas.drawLine(x + s * .50f, y + s * .52f, x + s * .66f, y + s * .61f, paint)
    }

    private fun drawAlbum(canvas: Canvas, x: Float, y: Float, s: Float) {
        rect.set(x + s * .14f, y + s * .14f, x + s * .86f, y + s * .86f)
        canvas.drawRoundRect(rect, s * .08f, s * .08f, paint)
        canvas.drawCircle(x + s * .50f, y + s * .50f, s * .22f, paint)
        canvas.drawCircle(x + s * .50f, y + s * .50f, s * .04f, paint)
    }

    private fun drawArtist(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawCircle(x + s * .50f, y + s * .34f, s * .17f, paint)
        rect.set(x + s * .20f, y + s * .51f, x + s * .80f, y + s * .94f)
        canvas.drawArc(rect, 195f, 150f, false, paint)
    }

    private fun drawFolder(canvas: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x + s * .12f, y + s * .30f)
        path.lineTo(x + s * .39f, y + s * .30f)
        path.lineTo(x + s * .47f, y + s * .40f)
        path.lineTo(x + s * .88f, y + s * .40f)
        path.lineTo(x + s * .82f, y + s * .78f)
        path.lineTo(x + s * .18f, y + s * .78f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawPlaylist(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawLine(x + s * .14f, y + s * .27f, x + s * .56f, y + s * .27f, paint)
        canvas.drawLine(x + s * .14f, y + s * .48f, x + s * .56f, y + s * .48f, paint)
        canvas.drawLine(x + s * .14f, y + s * .69f, x + s * .45f, y + s * .69f, paint)
        canvas.drawLine(x + s * .70f, y + s * .32f, x + s * .70f, y + s * .68f, paint)
        canvas.drawCircle(x + s * .59f, y + s * .71f, s * .11f, paint)
    }

    private fun drawPlay(canvas: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x + s * .35f, y + s * .24f)
        path.lineTo(x + s * .76f, y + s * .50f)
        path.lineTo(x + s * .35f, y + s * .76f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawQueue(canvas: Canvas, x: Float, y: Float, s: Float) {
        for (i in 0..2) {
            val lineY = y + s * (.27f + i * .23f)
            canvas.drawCircle(x + s * .17f, lineY, s * .025f, paint)
            canvas.drawLine(x + s * .28f, lineY, x + s * .78f, lineY, paint)
        }
    }

    private fun drawBluetooth(canvas: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x + s * .48f, y + s * .12f)
        path.lineTo(x + s * .75f, y + s * .36f)
        path.lineTo(x + s * .31f, y + s * .72f)
        path.moveTo(x + s * .48f, y + s * .12f)
        path.lineTo(x + s * .48f, y + s * .88f)
        path.lineTo(x + s * .75f, y + s * .64f)
        path.lineTo(x + s * .31f, y + s * .28f)
        canvas.drawPath(path, paint)
    }

    private fun drawSettings(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawCircle(x, y, s * .18f, paint)
        canvas.drawCircle(x, y, s * .38f, paint)
        canvas.drawLine(x, y - s * .50f, x, y - s * .38f, paint)
        canvas.drawLine(x, y + s * .38f, x, y + s * .50f, paint)
        canvas.drawLine(x - s * .50f, y, x - s * .38f, y, paint)
        canvas.drawLine(x + s * .38f, y, x + s * .50f, y, paint)
    }

    private fun drawStorage(canvas: Canvas, x: Float, y: Float, s: Float) {
        rect.set(x + s * .16f, y + s * .18f, x + s * .84f, y + s * .82f)
        canvas.drawRoundRect(rect, s * .08f, s * .08f, paint)
        canvas.drawLine(x + s * .16f, y + s * .58f, x + s * .84f, y + s * .58f, paint)
        canvas.drawCircle(x + s * .69f, y + s * .70f, s * .035f, paint)
    }

    private fun drawDisplay(canvas: Canvas, x: Float, y: Float, s: Float) {
        rect.set(x + s * .12f, y + s * .17f, x + s * .88f, y + s * .72f)
        canvas.drawRoundRect(rect, s * .06f, s * .06f, paint)
        canvas.drawLine(x + s * .50f, y + s * .72f, x + s * .50f, y + s * .84f, paint)
        canvas.drawLine(x + s * .34f, y + s * .84f, x + s * .66f, y + s * .84f, paint)
    }

    private fun drawDiagnostics(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawLine(x + s * .16f, y + s * .82f, x + s * .16f, y + s * .20f, paint)
        canvas.drawLine(x + s * .16f, y + s * .82f, x + s * .86f, y + s * .82f, paint)
        path.reset()
        path.moveTo(x + s * .25f, y + s * .65f)
        path.lineTo(x + s * .42f, y + s * .48f)
        path.lineTo(x + s * .58f, y + s * .59f)
        path.lineTo(x + s * .79f, y + s * .30f)
        canvas.drawPath(path, paint)
    }

    private fun drawSort(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawLine(x + s * .18f, y + s * .27f, x + s * .82f, y + s * .27f, paint)
        canvas.drawLine(x + s * .18f, y + s * .50f, x + s * .65f, y + s * .50f, paint)
        canvas.drawLine(x + s * .18f, y + s * .73f, x + s * .48f, y + s * .73f, paint)
    }

    private fun drawAdd(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawLine(x - s * .28f, y, x + s * .28f, y, paint)
        canvas.drawLine(x, y - s * .28f, x, y + s * .28f, paint)
    }

    private fun drawInfo(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawCircle(x, y, s * .38f, paint)
        canvas.drawLine(x, y - s * .04f, x, y + s * .23f, paint)
        canvas.drawCircle(x, y - s * .22f, s * .025f, paint)
    }

    private fun drawAction(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawCircle(x + s * .50f, y + s * .50f, s * .34f, paint)
        canvas.drawLine(x + s * .34f, y + s * .50f, x + s * .67f, y + s * .50f, paint)
        canvas.drawLine(x + s * .57f, y + s * .40f, x + s * .67f, y + s * .50f, paint)
        canvas.drawLine(x + s * .57f, y + s * .60f, x + s * .67f, y + s * .50f, paint)
    }

    private fun drawRefresh(canvas: Canvas, x: Float, y: Float, s: Float) {
        rect.set(x + s * .17f, y + s * .17f, x + s * .83f, y + s * .83f)
        canvas.drawArc(rect, -65f, 285f, false, paint)
        canvas.drawLine(x + s * .72f, y + s * .17f, x + s * .84f, y + s * .22f, paint)
        canvas.drawLine(x + s * .72f, y + s * .17f, x + s * .76f, y + s * .31f, paint)
    }

    private fun drawHeadphones(canvas: Canvas, x: Float, y: Float, s: Float) {
        rect.set(x + s * .18f, y + s * .16f, x + s * .82f, y + s * .78f)
        canvas.drawArc(rect, 190f, 160f, false, paint)
        canvas.drawLine(x + s * .18f, y + s * .47f, x + s * .18f, y + s * .76f, paint)
        canvas.drawLine(x + s * .82f, y + s * .47f, x + s * .82f, y + s * .76f, paint)
        canvas.drawLine(x + s * .18f, y + s * .76f, x + s * .31f, y + s * .76f, paint)
        canvas.drawLine(x + s * .69f, y + s * .76f, x + s * .82f, y + s * .76f, paint)
    }

    private fun drawSpeaker(canvas: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x + s * .17f, y + s * .42f)
        path.lineTo(x + s * .35f, y + s * .42f)
        path.lineTo(x + s * .55f, y + s * .25f)
        path.lineTo(x + s * .55f, y + s * .75f)
        path.lineTo(x + s * .35f, y + s * .58f)
        path.lineTo(x + s * .17f, y + s * .58f)
        path.close()
        canvas.drawPath(path, paint)
        rect.set(x + s * .46f, y + s * .29f, x + s * .83f, y + s * .71f)
        canvas.drawArc(rect, -55f, 110f, false, paint)
    }

    private fun drawDisconnected(canvas: Canvas, x: Float, y: Float, s: Float) {
        drawHeadphones(canvas, x, y, s)
        canvas.drawLine(x + s * .14f, y + s * .14f, x + s * .86f, y + s * .86f, paint)
    }

    private fun drawUnknown(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawCircle(x, y, s * .38f, paint)
        rect.set(x - s * .15f, y - s * .25f, x + s * .15f, y + s * .05f)
        canvas.drawArc(rect, 195f, 255f, false, paint)
        canvas.drawLine(x, y + s * .05f, x, y + s * .15f, paint)
        canvas.drawCircle(x, y + s * .26f, s * .025f, paint)
    }

    private fun drawChevron(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawLine(x + s * .38f, y + s * .28f, x + s * .62f, y + s * .50f, paint)
        canvas.drawLine(x + s * .62f, y + s * .50f, x + s * .38f, y + s * .72f, paint)
    }

    private fun drawPause(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawLine(x + s * .38f, y + s * .26f, x + s * .38f, y + s * .74f, paint)
        canvas.drawLine(x + s * .62f, y + s * .26f, x + s * .62f, y + s * .74f, paint)
    }

    private fun drawPrevious(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawLine(x + s * .27f, y + s * .24f, x + s * .27f, y + s * .76f, paint)
        path.reset()
        path.moveTo(x + s * .70f, y + s * .24f)
        path.lineTo(x + s * .34f, y + s * .50f)
        path.lineTo(x + s * .70f, y + s * .76f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawNext(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawLine(x + s * .73f, y + s * .24f, x + s * .73f, y + s * .76f, paint)
        path.reset()
        path.moveTo(x + s * .30f, y + s * .24f)
        path.lineTo(x + s * .66f, y + s * .50f)
        path.lineTo(x + s * .30f, y + s * .76f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawCheck(canvas: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x + s * .18f, y + s * .53f)
        path.lineTo(x + s * .40f, y + s * .73f)
        path.lineTo(x + s * .82f, y + s * .27f)
        canvas.drawPath(path, paint)
    }

    private fun drawPreparing(canvas: Canvas, x: Float, y: Float, s: Float) {
        for (i in 0..2) canvas.drawCircle(x + s * (.28f + i * .22f), y + s * .50f, s * .055f, paint)
    }

    private fun drawWarning(canvas: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x + s * .50f, y + s * .14f)
        path.lineTo(x + s * .87f, y + s * .82f)
        path.lineTo(x + s * .13f, y + s * .82f)
        path.close()
        canvas.drawPath(path, paint)
        canvas.drawLine(x + s * .50f, y + s * .36f, x + s * .50f, y + s * .59f, paint)
        canvas.drawCircle(x + s * .50f, y + s * .70f, s * .025f, paint)
    }

    private fun drawShuffle(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawLine(x + s * .14f, y + s * .30f, x + s * .34f, y + s * .30f, paint)
        canvas.drawLine(x + s * .34f, y + s * .30f, x + s * .67f, y + s * .70f, paint)
        canvas.drawLine(x + s * .67f, y + s * .70f, x + s * .84f, y + s * .70f, paint)
        canvas.drawLine(x + s * .72f, y + s * .58f, x + s * .84f, y + s * .70f, paint)
        canvas.drawLine(x + s * .72f, y + s * .82f, x + s * .84f, y + s * .70f, paint)
        canvas.drawLine(x + s * .14f, y + s * .70f, x + s * .34f, y + s * .70f, paint)
        canvas.drawLine(x + s * .34f, y + s * .70f, x + s * .67f, y + s * .30f, paint)
    }

    private fun drawRepeat(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawLine(x + s * .21f, y + s * .34f, x + s * .75f, y + s * .34f, paint)
        canvas.drawLine(x + s * .75f, y + s * .34f, x + s * .84f, y + s * .44f, paint)
        canvas.drawLine(x + s * .79f, y + s * .66f, x + s * .25f, y + s * .66f, paint)
        canvas.drawLine(x + s * .25f, y + s * .66f, x + s * .16f, y + s * .56f, paint)
    }

    private fun drawTimer(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawCircle(x + s * .50f, y + s * .54f, s * .31f, paint)
        canvas.drawLine(x + s * .50f, y + s * .10f, x + s * .50f, y + s * .20f, paint)
        canvas.drawLine(x + s * .38f, y + s * .10f, x + s * .62f, y + s * .10f, paint)
        canvas.drawLine(x + s * .50f, y + s * .54f, x + s * .50f, y + s * .34f, paint)
        canvas.drawLine(x + s * .50f, y + s * .54f, x + s * .65f, y + s * .61f, paint)
    }

    private fun drawDac(canvas: Canvas, x: Float, y: Float, s: Float) {
        rect.set(x + s * .18f, y + s * .18f, x + s * .82f, y + s * .82f)
        canvas.drawRoundRect(rect, s * .06f, s * .06f, paint)
        for (i in 0..2) {
            val pinX = x + s * (.30f + i * .20f)
            canvas.drawLine(pinX, y + s * .10f, pinX, y + s * .18f, paint)
            canvas.drawLine(pinX, y + s * .82f, pinX, y + s * .90f, paint)
        }
        path.reset()
        path.moveTo(x + s * .29f, y + s * .58f)
        path.lineTo(x + s * .42f, y + s * .40f)
        path.lineTo(x + s * .53f, y + s * .61f)
        path.lineTo(x + s * .70f, y + s * .38f)
        canvas.drawPath(path, paint)
    }
}
