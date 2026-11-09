package com.guilherme.thoth

import com.guilherme.thoth.core.Docx
import com.guilherme.thoth.core.OLine
import com.guilherme.thoth.core.OWord
import com.guilherme.thoth.core.Transcribe
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

class DocxTest {
    @Test fun wordFileHasColorsAndIsValidXmlText() {
        val words = listOf("Lote" to 0.97f, "đas" to 0.99f, "Flores" to 0.8f, "x&y" to 0.95f, "zz" to 0.2f)
        var x = 0
        val line = OLine(words.map { (t, c) -> OWord(t, c, x, 0, x + 40, 20).also { x += 50 } })
        val toks = Transcribe.documentTokens("Matrícula 1", listOf(listOf(line)), 0.6f)
        val out = java.io.ByteArrayOutputStream()
        Docx.write(out, "Matrícula 1", toks, 0.6f)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue(entries.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")))
        val doc = entries["word/document.xml"]!!
        assertTrue(doc.contains("w:fill=\"FFC98A\"")) // "das" was guessed: doubt
        assertTrue(doc.contains("w:fill=\"FFF0A0\"")) // Flores: almost sure
        assertTrue(doc.contains("(ilegível)"))
        assertTrue(doc.contains("x&amp;y"))
        assertTrue(!doc.contains("đ"))
        System.getProperty("amostras")?.let { java.io.File(it).apply { mkdirs() }.resolve("teste.docx").writeBytes(out.toByteArray()) }
    }
}
