package com.kitt.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

class ScannerView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val segments = 24
    private var position = -10f
    private var animator: ValueAnimator? = null
    private var currentDuration = 0L

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
        for (i in 0 until segments) {
            val dist = Math.abs(i - center)
            val level = (1f - dist / 6f).coerceIn(0.08f, 1f)
            paint.color = Color.argb((255 * level).toInt(), 255, 20, 20)
            val left = i * (segW + gap)
            canvas.drawRoundRect(left, 0f, left + segW, h, 8f, 8f, paint)
        }
    }
}