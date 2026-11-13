package com.guilherme.thoth.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Straightens a curved or tilted page by following its lines of text (like vFlat does with books).
 *
 * Idea: typed text sits on straight, parallel lines. In the photo those lines are bent. We find them,
 * fit one smooth "bend" for the whole page, and move every pixel up or down so the lines become straight.
 * The bend is stored in page fractions (0..1), so it can be measured on a small picture and applied to a big one.
 */
object Flatten {

    /** The bend: source row = target row + shift(x, row). Polynomial in page fractions. */
    class Model(val c: DoubleArray, val xc: Double) {
        fun shift(x: Double, y: Double): Double {
            val dx = x - xc
            var s = 0.0; var k = 0
            for (j in 1..4) { val px = Math.pow(dx, j.toDouble()); for (i in 0..2) { s += c[k++] * px * Math.pow(y, i.toDouble()) } }
            return s
        }
        fun toList() = c.toList() + xc
        companion object { fun fromList(l: List<Double>) = Model(l.dropLast(1).toDoubleArray(), l.last()) }
    }

    /** wobble = how much a typical text line goes up and down from end to end, in pixels (0 = straight). */
    class Found(val model: Model?, val lines: Int, val maxShift: Double, val why: String, val wobble: Double = 0.0)

    private const val TERMS = 12 // j = 1..4 (x), i = 0..2 (y)

    /** Measures the bend. Works on a picture around 1000–1400 px wide. */
    fun measure(src: Img): Found {
        val w = src.w; val h = src.h
        if (w < 200 || h < 200) return Found(null, 0, 0.0, "imagem pequena")
        val flat = Ink.divideBackground(Img.gray(src.luma(), w, h), 0.4f).r
        // ink: clearly darker than the paper
        val ink = FloatArray(w * h) { ((0.82f - flat[it]) / 0.4f).coerceIn(0f, 1f) }
        // smear ink sideways, so letters of a line join into one band
        val smear = boxRows(ink, w, h, max(2, w / 120))

        // text line spacing from the middle of the page
        val strips = 28
        val sw = w / strips
        fun profile(s: Int): FloatArray {
            val x0 = s * sw; val x1 = min(w, x0 + sw)
            val p = FloatArray(h)
            for (y in 0 until h) { var a = 0f; val row = y * w; for (x in x0 until x1) a += smear[row + x]; p[y] = a / (x1 - x0) }
            return p
        }
        val mid = FloatArray(h)
        for (s in strips / 3 until strips * 2 / 3) { val p = profile(s); for (y in 0 until h) mid[y] += p[y] }
        val spacing = lineSpacing(mid) ?: return Found(null, 0, 0.0, "não achei linhas de texto")

        // centers of text lines in each vertical strip
        val r = max(1, (spacing / 6).roundToInt())
        val peaks = Array(strips) { s ->
            val p = smooth(profile(s), r)
            val top = p.maxOrNull() ?: 0f
            val out = mutableListOf<Double>()
            if (top > 0.02f) {
                val minGap = (spacing * 0.55).roundToInt()
                var y = 1
                while (y < h - 1) {
                    if (p[y] >= 0.25f * top && p[y] >= p[y - 1] && p[y] > p[y + 1]) {
                        // keep the highest point within half a line
                        var best = y
                        for (k in y + 1 until min(h - 1, y + minGap)) if (p[k] > p[best]) best = k
                        // sub-pixel center
                        val a = p[max(0, best - 1)]; val b = p[best]; val c = p[min(h - 1, best + 1)]
                        val den = a - 2 * b + c
                        out += best + if (abs(den) > 1e-6f) (0.5f * (a - c) / den).toDouble().coerceIn(-0.5, 0.5) else 0.0
                        y = best + minGap
                    } else y++
                }
            }
            out
        }

        // follow each line from the middle strip to both sides
        data class Pt(val x: Double, val y: Double)
        val center = strips / 2
        val tracks = mutableListOf<MutableList<Pt>>()
        val maxJump = spacing * 0.35
        for (start in peaks[center]) {
            val pts = mutableListOf(Pt((center + 0.5) * sw, start))
            for (dir in intArrayOf(1, -1)) {
                var y = start; var slope = 0.0; var s = center + dir; var misses = 0
                while (s in 0 until strips && misses < 3) {
                    val guess = y + slope * dir * (misses + 1)
                    val hit = peaks[s].minByOrNull { abs(it - guess) }
                    if (hit != null && abs(hit - guess) < maxJump) {
                        slope = 0.6 * slope + 0.4 * (hit - y) / (dir * (misses + 1)) * dir
                        y = hit; misses = 0
                        pts += Pt((s + 0.5) * sw, hit)
                    } else misses++
                    s += dir
                }
            }
            if (pts.size >= strips * 0.35) tracks += pts.sortedBy { it.x }.toMutableList()
        }
        // two tracks that ended on the same line: keep the longer
        val byStart = tracks.sortedByDescending { it.size }
        val kept = mutableListOf<MutableList<Pt>>()
        for (t in byStart) {
            val dup = kept.any { k -> t.count { p -> k.any { abs(it.x - p.x) < 1 && abs(it.y - p.y) < 1.5 } } > t.size / 2 }
            if (!dup) kept += t
        }
        val wobble = kept.map { t -> t.maxOf { it.y } - t.minOf { it.y } }.sorted()[kept.size / 2]
        if (kept.size < 4) return Found(null, kept.size, 0.0, "poucas linhas de texto (${kept.size})", wobble)

        // fit one smooth bend for all lines: y = v_line + shift(x, v_line)
        val xc = 0.5
        var v = DoubleArray(kept.size) { i -> kept[i].minByOrNull { abs(it.x / w - xc) }!!.y / h }
        var c = DoubleArray(TERMS)
        repeat(4) {
            val ata = Array(TERMS) { DoubleArray(TERMS) }; val atb = DoubleArray(TERMS)
            for ((li, t) in kept.withIndex()) for (p in t) {
                val row = basis(p.x / w - xc, v[li])
                val target = p.y / h - v[li]
                for (a in 0 until TERMS) { atb[a] += row[a] * target; for (b in 0 until TERMS) ata[a][b] += row[a] * row[b] }
            }
            for (a in 0 until TERMS) ata[a][a] += 1e-7
            c = solve(ata, atb) ?: return Found(null, kept.size, 0.0, "conta sem solução")
            val m = Model(c, xc)
            v = DoubleArray(kept.size) { li -> kept[li].map { p -> p.y / h - m.shift(p.x / w, v[li]) }.average() }
        }
        val model = Model(c, xc)
        // how far does it move things, and how well does it fit
        // only where there is text: outside it the curve is a guess
        var maxShift = 0.0
        val vlo = v.minOrNull()!!; val vhi = v.maxOrNull()!!
        for (gx in 0..20) for (gy in 0..20) maxShift = max(maxShift, abs(model.shift(gx / 20.0, vlo + (vhi - vlo) * gy / 20.0)))
        val res = kept.indices.flatMap { li -> kept[li].map { p -> abs(p.y / h - v[li] - model.shift(p.x / w, v[li])) } }.sorted()
        val med = res[res.size / 2] * h
        return when {
            maxShift * h < 1.0 || wobble < 1.5 -> Found(null, kept.size, maxShift, "a página já está reta", wobble)
            med > spacing * 0.25 -> Found(null, kept.size, maxShift, "as linhas não formam uma curva clara", wobble)
            maxShift > 0.12 -> Found(null, kept.size, maxShift, "curva grande demais, prefiro não mexer", wobble)
            else -> Found(model, kept.size, maxShift, "ok", wobble)
        }
    }

