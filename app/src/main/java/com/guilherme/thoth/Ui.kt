package com.guilherme.thoth

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.guilherme.thoth.core.Analyze
import com.guilherme.thoth.core.Channel
import com.guilherme.thoth.core.Img
import com.guilherme.thoth.core.Ink
import com.guilherme.thoth.core.Method
import com.guilherme.thoth.core.OLine
import com.guilherme.thoth.core.Transcribe
import com.guilherme.thoth.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object C {
    val bg = Color(0xFF121315)
    val surface = Color(0xFF1C1E21)
    val line = Color(0xFF2C2F34)
    val text = Color(0xFFE8E6E1)
    val dim = Color(0xFF9A9DA3)
    val accent = Color(0xFF86B7E8)
    val onAccent = Color(0xFF0B1A28)
    val warn = Color(0xFFE8A08A)
}

@Composable
fun ThothTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = C.onAccent, background = C.bg, surface = C.surface,
            onBackground = C.text, onSurface = C.text, surfaceContainerHigh = C.surface,
        ),
        content = content,
    )
}

private fun Context.activity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Google's page scanner (finds the edges, straightens the page, many pages in a row). Gallery as fallback. */
@Composable
private fun rememberScanner(onPages: (List<Uri>) -> Unit, onMessage: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onPages(uris)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val r = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val uris = r?.pages?.map { it.imageUri }.orEmpty()
            if (uris.isNotEmpty()) onPages(uris)
        }
    }
    val scanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                // basic mode: crop and straighten only, no "whitening" filter that erases faint ink
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
                .build()
        )
    }
    return {
        val fallback = {
            onMessage("O scanner do Google não abriu neste celular. Escolha as fotos da galeria.")
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        val act = ctx.activity()
        if (act == null) fallback() else
            scanner.getStartScanIntent(act)
                .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { fallback() }
    }
}

@Composable
fun ThothApp() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val scope = rememberCoroutineScope()
    var scans by remember { mutableStateOf(store.all()) }
    var open by remember { mutableStateOf<Scan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() { scans = store.all() }

    val newScan = rememberScanner(
        onPages = { uris ->
            busy = true
            scope.launch {
                val s = withContext(Dispatchers.IO) { store.addPages(store.create(store.nextName()), uris) }
                refresh(); busy = false; open = s
            }
        },
        onMessage = { message = it },
    )

    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            busy = true; message = null
            scope.launch {
                val s = withContext(Dispatchers.IO) {
                    runCatching {
                        val (name, uris) = Ocr.imagesInFolder(ctx, tree)
                        if (uris.isEmpty()) null else store.addPages(store.create(name), uris)
                    }.getOrNull()
                }
                busy = false
                if (s == null) message = "Não achei fotos nessa pasta." else { refresh(); open = s }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg).systemBarsPadding()) {
        val s = open
        if (s == null) {
            Home(
                scans = scans, store = store, busy = busy, message = message,
                onNew = { message = null; newScan() },
                onFolder = { folder.launch(null) },
                onOpen = { open = it },
                onExample = {
                    scope.launch {
                        val e = withContext(Dispatchers.IO) { store.addExample(store.create("Exemplo")) }
                        refresh(); open = e
                    }
                },
            )
        } else {
            BackHandler { open = null; refresh() }
            ScanScreen(
                initial = s, store = store,
                onBack = { open = null; refresh() },
                onDeleted = { store.delete(s); open = null; refresh() },
            )
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun Home(
    scans: List<Scan>, store: Store, busy: Boolean, message: String?,
    onNew: () -> Unit, onFolder: () -> Unit, onOpen: (Scan) -> Unit, onExample: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(28.dp))
        Text("Thoth", color = C.text, fontSize = 34.sp, fontFamily = FontFamily.Serif)
        Text("Matrículas digitalizadas", color = C.dim, fontSize = 14.sp)
        Spacer(Modifier.height(22.dp))
        if (busy) Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else {
            BigButton("Nova matrícula", primary = true, onClick = onNew)
            Spacer(Modifier.height(10.dp))
            BigButton("Importar pasta de fotos", onClick = onFolder)
        }
        if (message != null) Text(message, color = C.warn, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(18.dp))
        if (scans.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Toque em \"Nova matrícula\" e fotografe as páginas.\nNo fim, gere um PDF com todas.",
                    color = C.dim, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text("Testar com uma página de exemplo", color = C.accent, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onExample).padding(8.dp))
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scans, key = { it.id }) { s -> ScanRow(s, store) { onOpen(s) } }
            }
        }
        Text(
            "Tudo é feito no celular, sem conta. As fotos da galeria nunca são alteradas.",
            color = C.dim, fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun ScanRow(s: Scan, store: Store, onClick: () -> Unit) {
    val first = s.pages.firstOrNull()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.surface).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(C.bg), contentAlignment = Alignment.Center) {
            if (first != null) Thumb(store, s, first, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, color = C.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val n = s.pages.size
            Text(
                (if (n == 1) "1 página" else "$n páginas") + " · " + SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date(s.created)),
                color = C.dim, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun Thumb(store: Store, s: Scan, p: Page, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, s.id, p.file, p.rotation) {
        value = withContext(Dispatchers.IO) { runCatching { Photos.load(store.pageFile(s, p), p.rotation, Photos.THUMB) }.getOrNull() }
    }
    bmp?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop) }
}

