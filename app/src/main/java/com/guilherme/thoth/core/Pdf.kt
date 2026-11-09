package com.guilherme.thoth.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** One page = one JPEG picture filling the whole page. */
class PdfPage(val jpeg: ByteArray, val w: Int, val h: Int)

/** Tiny PDF writer: each page holds one JPEG image (kept as JPEG, so the file stays small). */
object Pdf {
    /** dpi decides the paper size: a 2400 px picture at 200 dpi is 12 inches long. */
    fun write(out: OutputStream, pages: List<PdfPage>, title: String, dpi: Float = 200f) {
        require(pages.isNotEmpty())
        val buf = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun ascii(s: String) = buf.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(body: () -> Unit) { offsets += buf.size(); ascii("${offsets.size} 0 obj\n"); body(); ascii("\nendobj\n") }

        ascii("%PDF-1.4\n%âãÏÓ\n")
        // object numbers: 1 catalog, 2 pages, 3 info, then 3 per page (page, content, image)
        val first = 4
        val kids = pages.indices.joinToString(" ") { "${first + it * 3} 0 R" }
        obj { ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        obj { ascii("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>") }
        obj { ascii("<< /Title (${escape(title)}) /Producer (Thoth) >>") }
        for ((i, p) in pages.withIndex()) {
            val pw = p.w * 72f / dpi; val ph = p.h * 72f / dpi
            val n = first + i * 3
            obj {
                ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pw)} ${f(ph)}] " +
                    "/Resources << /XObject << /Im0 ${n + 2} 0 R >> >> /Contents ${n + 1} 0 R >>")
            }
            val content = "q ${f(pw)} 0 0 ${f(ph)} 0 0 cm /Im0 Do Q"
            obj { ascii("<< /Length ${content.length} >>\nstream\n$content\nendstream") }
            obj {
                ascii("<< /Type /XObject /Subtype /Image /Width ${p.w} /Height ${p.h} /ColorSpace /DeviceRGB " +
                    "/BitsPerComponent 8 /Filter /DCTDecode /Length ${p.jpeg.size} >>\nstream\n")
                buf.write(p.jpeg); ascii("\nendstream")
            }
        }
        val xref = buf.size()
        ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) ascii(String.format("%010d 00000 n \n", o))
        ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
        buf.writeTo(out)
    }

    private fun f(v: Float) = String.format(java.util.Locale.US, "%.2f", v)

    private fun escape(s: String) = buildString {
        for (c in s) when {
            c == '(' || c == ')' || c == '\\' -> { append('\\'); append(c) }
            c.code in 32..255 -> append(c)
            else -> append('?')
        }
    }
}
