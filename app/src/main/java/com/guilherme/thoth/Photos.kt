package com.guilherme.thoth

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.guilherme.thoth.core.Flatten
import com.guilherme.thoth.core.Img
import com.guilherme.thoth.core.Ink
import com.guilherme.thoth.core.Method
import com.guilherme.thoth.core.Pdf
import com.guilherme.thoth.core.PdfPage
import com.guilherme.thoth.core.Settings
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** Loading pictures and exporting PDFs. Results are always new files; nothing of the user's is changed. */
object Photos {
    const val PREVIEW = 1200
    const val THUMB = 320
    const val PDF_SIDE = 2400

    fun load(file: File, rotation: Int, maxSide: Int): Bitmap {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val s = maxSide.toFloat() / max(w, h)
            if (s < 1f) dec.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
        }
        val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        if (rotation % 360 == 0) return argb
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(argb, 0, 0, argb.width, argb.height, m, true)
    }

    /** The page as it should be used: desentortada (straightened) when that is on for this matrícula. */
    fun loadPage(store: Store, scan: Scan, p: Page, maxSide: Int): Bitmap {
        val raw = load(store.pageFile(scan, p), p.rotation, maxSide)
        if (!scan.flatten) return raw
        val m = bend(store, scan, p) ?: return raw
        return straighten(raw, m)
    }

    fun straighten(b: Bitmap, m: Flatten.Model): Bitmap {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Bitmap.createBitmap(Flatten.applyArgb(px, b.width, b.height, m), b.width, b.height, Bitmap.Config.ARGB_8888)
    }

    /** Measures how bent the page is (once; kept next to the page). Null = leave it as it is. */
    fun bend(store: Store, scan: Scan, p: Page): Flatten.Model? = bendInfo(store, scan, p).model

    fun bendInfo(store: Store, scan: Scan, p: Page): Flatten.Found {
        val f = File(store.dir(scan.id), "flat_${p.file}_${p.rotation}.json")
        runCatching {
            if (f.exists()) {
                val o = org.json.JSONObject(f.readText())
                val c = o.optJSONArray("m")
                val model = c?.let { a -> Flatten.Model.fromList(List(a.length()) { a.getDouble(it) }) }
                return Flatten.Found(model, o.optInt("lines"), o.optDouble("shift"), o.optString("why"), o.optDouble("wobble"))
            }
        }
        val found = runCatching { Flatten.measure(toImg(load(store.pageFile(scan, p), p.rotation, 1400))) }
            .getOrElse { Flatten.Found(null, 0, 0.0, "erro ao medir") }
        runCatching {
            val o = org.json.JSONObject().put("lines", found.lines).put("shift", found.maxShift).put("why", found.why).put("wobble", found.wobble)
            found.model?.let { o.put("m", org.json.JSONArray(it.toList())) }
            f.writeText(o.toString())
        }
        return found
    }

    fun toImg(b: Bitmap): Img {
        val p = IntArray(b.width * b.height)
        b.getPixels(p, 0, b.width, 0, 0, b.width, b.height)
        return Img.fromArgb(p, b.width, b.height)
    }

    fun toBitmap(img: Img): Bitmap = Bitmap.createBitmap(img.toArgb(), img.w, img.h, Bitmap.Config.ARGB_8888)

    fun scaled(b: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / max(b.width, b.height)
        if (s >= 1f) return b
        return Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true)
    }

    /** Builds the PDF and saves it as a new file in Downloads/Thoth. Returns its address. */
    fun exportPdf(ctx: Context, store: Store, scan: Scan, progress: (Int) -> Unit): Uri {
        val pages = scan.pages.mapIndexed { i, p ->
            progress(i + 1)
            fun at(side: Int): PdfPage {
                val src = toImg(loadPage(store, scan, p, side))
                val out = toBitmap(Ink.process(src, scan.method, scan.settings).img)
                val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                return PdfPage(bytes, out.width, out.height)
            }
            try { at(PDF_SIDE) } catch (e: OutOfMemoryError) { System.gc(); at(1600) }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName(scan.name) + ".pdf")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Thoth")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("não consegui criar o arquivo")
        r.openOutputStream(uri)!!.use { Pdf.write(it, pages, scan.name) }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        r.update(uri, values, null, null)
        return uri
    }

    /** Saves a new file in Downloads/Thoth (never overwrites: Android adds " (1)" if the name exists). */
    fun exportFile(ctx: Context, fileName: String, mime: String, write: (java.io.OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Thoth")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("não consegui criar o arquivo")
        r.openOutputStream(uri)!!.use(write)
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        r.update(uri, values, null, null)
        return uri
    }

    fun exportText(ctx: Context, name: String, text: String) =
        exportFile(ctx, safeName(name) + ".txt", "text/plain") { it.write(text.toByteArray(Charsets.UTF_8)) }

    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun exportDocx(ctx: Context, name: String, tokens: List<com.guilherme.thoth.core.Transcribe.Tok>, minConf: Float) =
        exportFile(ctx, safeName(name) + " (com cores).docx", DOCX) { com.guilherme.thoth.core.Docx.write(it, name, tokens, minConf) }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { "Matrícula" }

    fun process(src: Img, m: Method, s: Settings) = Ink.process(src, m, s)
}