// ---------------------------------------------------------------- one matrícula

@Composable
private fun ScanScreen(initial: Scan, store: Store, onBack: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var scan by remember { mutableStateOf(initial) }
    var cur by remember { mutableIntStateOf(0) }
    val perMethod = remember {
        mutableStateMapOf<Method, Settings>().apply {
            Method.entries.forEach { put(it, Settings.defaultFor(it)) }; put(initial.method, initial.settings)
        }
    }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var previewImg by remember { mutableStateOf<Img?>(null) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    var channelUsed by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var holdOriginal by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<Int?>(null) }
    var pdfReady by remember { mutableStateOf<Uri?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun update(s: Scan) { scan = s }
    // save a moment after the last change (sliders move many times per second)
    LaunchedEffect(Unit) {
        snapshotFlow { scan }.collectLatest { delay(400); withContext(Dispatchers.IO) { store.save(it) } }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(3000); toast = null } }

    val addPages = rememberScanner(
        onPages = { uris ->
            scope.launch {
                val before = scan.pages.size
                val s = withContext(Dispatchers.IO) { store.save(scan); store.addPages(scan, uris) }
                update(s); cur = before.coerceAtMost(s.pages.size - 1)
                toast = if (uris.size == 1) "1 página adicionada" else "${uris.size} páginas adicionadas"
            }
        },
        onMessage = { toast = it },
    )

    val page = scan.pages.getOrNull(cur)
    // load the current page
    LaunchedEffect(page?.file, page?.rotation) {
        scale = 1f; offset = Offset.Zero
        if (page == null) { preview = null; previewImg = null; shown = null; return@LaunchedEffect }
        val b = withContext(Dispatchers.IO) { runCatching { Photos.load(store.pageFile(scan, page), page.rotation, Photos.PREVIEW) }.getOrNull() }
        preview = b; previewImg = b?.let { withContext(Dispatchers.Default) { Photos.toImg(it) } }; shown = null
    }
    // live processing
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(previewImg, scan.method, scan.settings) }.collectLatest { (img, m, st) ->
            if (img == null) return@collectLatest
            delay(40); busy = true
            val r = withContext(Dispatchers.Default) { val res = Ink.process(img, m, st); Photos.toBitmap(res.img) to res.channelUsed }
            shown = r.first; channelUsed = r.second; busy = false
        }
    }

    fun setMethod(m: Method) { perMethod[scan.method] = scan.settings; update(scan.copy(method = m, settings = perMethod[m]!!)) }
    fun updSettings(f: (Settings) -> Settings) { update(scan.copy(settings = f(scan.settings))) }

    fun move(d: Int) {
        val j = cur + d
        if (page == null || j !in scan.pages.indices) return
        val l = scan.pages.toMutableList(); l[cur] = l[j]; l[j] = page
        update(scan.copy(pages = l)); cur = j
    }
    fun rotate() {
        if (page == null) return
        val l = scan.pages.toMutableList(); l[cur] = page.copy(rotation = (page.rotation + 90) % 360)
        update(scan.copy(pages = l))
    }
    fun exportPdf() {
        if (exporting != null || scan.pages.isEmpty()) return
        exporting = 0
        scope.launch {
            try {
                val s = scan
                withContext(Dispatchers.IO) { store.save(s) }
                pdfReady = withContext(Dispatchers.Default) {
                    Photos.exportPdf(ctx, store, s) { n -> scope.launch { exporting = n } }
                }
            } catch (e: Throwable) {
                toast = "Não consegui gerar o PDF: ${e.message ?: e.javaClass.simpleName}"
            }
            exporting = null
        }
    }

    var showText by remember { mutableStateOf(false) }
    if (showText) {
        BackHandler { showText = false }
        TextScreen(scan, store, onBack = { showText = false })
        return
    }

    if (grid && previewImg != null) {
        BackHandler { grid = false }
        Grid(previewImg!!, perMethod.toMap() + (scan.method to scan.settings), onPick = { setMethod(it); grid = false }, onBack = { grid = false })
        return
    }

    Column(Modifier.fillMaxSize()) {
        // top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("‹", onClick = onBack)
            Spacer(Modifier.width(10.dp))
            Text(
                scan.name, color = C.text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { renaming = true }.padding(vertical = 6.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (scan.pages.isNotEmpty()) Pill("Texto", onClick = { store.save(scan); showText = true })
            Spacer(Modifier.width(8.dp))
            val e = exporting
            Pill(
                if (e != null) "PDF $e/${scan.pages.size}…" else "PDF",
                selected = true, onClick = { exportPdf() },
            )
        }

        // the page
        Box(
            Modifier.fillMaxWidth().weight(1f).clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = if (holdOriginal) preview else (shown ?: preview)
            if (bmp != null) Image(
                bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            ) else if (scan.pages.isEmpty()) Text("Nenhuma página. Toque em + para adicionar.", color = C.dim, fontSize = 14.sp)
            if (busy || (bmp == null && page != null)) CircularProgressIndicator(
                color = C.accent, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp),
            )
            if (page != null) Box(
                Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(50))
                    .background(if (holdOriginal) C.accent else Color(0xCC1C1E21))
                    .pointerInput(Unit) { detectTapGestures(onPress = { holdOriginal = true; tryAwaitRelease(); holdOriginal = false }) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text("Segure: original", color = if (holdOriginal) C.onAccent else C.text, fontSize = 13.sp) }
            val t = toast
            if (t != null) Box(
                Modifier.align(Alignment.TopCenter).padding(10.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xEE1C1E21)).padding(horizontal = 14.dp, vertical = 9.dp),
            ) { Text(t, color = C.text, fontSize = 13.sp) }
        }

        // pages strip
        LazyRow(
            Modifier.fillMaxWidth().height(78.dp).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        ) {
            itemsIndexed(scan.pages, key = { _, p -> p.file }) { i, p ->
                Box(
                    Modifier.fillMaxHeight().aspectRatio(0.75f).clip(RoundedCornerShape(6.dp)).background(C.surface)
                        .border(if (i == cur) 2.dp else 1.dp, if (i == cur) C.accent else C.line, RoundedCornerShape(6.dp))
                        .clickable { cur = i },
                ) {
                    Thumb(store, scan, p, Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(4.dp)))
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(3.dp).clip(RoundedCornerShape(50))
                            .background(if (i == cur) C.accent else Color(0xCC121315)).padding(horizontal = 6.dp, vertical = 1.dp),
                    ) { Text("${i + 1}", color = if (i == cur) C.onAccent else C.text, fontSize = 11.sp) }
                }
            }
            item {
                Box(
                    Modifier.fillMaxHeight().aspectRatio(0.75f).clip(RoundedCornerShape(6.dp))
                        .border(1.dp, C.line, RoundedCornerShape(6.dp)).clickable { addPages() },
                    contentAlignment = Alignment.Center,
                ) { Text("+", color = C.accent, fontSize = 26.sp) }
            }
        }

        // page actions
        if (page != null) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill("‹ Mover", onClick = { move(-1) })
            Pill("Mover ›", onClick = { move(1) })
            Pill("Girar", onClick = { rotate() })
            Pill("Remover", onClick = { confirmRemove = true })
            Pill("Lado a lado", onClick = { grid = true })
        }

        // look of the pages (applies to all pages of this matrícula)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { Method.entries.forEach { m -> Pill(m.label, selected = m == scan.method, onClick = { setMethod(m) }) } }

        Column(
            Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp).padding(bottom = 10.dp),
        ) {
            val s = scan.settings
            Text(scan.method.about + " Vale para todas as páginas.", color = C.dim, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            when (scan.method) {
                Method.ORIGINAL -> {}
                Method.FUNDO -> {
                    Knob("Recuperar tinta", s.ink, 0f..1f, auto = true) { v -> updSettings { it.copy(ink = v) } }
                    Knob("Limpar papel", s.clean, 0f..0.15f) { v -> updSettings { it.copy(clean = v) } }
                    Knob("Tamanho da sombra", s.bgSize, 0f..1f) { v -> updSettings { it.copy(bgSize = v) } }
                    Toggle("Manter cores", s.color) { v -> updSettings { it.copy(color = v) } }
                }
                Method.COMBINADO -> {
                    Knob("Recuperar tinta", s.ink, 0f..1f, auto = true) { v -> updSettings { it.copy(ink = v) } }
                    Knob("Limpar papel", s.clean, 0f..0.15f) { v -> updSettings { it.copy(clean = v) } }
                    Knob("Contraste local", s.clip, 1f..6f) { v -> updSettings { it.copy(clip = v) } }
                    Channels(s.channel, channelUsed) { v -> updSettings { it.copy(channel = v) } }
                }
                Method.CLAHE -> Knob("Força", s.clip, 1f..6f) { v -> updSettings { it.copy(clip = v) } }
                Method.CANAL -> {
                    Channels(s.channel, channelUsed) { v -> updSettings { it.copy(channel = v) } }
                    Knob("Recuperar tinta", s.ink, 0f..1f, auto = true) { v -> updSettings { it.copy(ink = v) } }
                    Knob("Limpar papel", s.clean, 0f..0.15f) { v -> updSettings { it.copy(clean = v) } }
                }
                Method.DSTRETCH -> {
                    Knob("Intensidade", s.stretch, 0.3f..2f) { v -> updSettings { it.copy(stretch = v) } }
                    Toggle("Tirar a sombra antes", s.shadowFirst) { v -> updSettings { it.copy(shadowFirst = v) } }
                }
            }
            if (scan.method != Method.ORIGINAL) Text(
                "Voltar ao padrão", color = C.accent, fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp).clickable { updSettings { Settings.defaultFor(scan.method) } },
            )
        }
    }

    if (renaming) RenameDialog(scan.name, onDone = { n -> if (n.isNotBlank()) update(scan.copy(name = n.trim())); renaming = false }, onDelete = onDeleted)
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remover a página ${cur + 1}?") },
        text = { Text("Ela sai só desta matrícula no Thoth. Fotos da galeria e PDFs já gerados não são apagados.") },
        confirmButton = {
            TextButton(onClick = {
                confirmRemove = false
                scope.launch {
                    val s = withContext(Dispatchers.IO) { store.save(scan); store.removePage(scan, cur) }
                    update(s); cur = cur.coerceAtMost(s.pages.size - 1).coerceAtLeast(0)
                }
            }) { Text("Remover", color = C.warn) }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancelar", color = C.text) } },
        containerColor = C.surface,
    )
    pdfReady?.let { uri ->
        AlertDialog(
            onDismissRequest = { pdfReady = null },
            title = { Text("PDF pronto") },
            text = { Text("Salvo em Downloads/Thoth, com ${scan.pages.size} " + (if (scan.pages.size == 1) "página." else "páginas.")) },
            confirmButton = {
                TextButton(onClick = {
                    pdfReady = null
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (e: ActivityNotFoundException) { toast = "Nenhum app para abrir PDF. Ele está em Downloads/Thoth." }
                }) { Text("Abrir", color = C.accent) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pdfReady = null
                    val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(send, "Enviar PDF"))
                }) { Text("Compartilhar", color = C.accent) }
            },
            containerColor = C.surface,
        )
    }
}

