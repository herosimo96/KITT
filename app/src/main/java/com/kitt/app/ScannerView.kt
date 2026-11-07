package com.kitt.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

fun mixColors(a: Int, b: Int, t: Float): Int {
    val r = (Color.red(a) * (1f - t) + Color.red(b) * t).toInt()
    val g = (Color.green(a) * (1f - t) + Color.green(b) * t).toInt()
    val bl = (Color.blue(a) * (1f - t) + Color.blue(b) * t).toInt()
    return Color.rgb(r, g, bl)
}

fun withAlpha(c: Int, a: Int): Int = (a shl 24) or (c and 0x00FFFFFF)

class ScannerView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val segments = 24
    private var position = -10f
    private var animator: ValueAnimator? = null
    private var currentDuration = 0L

    var color: Int = Color.RED
        set(value) {
            field = value
            invalidate()
        }

    fun start(durationMs: Long = 900L) {
        if (animator?.isRunning == true && currentDuration == durationMs) return
        animator?.cancel()
        currentDuration = durationMs
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        position = -10f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w * 0.008f
        val segW = (w - gap * (segments - 1)) / segments
        val center = position * (segments - 1)
        val cr = Color.red(color)
        val cg = Color.green(color)
        val cb = Color.blue(color)
        val rad = minOf(8f, h / 2f)
        for (i in 0 until segments) {
            val dist = Math.abs(i - center)
            val level = (1f - dist / 6f).coerceIn(0.08f, 1f)
            val left = i * (segW + gap)
            if (level > 0.35f) {
                paint.color = Color.argb((80 * level).toInt(), cr, cg, cb)
                canvas.drawRoundRect(left - gap * 1.5f, 0f, left + segW + gap * 1.5f, h, rad, rad, paint)
            }
            paint.color = Color.argb((255 * level).toInt(), cr, cg, cb)
            canvas.drawRoundRect(left, h * 0.1f, left + segW, h * 0.9f, rad, rad, paint)
        }
    }
}