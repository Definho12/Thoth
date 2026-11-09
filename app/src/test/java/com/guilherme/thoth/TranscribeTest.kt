package com.guilherme.thoth

import com.guilherme.thoth.core.OLine
import com.guilherme.thoth.core.OWord
import com.guilherme.thoth.core.Transcribe
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscribeTest {
    /** Builds a line of words at height y, starting at x, each word 10 px per letter. */
    private fun line(y: Int, x: Int, vararg words: Pair<String, Float>): OLine {
        var cx = x
        return OLine(words.map { (t, c) -> OWord(t, c, cx, y, cx + t.length * 10, y + 20).also { cx += t.length * 10 + 10 } })
    }
    private fun ok(vararg t: String) = t.map { it to 0.95f }.toTypedArray()

    @Test fun keepsOriginalSpellingAndMarksUnsureWords() {
        val lines = listOf(
            line(100, 0, *ok("PROPRIETARIO:Antonio", "Carlos", "Valente,"), "casado,".to(0.9f)),
            line(130, 0, "Lote".to(0.97f), "terreno".to(0.2f), "e".to(0.3f), "uma".to(0.96f), "caza".to(0.9f)),
        )
        val t = Transcribe.page(lines, 0.6f)
        assertEquals("PROPRIETARIO:Antonio Carlos Valente, casado,\nLote (ilegível) uma caza", t)
    }

    @Test fun sameHeightLinesJoinLeftToRight() {
        // the reader may return the right-hand part first
        val right = line(50, 900, *ok("Nº", "Anterior", "L.02"))
        val left = line(52, 0, *ok("Matrícula", "321"))
        val next = line(80, 0, *ok("Data", "26.04.83"))
        assertEquals("Matrícula 321   Nº Anterior L.02\nData 26.04.83", Transcribe.page(listOf(right, next, left), 0.6f))
    }

    @Test fun bigGapBecomesNewParagraph() {
        val a = line(100, 0, *ok("assino"))
        val b = line(200, 0, *ok("REG.01"))
        assertEquals("assino\n\nREG.01", Transcribe.page(listOf(a, b), 0.6f))
    }

    @Test fun nothingReadIsIllegible() {
        assertEquals("(ilegível)", Transcribe.page(emptyList(), 0.6f))
    }

    @Test fun documentHasPageHeaders() {
        val p = listOf(line(0, 0, *ok("texto")))
        assertEquals("Matrícula 321\n\n— Página 1 —\n\ntexto\n\n— Página 2 —\n\ntexto\n", Transcribe.document("Matrícula 321", listOf(p, p), 0.6f))
    }

    @Test fun naturalOrderOfFiles() {
        val l = listOf("foto 10.jpg", "foto 2.jpg", "Foto 1.jpg").sortedWith(Transcribe.naturalOrder)
        assertEquals(listOf("Foto 1.jpg", "foto 2.jpg", "foto 10.jpg"), l)
    }

    @Test fun eachWordKeepsHowSureItWas() {
        val lines = listOf(line(0, 0, "Valente".to(0.97f), "casado".to(0.8f), "Souza".to(0.65f), "xx".to(0.3f), "yy".to(0.1f)))
        val t = Transcribe.pageTokens(lines, 0.6f).filter { it.conf != null }
        assertEquals(listOf("Valente", "casado", "Souza", "(ilegível)"), t.map { it.text })
        assertEquals(listOf(3, 2, 1, 0), t.map { Transcribe.level(it, 0.6f) })
        assertEquals(0.1f, t.last().conf!!, 1e-6f)
    }
}
