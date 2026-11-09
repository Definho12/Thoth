package com.guilherme.thoth.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Image as three float channels in 0..1. Treated as immutable: every step returns new arrays. */
class Img(val w: Int, val h: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray) {
    val n get() = w * h
    fun ch(i: Int) = when (i) { 0 -> r; 1 -> g; else -> b }
    val isGray get() = r === g && g === b

    fun luma(): FloatArray {
        if (isGray) return r
        return FloatArray(n) { 0.299f * r[it] + 0.587f * g[it] + 0.114f * b[it] }
    }

    fun toArgb(): IntArray = IntArray(n) {
        val rr = (r[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gg = (g[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bb = (b[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    companion object {
        fun fromArgb(p: IntArray, w: Int, h: Int): Img {
            val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)
            for (i in p.indices) {
                val c = p[i]
                r[i] = ((c shr 16) and 255) / 255f; g[i] = ((c shr 8) and 255) / 255f; b[i] = (c and 255) / 255f
            }
            return Img(w, h, r, g, b)
        }
        fun gray(v: FloatArray, w: Int, h: Int) = Img(w, h, v, v, v)
    }
}

enum class Method(val label: String, val about: String) {
    FUNDO("Padrão", "Tira a sombra e a cor do papel e escurece a tinta, mantendo as cores (o vermelho e a assinatura azul continuam visíveis)."),
    COMBINADO("Preto e branco", "Tira a sombra, usa a cor em que a tinta aparece mais e realça o contraste. Bom para tinta muito apagada."),
    CLAHE("Contraste local", "Aumenta o contraste pedaço por pedaço da folha (CLAHE)."),
    CANAL("Melhor canal", "Usa só a cor em que a tinta aparece mais. Caneta azul apagada costuma aparecer no vermelho."),
    DSTRETCH("Separar cores", "Estica as diferenças de cor (técnica usada por arqueólogos em pinturas apagadas)."),
    ORIGINAL("Original", "A foto como foi tirada."),
}

enum class Channel(val label: String) { AUTO("Auto"), RED("Vermelho"), GREEN("Verde"), BLUE("Azul"), DARKEST("Mais escuro") }

data class Settings(
    val bgSize: Float = 0.4f,     // 0..1: how big the "paper" estimate window is
    val clip: Float = 2f,         // CLAHE strength 1..6
    val channel: Channel = Channel.AUTO,
    val stretch: Float = 1f,      // DStretch 0.3..2
    val ink: Float = 0.5f,        // ink recovery 0..1 (0.5 = automatic)
    val clean: Float = 0.02f,     // clean paper 0..0.15
    val color: Boolean = true,    // keep colors (Padrão)
    val shadowFirst: Boolean = true, // DStretch: remove shadow first
) {
    companion object {
        fun defaultFor(m: Method) = when (m) {
            Method.COMBINADO -> Settings(clean = 0.04f)
            Method.CANAL -> Settings(clean = 0.03f)
            else -> Settings()
        }
    }
}

class Result(val img: Img, val channelUsed: Int?)

object Ink {

    fun process(src: Img, m: Method, s: Settings): Result = when (m) {
        Method.ORIGINAL -> Result(src, null)
        Method.FUNDO -> {
            val d = divideBackground(src, s.bgSize)
            val img = if (s.color) d else Img.gray(d.luma(), d.w, d.h)
            Result(recoverInk(img, s.ink, s.clean), null)
        }
        Method.CLAHE -> Result(claheColor(src, s.clip), null)
        Method.CANAL -> {
            val d = divideBackground(src, s.bgSize)
            val (v, used) = pickChannel(d, s.channel)
            Result(recoverInk(Img.gray(v, d.w, d.h), s.ink, s.clean), used)
        }
        Method.DSTRETCH -> {
            val base = if (s.shadowFirst) divideBackground(src, s.bgSize) else src
            Result(dstretch(base, s.stretch), null)
        }
        Method.COMBINADO -> {
            val d = divideBackground(src, s.bgSize)
            val (v, used) = pickChannel(d, s.channel)
            val c = clahe(v, src.w, src.h, s.clip)
            val again = divideBackground(Img.gray(c, src.w, src.h), s.bgSize)
            val smooth = box(again.r, src.w, src.h, 1)
            Result(recoverInk(Img.gray(smooth, src.w, src.h), s.ink, s.clean), used)
        }
    }

    // ---------------- paper background ----------------

    /** Estimates the paper (brightest nearby) for each channel and divides it out: paper -> 1, ink keeps its ratio. */
    fun divideBackground(src: Img, size: Float): Img {
        if (src.isGray) {
            val v = divideChannel(src.r, src.w, src.h, size); return Img.gray(v, src.w, src.h)
        }
        return Img(src.w, src.h,
            divideChannel(src.r, src.w, src.h, size),
            divideChannel(src.g, src.w, src.h, size),
            divideChannel(src.b, src.w, src.h, size))
    }

    fun background(v: FloatArray, w: Int, h: Int, size: Float): FloatArray {
        val s = 160f / max(w, h)
        val sw = max(4, (w * s).toInt()); val sh = max(4, (h * s).toInt())
        val small = downArea(v, w, h, sw, sh)
        val r = max(1, (1 + size * 7).toInt())
        val bg = box(box(maxFilter(small, sw, sh, r), sw, sh, r), sw, sh, r)
        return upBilinear(bg, sw, sh, w, h)
    }

    private fun divideChannel(v: FloatArray, w: Int, h: Int, size: Float): FloatArray {
        val bg = background(v, w, h, size)
        return FloatArray(v.size) { min(1f, v[it] / max(bg[it], 1e-3f)) }
    }

    /**
     * Makes ink darker. The strength adapts to the page: it measures how dark the darkest ink is
     * and stretches it to near black, so dark typewriter text stays crisp and faint ink is pulled up.
     * ink = 0.5 is the automatic level; 0 halves it, 1 doubles it. clean removes faint noise on the paper.
     */
    fun recoverInk(src: Img, ink: Float, clean: Float): Img {
        val dark = FloatArray(src.n) { 1f - min(src.r[it], min(src.g[it], src.b[it])) }
        val dk = percentile(dark, 0.995f)
        val gain = autoGain(dk) * Math.pow(2.0, (ink * 2f - 1f).toDouble()).toFloat()
        val c = clean.coerceIn(0f, 0.5f)
        fun f(a: FloatArray) = FloatArray(a.size) {
            val d = ((1f - a[it] - c) / (1f - c)).coerceIn(0f, 1f) * gain
            (1f - d).coerceIn(0f, 1f)
        }
        if (src.isGray) return Img.gray(f(src.r), src.w, src.h)
        return Img(src.w, src.h, f(src.r), f(src.g), f(src.b))
    }

    fun autoGain(darkestInk: Float) = max(1f, 0.85f / max(darkestInk, 0.05f))

    // ---------------- best channel ----------------

    /** Returns the chosen channel values and the index used (0 red, 1 green, 2 blue, 3 darkest). */
    fun pickChannel(src: Img, ch: Channel): Pair<FloatArray, Int> = when (ch) {
        Channel.RED -> src.r to 0
        Channel.GREEN -> src.g to 1
        Channel.BLUE -> src.b to 2
        Channel.DARKEST -> FloatArray(src.n) { min(src.r[it], min(src.g[it], src.b[it])) } to 3
        Channel.AUTO -> {
            val i = bestChannel(src); src.ch(i) to i
        }
    }

    /** Channel where ink stands out most from the paper. */
    fun bestChannel(src: Img): Int {
        var best = 0; var bestScore = -1f
        for (c in 0..2) {
            val v = src.ch(c)
            val p50 = percentile(v, 0.5f); val p1 = percentile(v, 0.005f)
            val score = (p50 - p1) / max(p50, 1e-3f)
            if (score > bestScore) { bestScore = score; best = c }
        }
        return best
    }

    fun percentile(v: FloatArray, q: Float): Float {
        val bins = 1024; val hist = IntArray(bins)
        for (x in v) hist[(x.coerceIn(0f, 1f) * (bins - 1)).toInt()]++
        val target = (q * v.size).toLong(); var acc = 0L
        for (i in 0 until bins) { acc += hist[i]; if (acc > target) return i / (bins - 1f) }
        return 1f
    }

    // ---------------- CLAHE ----------------

    fun claheColor(src: Img, clip: Float): Img {
        val y = src.luma()
        val y2 = clahe(y, src.w, src.h, clip)
        if (src.isGray) return Img.gray(y2, src.w, src.h)
        val k = FloatArray(src.n) { y2[it] / max(y[it], 1e-3f) }
        return Img(src.w, src.h,
            FloatArray(src.n) { (src.r[it] * k[it]).coerceIn(0f, 1f) },
            FloatArray(src.n) { (src.g[it] * k[it]).coerceIn(0f, 1f) },
            FloatArray(src.n) { (src.b[it] * k[it]).coerceIn(0f, 1f) })
    }

    fun clahe(v: FloatArray, w: Int, h: Int, clip: Float, tiles: Int = 8): FloatArray {
        val q = IntArray(v.size) { (v[it].coerceIn(0f, 1f) * 255f).toInt() }
        val th = h.toFloat() / tiles; val tw = w.toFloat() / tiles
        val luts = FloatArray(tiles * tiles * 256)
        val hist = FloatArray(256)
        for (ty in 0 until tiles) for (tx in 0 until tiles) {
            java.util.Arrays.fill(hist, 0f)
            val y0 = (ty * th).toInt(); val y1 = max(y0 + 1, ((ty + 1) * th).toInt().coerceAtMost(h))
            val x0 = (tx * tw).toInt(); val x1 = max(x0 + 1, ((tx + 1) * tw).toInt().coerceAtMost(w))
            var count = 0
            for (y in y0 until y1) for (x in x0 until x1) { hist[q[y * w + x]] += 1f; count++ }
            val lim = clip * count / 256f
            var excess = 0f
            for (i in 0 until 256) if (hist[i] > lim) { excess += hist[i] - lim; hist[i] = lim }
            val add = excess / 256f
            var acc = 0f
            val base = (ty * tiles + tx) * 256
            for (i in 0 until 256) { acc += hist[i] + add; luts[base + i] = acc / max(count, 1) }
        }
        val out = FloatArray(v.size)
        for (y in 0 until h) {
            val gy = (y + 0.5f) / th - 0.5f
            val iy = floor(gy).toInt()
            val ya = iy.coerceIn(0, tiles - 1); val yb = (iy + 1).coerceIn(0, tiles - 1)
            val fy = if (gy < 0 || gy > tiles - 1) 0f else gy - iy
            for (x in 0 until w) {
                val gx = (x + 0.5f) / tw - 0.5f
                val ix = floor(gx).toInt()
                val xa = ix.coerceIn(0, tiles - 1); val xb = (ix + 1).coerceIn(0, tiles - 1)
                val fx = if (gx < 0 || gx > tiles - 1) 0f else gx - ix
                val p = q[y * w + x]
                val a = luts[(ya * tiles + xa) * 256 + p]; val b = luts[(ya * tiles + xb) * 256 + p]
                val c = luts[(yb * tiles + xa) * 256 + p]; val d = luts[(yb * tiles + xb) * 256 + p]
                out[y * w + x] = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
            }
        }
        return out
    }

    // ---------------- decorrelation stretch ----------------

    fun dstretch(src: Img, amount: Float): Img {
        val n = src.n
        val mu = DoubleArray(3)
        for (i in 0 until n) { mu[0] += src.r[i]; mu[1] += src.g[i]; mu[2] += src.b[i] }
        for (k in 0..2) mu[k] /= n
        val c = Array(3) { DoubleArray(3) }
        for (i in 0 until n) {
            val d0 = src.r[i] - mu[0]; val d1 = src.g[i] - mu[1]; val d2 = src.b[i] - mu[2]
            c[0][0] += d0 * d0; c[0][1] += d0 * d1; c[0][2] += d0 * d2
            c[1][1] += d1 * d1; c[1][2] += d1 * d2; c[2][2] += d2 * d2
        }
        c[1][0] = c[0][1]; c[2][0] = c[0][2]; c[2][1] = c[1][2]
        for (a in 0..2) for (b in 0..2) c[a][b] /= max(1, n - 1)
        val (lam, vec) = eigenSym3(c)
        val target = sqrt(lam.max().coerceAtLeast(0.0)) * amount
        val sc = DoubleArray(3) { min(30.0, target / sqrt(max(lam[it], 0.0) + 1e-8)) }
        // T = V * diag(sc) * V^T
        val t = Array(3) { a -> DoubleArray(3) { b -> (0..2).sumOf { k -> vec[a][k] * sc[k] * vec[b][k] } } }
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            val d0 = src.r[i] - mu[0]; val d1 = src.g[i] - mu[1]; val d2 = src.b[i] - mu[2]
            r[i] = (t[0][0] * d0 + t[0][1] * d1 + t[0][2] * d2 + mu[0]).toFloat().coerceIn(0f, 1f)
            g[i] = (t[1][0] * d0 + t[1][1] * d1 + t[1][2] * d2 + mu[1]).toFloat().coerceIn(0f, 1f)
            b[i] = (t[2][0] * d0 + t[2][1] * d1 + t[2][2] * d2 + mu[2]).toFloat().coerceIn(0f, 1f)
        }
        return Img(src.w, src.h, r, g, b)
    }

    /** Jacobi eigen decomposition of a symmetric 3x3 matrix. Returns eigenvalues and eigenvectors (columns). */
    fun eigenSym3(m: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val a = Array(3) { m[it].copyOf() }
        val v = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
        repeat(50) {
            var p = 0; var q = 1; var big = abs(a[0][1])
            if (abs(a[0][2]) > big) { p = 0; q = 2; big = abs(a[0][2]) }
            if (abs(a[1][2]) > big) { p = 1; q = 2; big = abs(a[1][2]) }
            if (big < 1e-15) return DoubleArray(3) { a[it][it] } to v
            val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
            val tt = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
            val cs = 1 / sqrt(tt * tt + 1); val sn = tt * cs
            for (k in 0..2) { // rotate columns p,q
                val akp = a[k][p]; val akq = a[k][q]
                a[k][p] = cs * akp - sn * akq; a[k][q] = sn * akp + cs * akq
            }
            for (k in 0..2) { // rotate rows p,q
                val apk = a[p][k]; val aqk = a[q][k]
                a[p][k] = cs * apk - sn * aqk; a[q][k] = sn * apk + cs * aqk
            }
            for (k in 0..2) {
                val vkp = v[k][p]; val vkq = v[k][q]
                v[k][p] = cs * vkp - sn * vkq; v[k][q] = sn * vkp + cs * vkq
            }
        }
        return DoubleArray(3) { a[it][it] } to v
    }

    // ---------------- small helpers ----------------

    fun downArea(v: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) {
            val y0 = y * h / nh; val y1 = max(y0 + 1, (y + 1) * h / nh)
            for (x in 0 until nw) {
                val x0 = x * w / nw; val x1 = max(x0 + 1, (x + 1) * w / nw)
                var s = 0f
                for (yy in y0 until y1) for (xx in x0 until x1) s += v[yy * w + xx]
                out[y * nw + x] = s / ((y1 - y0) * (x1 - x0))
            }
        }
        return out
    }

    fun upBilinear(v: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) {
            val gy = ((y + 0.5f) * h / nh - 0.5f).coerceIn(0f, h - 1f)
            val y0 = gy.toInt(); val y1 = min(y0 + 1, h - 1); val fy = gy - y0
            for (x in 0 until nw) {
                val gx = ((x + 0.5f) * w / nw - 0.5f).coerceIn(0f, w - 1f)
                val x0 = gx.toInt(); val x1 = min(x0 + 1, w - 1); val fx = gx - x0
                val a = v[y0 * w + x0]; val b = v[y0 * w + x1]; val c = v[y1 * w + x0]; val d = v[y1 * w + x1]
                out[y * nw + x] = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
            }
        }
        return out
    }

    fun maxFilter(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(v.size); val out = FloatArray(v.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (k in max(0, x - r)..min(w - 1, x + r)) m = max(m, v[y * w + k])
            t[y * w + x] = m
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (k in max(0, y - r)..min(h - 1, y + r)) m = max(m, t[k * w + x])
            out[y * w + x] = m
        }
        return out
    }

    /** Box blur with edge clamping, radius r (separable, running sum). */
    fun box(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(v.size); val out = FloatArray(v.size); val d = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var s = 0f
            for (k in -r..r) s += v[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                t[row + x] = s / d
                s += v[row + (x + r + 1).coerceIn(0, w - 1)] - v[row + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var s = 0f
            for (k in -r..r) s += t[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = s / d
                s += t[(y + r + 1).coerceIn(0, h - 1) * w + x] - t[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }
}
