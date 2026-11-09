package com.guilherme.thoth.core

import kotlin.math.max
import kotlin.math.min

/** A word read from the page, with its box and how sure the reader is (0..1). */
data class OWord(val text: String, val conf: Float, val l: Int, val t: Int, val r: Int, val b: Int)

/** A line as the reader returned it (words in order). */
data class OLine(val words: List<OWord>) {
    val l get() = words.minOf { it.l }
    val t get() = words.minOf { it.t }
    val r get() = words.maxOf { it.r }
    val b get() = words.maxOf { it.b }
    val h get() = b - t
    val cy get() = (t + b) / 2f
}

/**
 * Turns what the reader saw into plain text, word for word.
 * Nothing is corrected: spelling, abbreviations and mistakes of the original stay as they are.
 * Words the reader is not sure about become "(ilegível)"; several in a row become one.
 */
object Transcribe {
    const val ILEGIVEL = "(ilegível)"

    /** Highest sureness a word can show after a strange letter was swapped: it shows as "dúvida". */
    const val GUESSED = 0.74f

    private const val LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZáàâãéêíóôõúüçÁÀÂÃÉÊÍÓÔÕÚÜÇ"
    private const val OTHERS = "0123456789 .,;:!?-–—/\\()[]{}\"'ºª°%$&@*+=#§²³_<>|"
    private val ALLOWED = (LETTERS + OTHERS).toSet()

    /** Typographic signs with a plain equivalent: not a doubt, just a different drawing of the same sign. */
    private val PLAIN = mapOf(
        '“' to "\"", '”' to "\"", '„' to "\"", '«' to "\"", '»' to "\"", '‘' to "'", '’' to "'", '‚' to "'", '`' to "'", '´' to "'",
        '…' to "...", '‐' to "-", '‑' to "-", '‒' to "-", '−' to "-", '·' to ".", '•' to ".", '×' to "x", '¹' to "1", '⁰' to "0",
    )

    /** Letters from other alphabets that the reader uses when it is unsure, mapped to the closest Portuguese letter. */
    private val CLOSEST = mapOf(
        'đ' to "d", 'Đ' to "D", 'ð' to "d", 'Ð' to "D", 'ł' to "l", 'Ł' to "L", 'ƚ' to "l", 'ŀ' to "l", 'ø' to "o", 'Ø' to "O",
        'ħ' to "h", 'Ħ' to "H", 'ı' to "i", 'ɨ' to "i", 'ȷ' to "j", 'ƒ' to "f", 'ß' to "ss", 'æ' to "ae", 'Æ' to "AE", 'œ' to "oe", 'Œ' to "OE",
        'ŧ' to "t", 'Ŧ' to "T", 'ɑ' to "a", 'ɡ' to "g", 'ſ' to "s", 'ⱥ' to "a", 'Ⱥ' to "A", 'ɇ' to "e", 'Ɇ' to "E", 'ƀ' to "b", 'Ƀ' to "B",
        'ȼ' to "c", 'Ȼ' to "C", 'ʉ' to "u", 'Ʉ' to "U", 'ɏ' to "y", 'Ɏ' to "Y", 'ƶ' to "z", 'Ƶ' to "Z", 'ǥ' to "g", 'Ǥ' to "G", 'ɍ' to "r", 'Ɍ' to "R",
        'ƛ' to "l", 'ɵ' to "o", 'ꝋ' to "o", 'ꝁ' to "k", 'ꞁ' to "l", 'ɩ' to "i", 'ʋ' to "u", 'ɛ' to "e", 'ɔ' to "o", 'ŋ' to "n", 'ɲ' to "n",
        'і' to "i", 'о' to "o", 'а' to "a", 'е' to "e", 'с' to "c", 'р' to "p", 'х' to "x", 'у' to "y", 'І' to "I", 'О' to "O", 'А' to "A",
        'Е' to "E", 'С' to "C", 'Р' to "P", 'Х' to "X", 'В' to "B", 'Н' to "H", 'К' to "K", 'М' to "M", 'Т' to "T",
    )

    /**
     * Keeps only signs of the Portuguese alphabet. A letter from another alphabet (đ, ⱥ, ā…) becomes the closest
     * normal letter, and the word is flagged as a guess so it shows as a doubt. Returns the text and whether it guessed.
     */
    fun clean(word: String): Pair<String, Boolean> {
        val out = StringBuilder(); var guessed = false
        for (c in word) {
            if (c in ALLOWED) { out.append(c); continue }
            val plain = PLAIN[c]
            if (plain != null) { out.append(plain); continue }
            val closest = CLOSEST[c]
            if (closest != null) { out.append(closest); guessed = true; continue }
            val d = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
            val base = d.firstOrNull()
            if (base != null && base in ALLOWED && base.isLetter()) {
                // keep an accent that exists in Portuguese (é, ã, ç...), drop the others (ā, ȧ, ę...)
                val kept = d.drop(1).map { java.text.Normalizer.normalize("$base$it", java.text.Normalizer.Form.NFC) }
                    .firstOrNull { it.length == 1 && it[0] in ALLOWED }
                out.append(kept ?: base.toString()); guessed = true; continue
            }
            // last try: the Unicode name ("LATIN SMALL LETTER A WITH STROKE" -> a)
            val name = runCatching { Character.getName(c.code) }.getOrNull()
            val m = name?.let { Regex("^LATIN (?:SMALL |CAPITAL )?LETTER (?:[A-Z-]+ )*?([A-Z])\\b").find(it) }
            if (m != null) {
                val l = m.groupValues[1]
                out.append(if (name.startsWith("LATIN CAPITAL LETTER")) l else l.lowercase()); guessed = true; continue
            }
            if (!c.isWhitespace()) guessed = true // unknown sign: dropped
        }
        return out.toString() to guessed
    }

