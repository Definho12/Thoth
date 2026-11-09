package com.guilherme.thoth

import com.guilherme.thoth.core.Img
import com.guilherme.thoth.core.Ink
import com.guilherme.thoth.core.Method
import com.guilherme.thoth.core.Pdf
import com.guilherme.thoth.core.PdfPage
import com.guilherme.thoth.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class InkTest {

    private fun sample(): Img {
        val bi = ImageIO.read(javaClass.getResourceAsStream("/exemplo.jpg"))
        val w = bi.width; val h = bi.height
        return Img.fromArgb(bi.getRGB(0, 0, w, h, null, 0, w), w, h)
    }

    private fun save(img: Img, name: String) {
        val dir = System.getProperty("amostras") ?: return
        File(dir).mkdirs()
        val bi = BufferedImage(img.w, img.h, BufferedImage.TYPE_INT_RGB)
        bi.setRGB(0, 0, img.w, img.h, img.toArgb(), 0, img.w)
        ImageIO.write(bi, "png", File(dir, "$name.png"))
    }

    /** Average brightness of a rectangle given in fractions of the page. */
    private fun mean(v: FloatArray, img: Img, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        var s = 0f; var c = 0
        for (y in (y0 * img.h).toInt() until (y1 * img.h).toInt())
            for (x in (x0 * img.w).toInt() until (x1 * img.w).toInt()) { s += v[y * img.w + x]; c++ }
        return s / c
    }

    @Test fun allMethodsProduceSamples() {
        val src = sample()
        for (m in Method.entries) {
            val t = System.currentTimeMillis()
            val r = Ink.process(src, m, Settings.defaultFor(m))
            println("${m.name}: ${System.currentTimeMillis() - t} ms")
            assertEquals(src.n, r.img.n)
            save(r.img, "${m.ordinal}_${m.name.lowercase()}")
        }
    }

    @Test fun combinedEvensOutShadowAndDarkensInk() {
        val src = sample()
        val out = Ink.process(src, Method.COMBINADO, Settings.defaultFor(Method.COMBINADO)).img
        val y0 = src.luma(); val y = out.luma()
        // empty paper: bright top-right corner vs dark bottom-left corner
        val litBefore = mean(y0, src, 0.8f, 0.85f, 0.95f, 0.95f)
        val darkBefore = mean(y0, src, 0.05f, 0.85f, 0.2f, 0.95f)
        val litAfter = mean(y, out, 0.8f, 0.85f, 0.95f, 0.95f)
        val darkAfter = mean(y, out, 0.05f, 0.85f, 0.2f, 0.95f)
        println("paper before %.2f / %.2f, after %.2f / %.2f".format(litBefore, darkBefore, litAfter, darkAfter))
        assertTrue("shadow should be removed", abs(litAfter - darkAfter) < 0.06f)
        assertTrue(litAfter > 0.9f)
        // faded ink must become dark
        val ink = Ink.percentile(y, 0.005f)
        println("darkest ink after: %.2f (before %.2f)".format(ink, Ink.percentile(y0, 0.005f)))
        assertTrue("ink should be dark", ink < 0.35f)
    }

    @Test fun fadedBlueInkShowsInRed() {
        val d = Ink.divideBackground(sample(), 0.4f)
        assertEquals(0, Ink.bestChannel(d))
    }

    @Test fun eigenReconstructs() {
        val m = arrayOf(doubleArrayOf(4.0, 1.0, 0.5), doubleArrayOf(1.0, 3.0, 0.2), doubleArrayOf(0.5, 0.2, 1.0))
        val (l, v) = Ink.eigenSym3(m)
        for (a in 0..2) for (b in 0..2) {
            val r = (0..2).sumOf { v[a][it] * l[it] * v[b][it] }
            assertEquals(m[a][b], r, 1e-9)
        }
    }

    @Test fun claheStaysInRange() {
        val src = sample()
        val c = Ink.clahe(src.luma(), src.w, src.h, 4f)
        assertTrue(c.all { it in 0f..1f })
    }

    @Test fun darkTextIsNotOverDarkened() {
        // already dark typewriter text: automatic ink recovery must not push it further
        assertEquals(1f, Ink.autoGain(0.9f), 1e-6f)
        assertTrue(Ink.autoGain(0.2f) > 4f)
    }

    @Test fun pdfHasAllPages() {
        val bi = BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB)
        val jpg = java.io.ByteArrayOutputStream().also { ImageIO.write(bi, "jpg", it) }.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        Pdf.write(out, listOf(PdfPage(jpg, 40, 60), PdfPage(jpg, 40, 60), PdfPage(jpg, 40, 60)), "Matrícula 321")
        val bytes = out.toByteArray(); val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.contains("/Count 3"))
        // every xref offset must point at the start of its object
        val xref = text.substring(text.lastIndexOf("startxref") + 10).trim().lines()[0].toInt()
        val entries = text.substring(xref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(12, entries.size)
        entries.forEachIndexed { i, e -> assertTrue(text.startsWith("${i + 1} 0 obj", e.take(10).toInt())) }
        File(System.getProperty("amostras") ?: return).let { it.mkdirs(); File(it, "teste.pdf").writeBytes(bytes) }
    }
}
