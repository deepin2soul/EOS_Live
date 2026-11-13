package com.example.eoslive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Показывает кадр Live View. Два пальца — плавное увеличение (до ×10) в точке между пальцами,
 * один палец — перемещение, двойной тап — сброс.
 */
class ZoomView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private val m = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var user = 1f
    private var tx = 0f
    private var ty = 0f
    private val maxUser = 10f

    /**
     * Желаемое соотношение сторон картинки на экране (ширина/высота). 0 = как у самого JPEG.
     */
    private var aspect = 0f

    /**
     * Если > 0 — показывается только центральная часть кадра с таким соотношением сторон
     * (то, что попадёт в запись при включённой обрезке), остальное отбрасывается.
     */
    private var crop = 0f

    /** Какая часть кадра видна и в каких пропорциях её показывать. */
    private class Geo(val src: Rect, val dispW: Float, val dispH: Float)

    private fun geo(b: Bitmap): Geo {
        val bw = b.width
        val bh = b.height
        val e = if (aspect > 0f) aspect else bw.toFloat() / bh
        var sw = bw
        var sh = bh
        var da = e
        if (crop > 0f && e < crop) {            // кадр «выше» нужного — режем сверху и снизу
            sh = (bh * e / crop).toInt().coerceIn(1, bh); da = crop
        } else if (crop > 0f && e > crop) {     // кадр «шире» нужного — режем по бокам
            sw = (bw * crop / e).toInt().coerceIn(1, bw); da = crop
        }
        val l = (bw - sw) / 2
        val t = (bh - sh) / 2
        return Geo(Rect(l, t, l + sw, t + sh), sh * da, sh.toFloat())
    }

    private fun fit(b: Bitmap): Float {
        val g = geo(b)
        return minOf(width.toFloat() / g.dispW, height.toFloat() / g.dispH)
    }

    private fun clamp() {
        val b = bmp ?: return
        val s = fit(b) * user
        val g = geo(b)
        val mx = maxOf(0f, g.dispW * s / 2f - width / 2f)
        val my = maxOf(0f, g.dispH * s / 2f - height / 2f)
        tx = tx.coerceIn(-mx, mx)
        ty = ty.coerceIn(-my, my)
    }

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val old = user
            user = (user * d.scaleFactor).coerceIn(1f, maxUser)
            val r = user / old
            val fx = d.focusX - width / 2f
            val fy = d.focusY - height / 2f
            tx = fx - (fx - tx) * r
            ty = fy - (fy - ty) * r
            clamp()
            invalidate()
            return true
        }
    })

    private val gestDet = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDet.isInProgress) return false
            tx -= dx
            ty -= dy
            clamp()
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            user = 1f; tx = 0f; ty = 0f
            invalidate()
            return true
        }
    })

    /** Задать цифровое увеличение (1 = вписать в экран) и вернуть картинку в центр. */
    fun setUserZoom(v: Float) {
        user = v.coerceIn(1f, maxUser)
        tx = 0f; ty = 0f
        clamp()
        invalidate()
    }

    fun frameSize(): String = bmp?.let { "${it.width}x${it.height}" } ?: "—"

    fun setFrame(b: Bitmap, displayAspect: Float = 0f, cropAspect: Float = 0f) {
        bmp = b
        aspect = displayAspect
        crop = cropAspect
        clamp()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        clamp()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        gestDet.onTouchEvent(e)
        return true
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        val g = geo(b)
        val s = fit(b) * user
        val cx = width / 2f + tx
        val cy = height / 2f + ty
        val hw = g.dispW * s / 2f
        val hh = g.dispH * s / 2f
        c.drawBitmap(b, g.src, RectF(cx - hw, cy - hh, cx + hw, cy + hh), paint)
    }
}