    /** A piece of the text. conf = how sure the reader was of this word; null for spaces, line breaks and headers. */
    data class Tok(val text: String, val conf: Float? = null)

    /** How sure: 3 = sure (90% or more), 2 = fairly sure, 1 = unsure, 0 = illegible. */
    fun level(t: Tok, minConf: Float): Int? {
        val c = t.conf ?: return null
        return when { c < minConf -> 0; c < 0.75f -> 1; c < 0.9f -> 2; else -> 3 }
    }

    fun pageTokens(lines: List<OLine>, minConf: Float): List<Tok> {
        val rows = rows(lines.filter { it.words.isNotEmpty() })
        if (rows.isEmpty()) return listOf(Tok(ILEGIVEL, 0f))
        val heights = rows.map { r -> r.maxOf { it.h } }.sorted()
        val typical = max(1, heights[heights.size / 2])
        val out = mutableListOf<Tok>()
        var prevBottom: Int? = null
        for (row in rows) {
            val top = row.minOf { it.t }
            if (prevBottom != null) out += Tok(if (top - prevBottom > typical * 1.2f) "\n\n" else "\n")
            rowTokens(row, minConf, typical, out)
            prevBottom = row.maxOf { it.b }
        }
        return out
    }

    fun page(lines: List<OLine>, minConf: Float) = pageTokens(lines, minConf).joinToString("") { it.text }

    fun documentTokens(name: String, pages: List<List<OLine>>, minConf: Float): List<Tok> {
        val out = mutableListOf(Tok(name + "\n"))
        pages.forEachIndexed { i, p ->
            out += Tok("\n— Página ${i + 1} —\n\n")
            out += pageTokens(p, minConf)
            out += Tok("\n")
        }
        return out
    }

    fun document(name: String, pages: List<List<OLine>>, minConf: Float) =
        documentTokens(name, pages, minConf).joinToString("") { it.text }

    /** Groups lines that sit at the same height (for example "Matrícula 321 ..." on the left and "Nº Anterior" on the right). */
    fun rows(lines: List<OLine>): List<List<OLine>> {
        val sorted = lines.sortedBy { it.cy }
        val rows = mutableListOf<MutableList<OLine>>()
        for (ln in sorted) {
            val row = rows.lastOrNull()
            if (row != null) {
                val rt = row.minOf { it.t }; val rb = row.maxOf { it.b }
                val overlap = min(rb, ln.b) - max(rt, ln.t)
                if (overlap > 0.5f * min(rb - rt, ln.h)) { row += ln; continue }
            }
            rows += mutableListOf(ln)
        }
        return rows.map { r -> r.sortedBy { it.l } }
    }

    private fun rowTokens(row: List<OLine>, minConf: Float, typical: Int, out: MutableList<Tok>) {
        var lastRight: Int? = null
        var lastIllegible: Int? = null // index of the last "(ilegível)" in out, while illegible words keep coming
        for (ln in row) {
            for (w in ln.words.sortedBy { it.l }) {
                val (txt, guessed) = clean(w.text)
                val conf = if (txt.isEmpty()) 0f else if (guessed) min(w.conf, GUESSED) else w.conf
                val illegible = conf < minConf
                if (illegible && lastIllegible != null) {
                    out[lastIllegible] = Tok(ILEGIVEL, min(out[lastIllegible].conf!!, conf)); lastRight = w.r; continue
                }
                if (lastRight != null) out += Tok(if (w.l - lastRight > typical * 2.5f) "   " else " ")
                out += Tok(if (illegible) ILEGIVEL else txt, conf)
                lastIllegible = if (illegible) out.size - 1 else null
                lastRight = w.r
            }
        }
    }

    /** Average sureness, weighted by word length: used to choose between the original and the cleaned-up picture. */
    fun score(lines: List<OLine>): Float {
        var s = 0f; var n = 0
        for (ln in lines) for (w in ln.words) { s += w.conf * w.text.length; n += w.text.length }
        return if (n == 0) 0f else s / n * min(1f, n / 40f)
    }

    /** Sorts file names the way people do: "foto 2" before "foto 10". */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until min(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size - rb.size
    }
}
