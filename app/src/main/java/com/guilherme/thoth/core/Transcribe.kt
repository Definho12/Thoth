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

    fun page(lines: List<OLine>, minConf: Float): String {
        val rows = rows(lines.filter { it.words.isNotEmpty() })
        if (rows.isEmpty()) return ILEGIVEL
        val heights = rows.map { r -> r.maxOf { it.h } }.sorted()
        val typical = max(1, heights[heights.size / 2])
        val out = StringBuilder()
        var prevBottom: Int? = null
        for (row in rows) {
            val top = row.minOf { it.t }
            if (prevBottom != null) {
                out.append('\n')
                if (top - prevBottom > typical * 1.2f) out.append('\n')
            }
            out.append(rowText(row, minConf, typical))
            prevBottom = row.maxOf { it.b }
        }
        return out.toString()
    }

    fun document(name: String, pages: List<List<OLine>>, minConf: Float): String = buildString {
        append(name).append('\n')
        pages.forEachIndexed { i, p ->
            append("\n— Página ${i + 1} —\n\n")
            append(page(p, minConf)).append('\n')
        }
    }

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

    private fun rowText(row: List<OLine>, minConf: Float, typical: Int): String {
        val parts = StringBuilder()
        var lastRight: Int? = null
        var lastWasIllegible = false
        for (ln in row) {
            for (w in ln.words.sortedBy { it.l }) {
                val illegible = w.conf < minConf
                if (illegible && lastWasIllegible) { lastRight = w.r; continue }
                if (lastRight != null) parts.append(if (w.l - lastRight > typical * 2.5f) "   " else " ")
                parts.append(if (illegible) ILEGIVEL else w.text)
                lastRight = w.r; lastWasIllegible = illegible
            }
        }
        return parts.toString()
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
