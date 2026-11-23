package com.chavix.seskesici

import kotlin.math.abs

class Region(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w: Int get() = r - l
    val h: Int get() = b - t
}

/** Pure functions: luminance grid of a region of an RGBA_8888 frame, and grid similarity. */
object ScreenMatcher {
    const val GX = 48
    const val GY = 24
    const val TOL = 24
    const val THRESHOLD = 0.75

    fun sample(raw: ByteArray, rowStride: Int, pixStride: Int, w: Int, h: Int, reg: Region): IntArray {
        val out = IntArray(GX * GY)
        for (gy in 0 until GY) {
            for (gx in 0 until GX) {
                val cx = reg.l + ((gx * 2 + 1) * reg.w) / (2 * GX)
                val cy = reg.t + ((gy * 2 + 1) * reg.h) / (2 * GY)
                var sum = 0
                var cnt = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val x = (cx + dx).coerceIn(0, w - 1)
                        val y = (cy + dy).coerceIn(0, h - 1)
                        val o = y * rowStride + x * pixStride
                        if (o + 2 < raw.size) {
                            val r = raw[o].toInt() and 0xFF
                            val g = raw[o + 1].toInt() and 0xFF
                            val b = raw[o + 2].toInt() and 0xFF
                            sum += (77 * r + 150 * g + 29 * b) shr 8
                            cnt++
                        }
                    }
                }
                out[gy * GX + gx] = if (cnt > 0) sum / cnt else 0
            }
        }
        return out
    }

    fun similarity(ref: IntArray, cur: IntArray): Double {
        if (ref.isEmpty() || ref.size != cur.size) return 0.0
        var ok = 0
        for (i in ref.indices) if (abs(ref[i] - cur[i]) <= TOL) ok++
        return ok.toDouble() / ref.size
    }
}