    /** Moves the pixels so the text lines become straight. Paper color fills what comes from outside the photo. */
    fun apply(src: Img, m: Model): Img {
        val w = src.w; val h = src.h
        // the shift changes slowly: compute it on a coarse grid and blend
        val step = 16
        val gw = w / step + 2; val gh = h / step + 2
        val grid = DoubleArray(gw * gh) { i ->
            val gx = (i % gw) * step; val gy = (i / gw) * step
            m.shift(gx.toDouble() / w, gy.toDouble() / h) * h
        }
        val paper = FloatArray(3) { c -> Ink.percentile(src.ch(c), 0.9f) }
        val outs = Array(if (src.isGray) 1 else 3) { FloatArray(w * h) }
        for (y in 0 until h) {
            val gy = y / step; val fy = (y % step).toDouble() / step
            for (x in 0 until w) {
                val gx = x / step; val fx = (x % step).toDouble() / step
                val i = gy * gw + gx
                val s = (grid[i] * (1 - fx) + grid[i + 1] * fx) * (1 - fy) + (grid[i + gw] * (1 - fx) + grid[i + gw + 1] * fx) * fy
                val sy = y + s
                val y0 = kotlin.math.floor(sy).toInt(); val t = (sy - y0).toFloat()
                for ((ci, out) in outs.withIndex()) {
                    val ch = src.ch(ci)
                    out[y * w + x] = when {
                        y0 < 0 || y0 + 1 >= h -> if (y0 == h - 1 && t == 0f) ch[y0 * w + x] else paper[ci]
                        else -> ch[y0 * w + x] * (1 - t) + ch[(y0 + 1) * w + x] * t
                    }
                }
            }
        }
        return if (src.isGray) Img.gray(outs[0], w, h) else Img(w, h, outs[0], outs[1], outs[2])
    }