@Composable
private fun TextScreen(scan: Scan, store: Store, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pages by remember { mutableStateOf<List<List<OLine>>?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var minConf by remember { mutableFloatStateOf(0.6f) }
    var round by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(3000); toast = null } }
    LaunchedEffect(round) {
        pages = null; error = null
        try {
            val res = mutableListOf<List<OLine>>()
            for ((i, p) in scan.pages.withIndex()) {
                progress = i + 1
                res += withContext(Dispatchers.Default) { Ocr.readPage(store, scan, p) }
            }
            pages = res
        } catch (e: Throwable) {
            error = "Não consegui ler as páginas: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    val tokens = pages?.let { Transcribe.documentTokens(scan.name, it, minConf) }
    val text = tokens?.joinToString("") { it.text }
    var colors by remember { mutableStateOf(true) }
    var analyzing by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    fun export(docx: Boolean) {
        exportOpen = false
        val toks = tokens ?: return
        val plain = text ?: return
        scope.launch {
            try {
                exported = withContext(Dispatchers.IO) {
                    if (docx) Photos.exportDocx(ctx, scan.name, toks, minConf) to Photos.DOCX
                    else Photos.exportText(ctx, scan.name, plain) to "text/plain"
                }
            } catch (e: Throwable) { toast = "Não consegui salvar: ${e.message}" }
        }
    }
    if (exportOpen) AlertDialog(
        onDismissRequest = { exportOpen = false },
        title = { Text("Exportar o texto") },
        text = {
            Column {
                Text("Word com cores (.docx)", color = C.accent, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { export(true) }.padding(vertical = 8.dp))
                Text("Abre no computador (Word, LibreOffice, Google Docs) com as mesmas cores da tela. Dá para corrigir lá.", color = C.dim, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Text("Texto simples (.txt)", color = C.accent, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { export(false) }.padding(vertical = 8.dp))
                Text("Só o texto, sem cores.", color = C.dim, fontSize = 13.sp)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { exportOpen = false }) { Text("Cancelar", color = C.text) } },
        containerColor = C.surface,
    )
    exported?.let { (uri, mime) ->
        AlertDialog(
            onDismissRequest = { exported = null },
            title = { Text("Salvo em Downloads/Thoth") },
            text = { Text("Para levar ao computador, compartilhe (WhatsApp, e-mail, Google Drive) ou copie pela pasta Downloads/Thoth com o cabo USB.") },
            confirmButton = {
                TextButton(onClick = {
                    exported = null
                    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(send, "Enviar arquivo"))
                }) { Text("Compartilhar", color = C.accent) }
            },
            dismissButton = { TextButton(onClick = { exported = null }) { Text("Ok", color = C.text) } },
            containerColor = C.surface,
        )
    }
    if (analyzing && text != null) {
        BackHandler { analyzing = false }
        AnalysisScreen(scan.name, text, onBack = { analyzing = false })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("‹", onClick = onBack)
            Spacer(Modifier.width(10.dp))
            Text("Texto", color = C.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (text != null) {
                Pill("Copiar", onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(scan.name, text))
                    toast = "Texto copiado"
                })
                Spacer(Modifier.width(8.dp))
                Pill("Exportar", selected = true, onClick = { exportOpen = true })
            }
        }
        val t = toast
        if (t != null) Text(t, color = C.accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
        if (text == null) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                val err = error
                if (err != null) {
                    Text(err, color = C.warn, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
                    Pill("Tentar de novo", onClick = { round++ })
                } else {
                    CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Lendo página $progress de ${scan.pages.size}…", color = C.dim, fontSize = 14.sp)
                }
            }
        } else {
            val toks = tokens!!
            val shown = remember(toks, colors) {
                buildAnnotatedString {
                    for (t in toks) {
                        val lv = if (colors) Transcribe.level(t, minConf) else null
                        if (lv == null || lv == 3) append(t.text)
                        else withStyle(SpanStyle(color = SURE[lv], background = SURE_BG[lv])) { append(t.text) }
                    }
                }
            }
            val words = toks.filter { it.conf != null }
            val count = words.count { it.text == Transcribe.ILEGIVEL }
            val letters = words.sumOf { it.text.length }.coerceAtLeast(1)
            val surePct = words.filter { Transcribe.level(it, minConf) == 3 }.sumOf { it.text.length } * 100 / letters
            // legend
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Legend(C.text, "Certeza (90%+)")
                Legend(SURE[2], "Quase certo")
                Legend(SURE[1], "Dúvida")
                Legend(SURE[0], "Ilegível")
                Text(if (colors) "Tirar cores" else "Mostrar cores", color = C.accent, fontSize = 12.sp, modifier = Modifier.clickable { colors = !colors })
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)).background(C.surface)) {
                SelectionContainer(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(shown, color = C.text, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ilegível abaixo de", color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${(minConf * 100).toInt()}% de certeza", color = C.dim, fontSize = 13.sp)
                }
                Slider(
                    value = minConf, onValueChange = { minConf = it }, valueRange = 0.2f..0.95f,
                    colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.line),
                    modifier = Modifier.height(34.dp),
                )
                Text(
                    "$surePct% do texto com certeza alta. " +
                        (if (count == 0) "Nenhum trecho ilegível." else if (count == 1) "1 trecho ilegível." else "$count trechos ilegíveis.") +
                        " Nada é corrigido: erros do original ficam como estão. A leitura pode confundir letras parecidas, então confira com a foto.",
                    color = C.dim, fontSize = 12.sp,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Compartilhar", onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, scan.name)
                        ctx.startActivity(Intent.createChooser(send, "Enviar texto"))
                    })
                    Pill("Analisar", selected = true, onClick = { analyzing = true })
                    Pill("Ler de novo", onClick = {
                        store.dir(scan.id).listFiles()?.filter { it.name.startsWith("ocr_") }?.forEach { it.delete() }
                        round++
                    })
                }
            }
        }
    }
}

