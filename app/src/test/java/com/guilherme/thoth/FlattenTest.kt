package com.guilherme.thoth

import com.guilherme.thoth.core.Flatten
import com.guilherme.thoth.core.Img
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FlattenTest {
    /** A fake typed page: rows of dark "words" on light paper. bend(x, y) moves the content (page fractions). */
    private fun page(bend: (Double, Double) -> Double): Img {
        val w = 1100; val h = 760
        val rnd = Random(7)
        val ink = Array(h) { BooleanArray(w) }
        var y = 40
        while (y < h - 40) {
            var x = 50
            while (x < w - 60) {
                val len = 20 + rnd.nextInt(70)
                for (yy in y until y + 9) for (xx in x until minOf(w - 50, x + len)) if (rnd.nextFloat() < 0.75f) ink[yy][xx] = true
                x += len + 10
            }
            y += 22
        }
        val v = FloatArray(w * h) { i ->
            val px = i % w; val py = i / w
            val sy = (py - bend(px.toDouble() / w, py.toDouble() / h) * h).toInt()
            if (sy in 0 until h && ink[sy][px]) 0.25f else 0.9f
        }
        return Img.gray(v, w, h)
    }

    @Test fun bentPageBecomesStraight() {
        val bent = page { x, y -> 0.04 * (x - 0.5) * (x - 0.5) * 4 * (0.6 + 0.8 * y) - 0.01 * (x - 0.5) }
        val f = Flatten.measure(bent)
        println("antes: ondulação ${f.wobble} px, ${f.lines} linhas, ${f.why}")
        assertNotNull(f.model)
        assertTrue(f.wobble > 15)
        val out = Flatten.apply(bent, f.model!!)
        val after = Flatten.measure(out)
        println("depois: ondulação ${after.wobble} px")
        assertTrue("lines must end up straight", after.wobble < 4)
        // the photo version (used on the phone) gives the same picture
        val argb = Flatten.applyArgb(bent.toArgb(), bent.w, bent.h, f.model!!)
        val ref = out.toArgb()
        var diff = 0L
        for (i in argb.indices) diff += kotlin.math.abs((argb[i] and 255) - (ref[i] and 255))
        assertTrue("same result in both versions", diff.toDouble() / argb.size < 3)
    }

    @Test fun straightPageIsLeftAlone() {
        val f = Flatten.measure(page { _, _ -> 0.0 })
        println("reta: ${f.why}")
        assertNull(f.model)
    }

    @Test fun blankPageIsLeftAlone() {
        assertNull(Flatten.measure(Img.gray(FloatArray(800 * 600) { 0.9f }, 800, 600)).model)
    }
}
