package com.chavix.seskesici

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class WaveView(ctx: Context) : View(ctx) {
    var pcm: ShortArray = ShortArray(0)
        set(v) { field = v; peaksW = -1; invalidate() }
    var cursor = 0
        set(v) { field = v; invalidate() }
    var selStart = -1
        set(v) { field = v; invalidate() }
    var selEnd = -1
        set(v) { field = v; invalidate() }
    var onTouched: (() -> Unit)? = null

    val hasSel: Boolean get() = selStart >= 0 && selEnd > selStart

    private var peaks = FloatArray(0)
    private var peaksW = -1
    private var drag = 0 // 0 cursor, 1 selection start, 2 selection end

    private val wavePaint = Paint().apply { color = 0xFF4FC3F7.toInt(); strokeWidth = 1f }
    private val cursorPaint = Paint().apply { color = Color.WHITE; strokeWidth = context.dp(2).toFloat() }
    private val selFill = Paint().apply { color = 0x44FFB300 }
    private val selLine = Paint().apply { color = 0xFFFFB300.toInt(); strokeWidth = context.dp(3).toFloat() }

    private fun toX(s: Int): Float = if (pcm.isEmpty()) 0f else s.toFloat() / pcm.size * width

    private fun computePeaks(w: Int) {
        val n = pcm.size
        peaks = FloatArray(w)
        for (x in 0 until w) {
            val from = (x.toLong() * n / w).toInt()
            val to = ((x + 1).toLong() * n / w).toInt().coerceAtLeast(from + 1).coerceAtMost(n)
            var m = 0
            var i = from
            while (i < to) {
                val a = abs(pcm[i].toInt())
                if (a > m) m = a
                i++
            }
            peaks[x] = m / 32768f
        }
        peaksW = w
    }

    override fun onDraw(c: Canvas) {
        val w = width
        val h = height
        c.drawColor(0xFF161616.toInt())
        if (pcm.isEmpty() || w <= 0) return
        if (peaksW != w) computePeaks(w)
        val mid = h / 2f
        for (x in 0 until w) {
            val a = peaks[x] * mid
            c.drawLine(x.toFloat(), mid - a, x.toFloat(), mid + a + 1f, wavePaint)
        }
        if (hasSel) {
            val x0 = toX(selStart)
            val x1 = toX(selEnd)
            c.drawRect(x0, 0f, x1, h.toFloat(), selFill)
            c.drawLine(x0, 0f, x0, h.toFloat(), selLine)
            c.drawLine(x1, 0f, x1, h.toFloat(), selLine)
        }
        val cx = toX(cursor)
        c.drawLine(cx, 0f, cx, h.toFloat(), cursorPaint)
    }

    private fun applyTouch(s: Int) {
        when (drag) {
            1 -> selStart = s.coerceAtMost(selEnd - 1).coerceAtLeast(0)
            2 -> selEnd = s.coerceAtLeast(selStart + 1).coerceAtMost(pcm.size)
            else -> cursor = s
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (pcm.isEmpty() || width <= 0) return true
        val s = (e.x / width * pcm.size).toInt().coerceIn(0, pcm.size)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onTouched?.invoke()
                val slop = context.dp(28)
                drag = 0
                if (hasSel) {
                    val d0 = abs(e.x - toX(selStart))
                    val d1 = abs(e.x - toX(selEnd))
                    if (d0 <= slop && d0 <= d1) drag = 1 else if (d1 <= slop) drag = 2
                }
                applyTouch(s)
            }
            MotionEvent.ACTION_MOVE -> applyTouch(s)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