@Composable
private fun AnalysisScreen(name: String, text: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val r = remember(text) { Analyze.analyze(text) }
    var copied by remember { mutableStateOf(false) }
    val summary = remember(r) {
        buildString {
            append(name).append(" — análise automática\n\n")
            append("Proprietário(s) atual(is):\n")
            r.holders.sortedByDescending { it.first.amount }.forEach { (h, share) ->
                append("• ").append(h.names.joinToString(" e ")).append(": ")
                append(if (r.remainingM2 != null) Analyze.fmtArea(h.amount) + " (" + Analyze.pct(share) + ")" else Analyze.pct(share)).append('\n')
            }
            r.totalM2?.let { append("\nÁrea na abertura: ").append(Analyze.fmtArea(it)).append('\n') }
            if (r.leftM2 > 0) append("Saiu da matrícula: ").append(Analyze.fmtArea(r.leftM2)).append('\n')
            r.remainingM2?.let { append("Área restante na matrícula: ").append(Analyze.fmtArea(it)).append('\n') }
            if (r.liens.isNotEmpty()) append("\nÔnus: ").append(r.liens.joinToString("; ")).append('\n')
            append("\nPasso a passo:\n")
            r.acts.forEach { a -> append("${a.label}${a.date?.let { " ($it)" } ?: ""}: ${a.summary}\n") }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("‹", onClick = onBack)
            Spacer(Modifier.width(10.dp))
            Text("Análise", color = C.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Pill(if (copied) "Copiado" else "Copiar resumo", onClick = {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(name, summary)); copied = true
            })
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 20.dp)) {
            Card("Proprietário atual") {
                if (r.holders.isEmpty()) Text("Não consegui descobrir.", color = C.warn, fontSize = 14.sp)
                r.holders.sortedByDescending { it.first.amount }.forEach { (h, share) ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text(h.names.joinToString(" e "), color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            (if (r.remainingM2 != null) Analyze.fmtArea(h.amount) + " · " else "") + Analyze.pct(share) + " do que restou" +
                                " · desde " + h.since + (if (h === r.latest) " · último a adquirir" else ""),
                            color = if (h === r.latest) C.accent else C.dim, fontSize = 13.sp,
                        )
                    }
                }
            }
            Card("Área") {
                AreaRow("Na abertura da matrícula", r.totalM2?.let { Analyze.fmtArea(it) } ?: "não encontrada")
                AreaRow("Saiu da matrícula", if (r.leftM2 > 0) Analyze.fmtArea(r.leftM2) else "nada")
                AreaRow("Ainda na matrícula", r.remainingM2?.let { Analyze.fmtArea(it) } ?: "—", strong = true)
            }
            if (r.liens.isNotEmpty()) Card("Ônus ativos") { r.liens.forEach { Text("• $it", color = C.text, fontSize = 14.sp) } }
            if (r.warnings.isNotEmpty()) Card("Atenção") { r.warnings.forEach { Text("• $it", color = C.warn, fontSize = 13.sp) } }
            Card("Passo a passo") {
                r.acts.forEach { a -> ActRow(a) }
            }
            Text(
                "Leitura automática para ajudar, feita no celular. Confira com a matrícula: não substitui certidão nem a análise de um profissional.",
                color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(C.surface).padding(14.dp)) {
        Text(title, color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun AreaRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (strong) C.accent else C.text, fontSize = 14.sp, fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun ActRow(a: Analyze.Act) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp)) {
        Row {
            Text(a.label, color = if (a.canceled) C.dim else C.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(64.dp))
            Column(Modifier.weight(1f)) {
                Text(a.summary, color = if (a.canceled) C.dim else C.text, fontSize = 13.sp)
                a.date?.let { Text(it, color = C.dim, fontSize = 12.sp) }
                a.notes.forEach { Text(it, color = C.warn, fontSize = 12.sp) }
                if (open) Text(a.text, color = C.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                else Text("ver trecho", color = C.dim, fontSize = 11.sp)
            }
        }
    }
}

/** Colors by how sure the reader was: 0 illegible, 1 unsure, 2 fairly sure (3 = sure, normal text). */
private val SURE = listOf(Color(0xFFF07A7A), Color(0xFFF0A86E), Color(0xFFE6D58A))
private val SURE_BG = listOf(Color(0x33F07A7A), Color(0x1FF0A86E), Color.Transparent)

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = C.dim, fontSize = 12.sp)
    }
}

