package com.guilherme.thoth

import android.content.Context
import android.net.Uri
import com.guilherme.thoth.core.Channel
import com.guilherme.thoth.core.Method
import com.guilherme.thoth.core.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Page(val file: String, val rotation: Int = 0)

data class Scan(
    val id: String,
    val name: String,
    val created: Long,
    val pages: List<Page>,
    val method: Method = Method.FUNDO,
    val settings: Settings = Settings.defaultFor(Method.FUNDO),
)

/**
 * Scans live inside the app (files/scans/<id>/). Each page is the app's own copy of the photo,
 * so the user's gallery is never changed. PDFs are exported to Downloads/Thoth and never deleted by the app.
 */
class Store(private val ctx: Context) {
    private val root = File(ctx.filesDir, "scans").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    fun pageFile(s: Scan, p: Page) = File(dir(s.id), p.file)

    fun all(): List<Scan> = root.listFiles().orEmpty()
        .mapNotNull { runCatching { read(File(it, "scan.json")) }.getOrNull() }
        .sortedByDescending { it.created }

    fun nextName(): String {
        val used = all().map { it.name }.toSet()
        var n = 1
        while ("Matrícula $n" in used) n++
        return "Matrícula $n"
    }

    fun create(name: String): Scan {
        val s = Scan(UUID.randomUUID().toString().take(8), name, System.currentTimeMillis(), emptyList())
        dir(s.id).mkdirs(); save(s); return s
    }

    /** Copies pictures into the scan as new pages. */
    fun addPages(s: Scan, uris: List<Uri>): Scan {
        val added = uris.mapNotNull { uri ->
            val name = "p_" + UUID.randomUUID().toString().take(8) + ".jpg"
            runCatching {
                ctx.contentResolver.openInputStream(uri)!!.use { inp -> File(dir(s.id), name).outputStream().use { inp.copyTo(it) } }
                Page(name)
            }.getOrNull()
        }
        return s.copy(pages = s.pages + added).also { save(it) }
    }

    fun addExample(s: Scan): Scan {
        val name = "p_exemplo_" + UUID.randomUUID().toString().take(4) + ".jpg"
        ctx.resources.openRawResource(R.drawable.exemplo).use { inp -> File(dir(s.id), name).outputStream().use { inp.copyTo(it) } }
        return s.copy(pages = s.pages + Page(name)).also { save(it) }
    }

    /** Removes a page from the scan (only the app's own copy). */
    fun removePage(s: Scan, index: Int): Scan {
        val p = s.pages[index]
        File(dir(s.id), p.file).delete()
        return s.copy(pages = s.pages.toMutableList().apply { removeAt(index) }).also { save(it) }
    }

    /** Deletes the whole scan (only the app's own copies; exported PDFs stay). */
    fun delete(s: Scan) { dir(s.id).deleteRecursively() }

    fun save(s: Scan) {
        val o = JSONObject()
            .put("id", s.id).put("name", s.name).put("created", s.created).put("method", s.method.name)
            .put("pages", JSONArray().apply { s.pages.forEach { put(JSONObject().put("file", it.file).put("rot", it.rotation)) } })
            .put("settings", JSONObject()
                .put("bgSize", s.settings.bgSize.toDouble()).put("clip", s.settings.clip.toDouble())
                .put("channel", s.settings.channel.name).put("stretch", s.settings.stretch.toDouble())
                .put("ink", s.settings.ink.toDouble()).put("clean", s.settings.clean.toDouble())
                .put("color", s.settings.color).put("shadowFirst", s.settings.shadowFirst))
        val f = File(dir(s.id), "scan.json"); val tmp = File(dir(s.id), "scan.json.tmp")
        tmp.writeText(o.toString()); tmp.renameTo(f)
    }

    private fun read(f: File): Scan {
        val o = JSONObject(f.readText())
        val pa = o.getJSONArray("pages")
        val st = o.optJSONObject("settings")
        val method = runCatching { Method.valueOf(o.getString("method")) }.getOrDefault(Method.FUNDO)
        val d = Settings.defaultFor(method)
        val settings = if (st == null) d else Settings(
            bgSize = st.optDouble("bgSize", d.bgSize.toDouble()).toFloat(),
            clip = st.optDouble("clip", d.clip.toDouble()).toFloat(),
            channel = runCatching { Channel.valueOf(st.getString("channel")) }.getOrDefault(d.channel),
            stretch = st.optDouble("stretch", d.stretch.toDouble()).toFloat(),
            ink = st.optDouble("ink", d.ink.toDouble()).toFloat(),
            clean = st.optDouble("clean", d.clean.toDouble()).toFloat(),
            color = st.optBoolean("color", d.color),
            shadowFirst = st.optBoolean("shadowFirst", d.shadowFirst),
        )
        return Scan(
            o.getString("id"), o.getString("name"), o.getLong("created"),
            List(pa.length()) { val p = pa.getJSONObject(it); Page(p.getString("file"), p.optInt("rot", 0)) },
            method, settings,
        )
    }
}
