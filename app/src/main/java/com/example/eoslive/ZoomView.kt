package com.example.eoslive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
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

    private fun fit(b: Bitmap): Float =
        minOf(width.toFloat() / b.width, height.toFloat() / b.height)

    private fun clamp() {
        val b = bmp ?: return
        val s = fit(b) * user
        val mx = maxOf(0f, b.width * s / 2f - width / 2f)
        val my = maxOf(0f, b.height * s / 2f - height / 2f)
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

    fun setFrame(b: Bitmap) {
        bmp = b
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
        val s = fit(b) * user
        m.reset()
        m.postTranslate(-b.width / 2f, -b.height / 2f)
        m.postScale(s, s)
        m.postTranslate(width / 2f + tx, height / 2f + ty)
        c.drawBitmap(b, m, paint)
    }
}