@Composable
private fun RenameDialog(name: String, onDone: (String) -> Unit, onDelete: () -> Unit) {
    var text by remember { mutableStateOf(name) }
    var sure by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDone(name) },
        title = { Text(if (sure) "Excluir esta matrícula?" else "Nome") },
        text = {
            if (sure) Text("As páginas saem do Thoth. Fotos da galeria e PDFs já gerados não são apagados.")
            else Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, cursorColor = C.accent),
                )
                Text("Excluir esta matrícula", color = C.warn, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp).clickable { sure = true })
            }
        },
        confirmButton = {
            if (sure) TextButton(onClick = onDelete) { Text("Excluir", color = C.warn) }
            else TextButton(onClick = { onDone(text) }) { Text("Salvar", color = C.accent) }
        },
        dismissButton = { TextButton(onClick = { if (sure) sure = false else onDone(name) }) { Text("Cancelar", color = C.text) } },
        containerColor = C.surface,
    )
}

// ---------------------------------------------------------------- small pieces

@Composable
private fun BigButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp))
            .background(if (primary) C.accent else C.surface)
            .then(if (primary) Modifier else Modifier.border(1.dp, C.line, RoundedCornerShape(14.dp)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (primary) C.onAccent else C.text, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Pill(label: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) C.accent else C.surface)
            .then(if (selected) Modifier else Modifier.border(1.dp, C.line, RoundedCornerShape(50)))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, color = if (selected) C.onAccent else C.text, fontSize = 14.sp) }
}

