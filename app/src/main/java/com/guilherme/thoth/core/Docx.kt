package com.guilherme.thoth.core

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the transcript as a Word file (.docx) with the same colors as the screen, so it can be read and
 * corrected on the computer (Word, LibreOffice, Google Docs). Built by hand: a .docx is a zip of a few XML files.
 */
object Docx {
    // background of each level: 0 illegible, 1 doubt, 2 almost sure (3 = sure, no color)
    private val FILL = listOf("F7A8A8", "FFC98A", "FFF0A0")
    private const val RED_TEXT = "9B1C1C"

    fun write(out: OutputStream, title: String, tokens: List<Transcribe.Tok>, minConf: Float) {
        val words = tokens.filter { it.conf != null }
        val letters = words.sumOf { it.text.length }.coerceAtLeast(1)
        val surePct = words.filter { Transcribe.level(it, minConf) == 3 }.sumOf { it.text.length } * 100 / letters
        val illegible = words.count { it.text == Transcribe.ILEGIVEL }

        val body = StringBuilder()
        body.append(para(run(title, bold = true, size = 28)))
        body.append(para(
            run("Legenda:  ", size = 18) + run("certeza (90% ou mais)", size = 18) + run("   ", size = 18) +
                run("quase certo", level = 2, size = 18) + run("   ", size = 18) +
                run("dúvida", level = 1, size = 18) + run("   ", size = 18) +
                run(Transcribe.ILEGIVEL, level = 0, size = 18),
        ))
        body.append(para(run(
            "$surePct% do texto com certeza alta · " +
                (if (illegible == 1) "1 trecho ilegível" else "$illegible trechos ilegíveis") +
                " · \"ilegível\" abaixo de ${(minConf * 100).toInt()}% · transcrição automática (Thoth), confira com a matrícula.",
            size = 16, color = "666666",
        )))

        // the text itself (skip the first piece: it is the name, already written as the title)
        var line = StringBuilder()
        var runText = StringBuilder(); var runLevel: Int? = null
        fun flushRun() { if (runText.isNotEmpty()) { line.append(run(runText.toString(), level = runLevel, mono = true)); runText = StringBuilder() } }
        fun flushLine() { flushRun(); body.append(para(line.toString())); line = StringBuilder() }
        for (t in tokens.drop(1)) {
            val lv = Transcribe.level(t, minConf)?.takeIf { it < 3 }
            val parts = t.text.split('\n')
            parts.forEachIndexed { i, part ->
                if (i > 0) flushLine()
                if (part.isEmpty()) return@forEachIndexed
                // spaces take the color of nothing; words keep their own
                if (t.conf == null) { if (runLevel != null) flushRun(); runLevel = null }
                else if (lv != runLevel) { flushRun(); runLevel = lv }
                runText.append(part)
            }
        }
        flushLine()

        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1000" w:right="900" w:bottom="1000" w:left="900" w:header="0" w:footer="0" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val types = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", types)
            put("_rels/.rels", rels)
            put("word/document.xml", document)
        }
    }

    private fun para(runs: String) =
        "<w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"250\" w:lineRule=\"auto\"/></w:pPr>$runs</w:p>"

    private fun run(text: String, level: Int? = null, bold: Boolean = false, size: Int = 18, mono: Boolean = false, color: String? = null): String {
        val p = StringBuilder("<w:rPr>")
        val font = if (mono) "Courier New" else "Calibri"
        p.append("<w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\" w:cs=\"$font\"/>")
        if (bold || level == 0) p.append("<w:b/>")
        val c = color ?: if (level == 0) RED_TEXT else null
        if (c != null) p.append("<w:color w:val=\"$c\"/>")
        p.append("<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/>")
        if (level != null && level in 0..2) p.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${FILL[level]}\"/>")
        p.append("</w:rPr>")
        return "<w:r>$p<w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"
    }

    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            else -> if (c.code >= 32 || c == '\t') append(c)
        }
    }
}
