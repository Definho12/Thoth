package com.guilherme.thoth

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.guilherme.thoth.core.Ink
import com.guilherme.thoth.core.Method
import com.guilherme.thoth.core.OLine
import com.guilherme.thoth.core.OWord
import com.guilherme.thoth.core.Settings
import com.guilherme.thoth.core.Transcribe
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Reads the text of the pages with Google's on-device reader (works without internet). */
object Ocr {
    private val reader by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Must run off the main thread. */
    fun read(b: Bitmap): List<OLine> {
        val text = Tasks.await(reader.process(InputImage.fromBitmap(b, 0)))
        return text.textBlocks.flatMap { blk ->
            blk.lines.mapNotNull { ln ->
                val words = ln.elements.mapNotNull { e ->
                    val r = e.boundingBox ?: return@mapNotNull null
                    OWord(e.text, e.confidence, r.left, r.top, r.right, r.bottom)
                }
                if (words.isEmpty()) null else OLine(words)
            }
        }
    }

    /**
     * Reads one page. Tries the photo as it is and the cleaned-up version, and keeps whichever the reader
     * is more sure about. The result is kept next to the page, so it is not read twice.
     */
    fun readPage(store: Store, scan: Scan, p: Page): List<OLine> {
        val cache = File(store.dir(scan.id), "ocr_${p.file}_${p.rotation}.json")
        runCatching { if (cache.exists()) return fromJson(cache.readText()) }
        val photo = Photos.load(store.pageFile(scan, p), p.rotation, 3000)
        val asIs = read(photo)
        val method = if (scan.method == Method.FUNDO || scan.method == Method.COMBINADO || scan.method == Method.CANAL) scan.method else Method.FUNDO
        val settings = if (method == scan.method) scan.settings else Settings.defaultFor(method)
        val clean = read(Photos.toBitmap(Ink.process(Photos.toImg(photo), method, settings).img))
        val best = if (Transcribe.score(clean) >= Transcribe.score(asIs)) clean else asIs
        runCatching { cache.writeText(toJson(best)) }
        return best
    }

    private fun toJson(lines: List<OLine>) = JSONArray().apply {
        lines.forEach { ln ->
            put(JSONArray().apply {
                ln.words.forEach { w -> put(JSONObject().put("t", w.text).put("c", w.conf.toDouble()).put("l", w.l).put("u", w.t).put("r", w.r).put("b", w.b)) }
            })
        }
    }.toString()

    private fun fromJson(s: String): List<OLine> {
        val a = JSONArray(s)
        return List(a.length()) { i ->
            val wa = a.getJSONArray(i)
            OLine(List(wa.length()) { j ->
                val o = wa.getJSONObject(j)
                OWord(o.getString("t"), o.getDouble("c").toFloat(), o.getInt("l"), o.getInt("u"), o.getInt("r"), o.getInt("b"))
            })
        }
    }

    /** Pictures inside a folder the user picked, in name order ("foto 2" before "foto 10"). Only reads them. */
    fun imagesInFolder(ctx: Context, tree: Uri): Pair<String, List<Uri>> {
        val dir = DocumentFile.fromTreeUri(ctx, tree) ?: return "Matrícula" to emptyList()
        val files = dir.listFiles().filter { it.isFile && (it.type?.startsWith("image/") == true) && it.name != null }
            .sortedWith(compareBy(Transcribe.naturalOrder) { it.name!! })
        return (dir.name ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/')) to files.map { it.uri }
    }
}