@Composable
private fun Knob(label: String, value: Float, range: ClosedFloatingPointRange<Float>, auto: Boolean = false, onChange: (Float) -> Unit) {
    val pct = ((value - range.start) / (range.endInclusive - range.start) * 100).toInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(if (auto && pct == 50) "automático" else "$pct%", color = C.dim, fontSize = 13.sp)
    }
    Slider(
        value = value, onValueChange = { v -> onChange(if (auto && kotlin.math.abs(v - 0.5f) < 0.03f) 0.5f else v) },
        valueRange = range,
        colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.line),
        modifier = Modifier.height(34.dp),
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.accent, checkedThumbColor = C.onAccent))
    }
}

@Composable
private fun Channels(value: Channel, used: Int?, onChange: (Channel) -> Unit) {
    val names = listOf("vermelho", "verde", "azul", "mais escuro")
    Text(
        "Canal" + if (value == Channel.AUTO && used != null) " (usando o ${names[used]})" else "",
        color = C.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Channel.entries.forEach { c -> Pill(c.label, selected = c == value, onClick = { onChange(c) }) }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Grid(img: Img, settings: Map<Method, Settings>, onPick: (Method) -> Unit, onBack: () -> Unit) {
    var thumbs by remember { mutableStateOf<Map<Method, Bitmap>>(emptyMap()) }
    LaunchedEffect(img) {
        val small = withContext(Dispatchers.Default) { Photos.toImg(Photos.scaled(Photos.toBitmap(img), 560)) }
        for (m in Method.entries) {
            val b = withContext(Dispatchers.Default) { Photos.toBitmap(Ink.process(small, m, settings[m] ?: Settings.defaultFor(m)).img) }
            thumbs = thumbs + (m to b)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("‹ Voltar", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text("Toque para escolher", color = C.dim, fontSize = 13.sp)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Method.entries.toList()) { m ->
                Column(Modifier.clip(RoundedCornerShape(10.dp)).background(C.surface).clickable { onPick(m) }.padding(6.dp)) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(img.w.toFloat() / img.h).clip(RoundedCornerShape(6.dp)).background(C.bg),
                        contentAlignment = Alignment.Center,
                    ) {
                        val b = thumbs[m]
                        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    }
                    Text(m.label, color = C.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                }
            }
        }
    }
}