    /** Same as apply, straight on the photo's pixels (uses much less memory on big photos). */
    fun applyArgb(px: IntArray, w: Int, h: Int, m: Model): IntArray {
        val step = 16
        val gw = w / step + 2; val gh = h / step + 2
        val grid = DoubleArray(gw * gh) { i -> m.shift((i % gw) * step.toDouble() / w, (i / gw) * step.toDouble() / h) * h }
        // paper color: average of the brightest rows' pixels, sampled
        var pr = 0L; var pg = 0L; var pb = 0L; var n = 0
        val lum = IntArray(256)
        for (i in px.indices step 7) { val c = px[i]; lum[(((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 5 + (c and 255) * 2) / 10]++ }
        var acc = 0; var cut = 255; val total = lum.sum()
        for (l in 255 downTo 0) { acc += lum[l]; if (acc > total / 5) { cut = l; break } }
        for (i in px.indices step 7) {
            val c = px[i]
            if ((((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 5 + (c and 255) * 2) / 10 >= cut) {
                pr += (c shr 16) and 255; pg += (c shr 8) and 255; pb += c and 255; n++
            }
        }
        val paper = if (n == 0) -1 else (0xFF shl 24) or ((pr / n).toInt() shl 16) or ((pg / n).toInt() shl 8) or (pb / n).toInt()
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val gy = y / step; val fy = (y % step).toDouble() / step
            for (x in 0 until w) {
                val gx = x / step; val fx = (x % step).toDouble() / step
                val i = gy * gw + gx
                val s = (grid[i] * (1 - fx) + grid[i + 1] * fx) * (1 - fy) + (grid[i + gw] * (1 - fx) + grid[i + gw + 1] * fx) * fy
                val sy = y + s
                val y0 = kotlin.math.floor(sy).toInt()
                if (y0 < 0 || y0 >= h - 1) { out[y * w + x] = if (y0 == h - 1) px[y0 * w + x] else paper; continue }
                val t = ((sy - y0) * 256).toInt().coerceIn(0, 256)
                val a = px[y0 * w + x]; val b = px[(y0 + 1) * w + x]
                fun mix(sh: Int) = ((((a shr sh) and 255) * (256 - t) + ((b shr sh) and 255) * t) shr 8)
                out[y * w + x] = (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    private fun basis(dx: Double, y: Double): DoubleArray {
        val r = DoubleArray(TERMS); var k = 0
        for (j in 1..4) { val px = Math.pow(dx, j.toDouble()); for (i in 0..2) r[k++] = px * Math.pow(y, i.toDouble()) }
        return r
    }

    /** Sideways-only blur (each row on its own), so lines of text do not melt into each other. */
    private fun boxRows(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val out = FloatArray(v.size); val d = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var s = 0f
            for (k in -r..r) s += v[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                out[row + x] = s / d
                s += v[row + (x + r + 1).coerceIn(0, w - 1)] - v[row + (x - r).coerceIn(0, w - 1)]
            }
        }
        return out
    }

    private fun smooth(p: FloatArray, r: Int): FloatArray {
        val out = FloatArray(p.size)
        var s = 0f
        for (k in -r..r) s += p[k.coerceIn(0, p.size - 1)]
        for (i in p.indices) {
            out[i] = s / (2 * r + 1)
            s += p[(i + r + 1).coerceIn(0, p.size - 1)] - p[(i - r).coerceIn(0, p.size - 1)]
        }
        return out
    }

    /** Distance between lines of text, from the repeating pattern of the row profile. */
    fun lineSpacing(p: FloatArray): Double? {
        val n = p.size
        val mean = p.average().toFloat()
        val d = FloatArray(n) { p[it] - mean }
        val lo = max(6, n / 200); val hi = min(n / 4, 400)
        if (hi <= lo + 2) return null
        val ac = DoubleArray(hi + 2)
        var zero = 0.0; for (i in 0 until n) zero += d[i] * d[i]
        if (zero <= 0) return null
        for (lag in lo..hi + 1) { var s = 0.0; for (i in 0 until n - lag) s += d[i] * d[i + lag]; ac[lag] = s / zero }
        // first clear peak
        var best = -1; var bestV = 0.15
        for (lag in lo + 1..hi) {
            if (ac[lag] > ac[lag - 1] && ac[lag] >= ac[lag + 1] && ac[lag] > bestV) { best = lag; break }
        }
        return if (best < 0) null else best.toDouble()
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-14) return null
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            for (r in 0 until n) if (r != col) {
                val f = m[r][col] / m[col][col]
                for (k in col..n) m[r][k] -= f * m[col][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }
}
