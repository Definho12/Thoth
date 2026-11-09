package com.guilherme.thoth.core

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reads a matrícula's text act by act and works out who owns it now and how much area each one has.
 * It is a careful helper, not a lawyer: every step keeps the passage it came from so the user can check it.
 */
object Analyze {

    enum class Kind(val label: String) {
        OPENING("Abertura"), TRANSFER("Transmissão"), SPLIT("Desmembramento"), REMAINDER("Área remanescente"),
        LIEN("Ônus"), CANCEL("Cancelamento"), OTHER("Averbação"),
    }

    class Act(
        val label: String,          // "R.1", "AV.3", "Abertura"
        val number: Int?,
        val isRegistro: Boolean,
        val kind: Kind,
        val date: String?,
        val title: String?,
        val from: List<String>,
        val to: List<String>,
        val toGroups: List<List<String>>,
        val areaM2: Double?,
        val fraction: Double?,
        val text: String,
    ) {
        val notes = mutableListOf<String>()
        var summary = ""
        var canceled = false
    }

    class Holder(val names: List<String>, var amount: Double, val since: String)

    class Result(
        val totalM2: Double?,            // area when the matrícula was opened
        val leftM2: Double,              // area that left the matrícula (split off to other matrículas)
        val holders: List<Pair<Holder, Double>>, // holder + share of what remains (0..1)
        val remainingM2: Double?,
        val latest: Holder?,
        val acts: List<Act>,
        val liens: List<String>,
        val warnings: List<String>,
    )

    // ------------------------------------------------------------------ text helpers

    /** Same length as the input: accents removed, upper case. Lets us search loosely but cut the original text. */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            sb.append((d.firstOrNull() ?: c).uppercaseChar())
        }
        return sb.toString()
    }

    /** Joins words broken at the end of a typed line ("fun-/\ndos" -> "fundos") and makes the act one line. */
    fun flat(s: String) = s
        .replace(Regex("-\\s*/?\\s*\\n\\s*"), "")
        .replace(Regex("/\\s*\\n"), " ")
        .replace(Regex("\\s*\\n\\s*"), " ")
        .replace(Regex("— Página \\d+ —"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** Old typewriters had no "1" key, so "11m" is typed "llm". Fixes that only inside measures. */
    private fun fixDigits(s: String): String = s
        .replace(Regex("(?<![\\p{L}\\d])([lI1]*[lI][lI1]*)(?=[\\d,.]*\\s?(m\\b|m\\s|mts|ms|m\\p{L}|ha\\b|has\\b))")) { it.value.replace('l', '1').replace('I', '1') }
        .replace(Regex("(?<=\\d)[oO](?=\\d)"), "0")

    fun num(s: String): Double? {
        val t = s.trim()
        val n = if (t.contains(',')) t.replace(".", "").replace(',', '.')
        else if (Regex("^\\d{1,3}(\\.\\d{3})+$").matches(t)) t.replace(".", "") else t
        return n.toDoubleOrNull()
    }

    class Area(val m2: Double, val at: Int, val unit: String)

    const val ALQUEIRE_MG = 48400.0

    /** Every measure of area in the text, converted to m². */
    fun areas(text: String): List<Area> {
        val t = fold(fixDigits(text))
        val found = mutableListOf<Area>()
        val used = BooleanArray(t.length)
        fun take(r: IntRange) { for (i in r) if (i in used.indices) used[i] = true }
        val n = "(\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d+(?:,\\d+)?)"
        // 2ha 42a 00ca
        Regex("$n\\s*HAS?\\.?\\s*,?\\s*$n\\s*A(?:RES)?\\.?\\s*(?:E\\s*)?(?:$n\\s*CA(?:S|NTIARES|ENTIARES)?\\.?)?").findAll(t).forEach { m ->
            val ha = num(m.groupValues[1]) ?: return@forEach
            val a = num(m.groupValues[2]) ?: 0.0
            val ca = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { num(it) } ?: 0.0
            found += Area(ha * 10000 + a * 100 + ca, m.range.first, "ha"); take(m.range)
        }
        // 11m de frente por 28m de fundos  /  11 x 28 metros
        Regex("$n\\s*M(?:TS|ETROS|S)?\\.?\\s*(?:DE\\s+)?FRENTE\\s*(?:,\\s*)?(?:POR|X)\\s*$n\\s*M(?:TS|ETROS|S)?\\.?\\s*(?:DE\\s+)?(?:FUNDOS?|COMPRIMENTO|EXTENSAO)").findAll(t).forEach { m ->
            if (used[m.range.first]) return@forEach
            val a = num(m.groupValues[1]) ?: return@forEach; val b = num(m.groupValues[2]) ?: return@forEach
            found += Area(a * b, m.range.first, "frente×fundos"); take(m.range)
        }
        Regex("$n\\s*(M²|M2\\b|MTS?2|MTS?\\.?\\s*QUADRADOS|METROS?\\s+QUADRADOS|HECTARES?|HAS?\\b|ARES\\b|ALQUEIRES?)").findAll(t).forEach { m ->
            if (used[m.range.first]) return@forEach
            val v = num(m.groupValues[1]) ?: return@forEach
            val u = m.groupValues[2]
            val f = when {
                u.startsWith("HA") || u.startsWith("HECT") -> 10000.0
                u.startsWith("ARES") -> 100.0
                u.startsWith("ALQ") -> ALQUEIRE_MG
                else -> 1.0
            }
            found += Area(v * f, m.range.first, if (f == ALQUEIRE_MG) "alqueire" else u); take(m.range)
        }
        return found.sortedBy { it.at }
    }

    /** Areas that are about the land, not a building on it. */
    private fun landAreas(text: String): List<Area> {
        val f = fold(text)
        return areas(text).filter { a ->
            val before = f.substring(max(0, a.at - 45), a.at)
            !before.contains("CONSTRU") && !before.contains("EDIFICA")
        }
    }

    /** "metade", "50%", "1/3", "um terço" of the seller's part. */
    fun fraction(text: String): Double? {
        val f = fold(text)
        Regex("(\\d+(?:,\\d+)?)\\s*%").find(f)?.let { m -> num(m.groupValues[1])?.let { if (it in 0.01..100.0) return it / 100 } }
        Regex("(?<![\\d/])(\\d{1,2})\\s*/\\s*(\\d{1,2})(?![\\d/])").find(f)?.let { m ->
            val a = m.groupValues[1].toDouble(); val b = m.groupValues[2].toDouble()
            if (b in 2.0..12.0 && a < b) return a / b
        }
        return when {
            Regex("\\bMETADE\\b|\\bMEIA PARTE\\b").containsMatchIn(f) -> 0.5
            Regex("\\bUM TERCO\\b|\\bTERCA PARTE\\b").containsMatchIn(f) -> 1.0 / 3
            Regex("\\bQUARTA PARTE\\b|\\bUM QUARTO\\b").containsMatchIn(f) -> 0.25
            else -> null
        }
    }

    // ------------------------------------------------------------------ people

    private val CONNECT = setOf("DE", "DA", "DO", "DAS", "DOS", "E", "D")
    private val SPOUSE = "(?:S/M\\.?|S/ESPOSA|SUA ESPOSA|SUA MULHER|S/MULHER|S/MARIDO|SEU MARIDO|S/S\\.?|S/ESP\\.?)"

    /** Keeps the name part ("Antonio Carlos Valente") and drops what follows ("casado", "brasileiro"...). */
    fun cleanName(s: String): String {
        val words = s.trim().trim('.', ',', ';', ':', '-').split(Regex("\\s+"))
        val out = mutableListOf<String>()
        for (w0 in words) {
            val w = w0.trim('.', ',', ';', ':', '(', ')')
            if (w.isEmpty()) continue
            val isConn = fold(w) in CONNECT && out.isNotEmpty()
            if (w[0].isUpperCase() || isConn) out += w else break
        }
        while (out.isNotEmpty() && fold(out.last()) in CONNECT) out.removeAt(out.size - 1)
        return out.joinToString(" ")
    }

    /** People named in a "TRANSMITENTE:" or "ADQUIRENTE:" field, including the spouse. */
    fun people(field: String): List<String> = groups(field).flatten()

    /**
     * The same, grouped: a couple is one group ("José e s/m Maria"), separate people are separate groups
     * ("Luiz Ramos e Paulo Ramos" = two heirs, each with an equal part).
     */
    fun groups(field: String): List<List<String>> {
        val f = field.replace(Regex("\\s+"), " ").trim()
        val out = mutableListOf<MutableList<String>>()
        val first = f.split(',', ';').first()
        val ff = fold(first)
        val sep = Regex("\\s+E\\s+($SPOUSE\\s*)?|\\s*$SPOUSE\\s*")
        var pos = 0
        var spouseNext = false
        for (m in sep.findAll(ff) + sequenceOf(null)) {
            val end = m?.range?.first ?: first.length
            val n = cleanName(first.substring(pos, end))
            if (n.split(" ").size >= 2) {
                if (spouseNext && out.isNotEmpty()) out.last() += n else out += mutableListOf(n)
            }
            if (m == null) break
            spouseNext = m.value.contains("/") || m.value.contains("SUA ") || m.value.contains("SEU ")
            pos = m.range.last + 1
        }
        // spouse mentioned later: ", casado, e s/esposa Eliete de Souza,"
        val fo = fold(f)
        Regex("$SPOUSE\\s*").findAll(fo).forEach { m ->
            if (m.range.first < first.length) return@forEach
            val n = cleanName(f.substring(m.range.last + 1).split(',', ';').first())
            if (n.split(" ").size >= 2 && out.flatten().none { same(it, n) }) {
                if (out.isEmpty()) out += mutableListOf(n) else out.last() += n
            }
        }
        return out
    }

    private fun tokens(n: String) = fold(n).split(Regex("[^A-Z]+")).filter { it.length > 1 && it !in CONNECT }

    /** Same person, allowing for missing middle names ("Antonio Valente" = "Antonio Carlos Valente"). */
    fun same(a: String, b: String): Boolean {
        val x = tokens(a); val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x.first() != y.first()) return false
        return x.last() == y.last() || x.intersect(y.toSet()).size >= 2
    }

    // ------------------------------------------------------------------ acts

    private val LABELS = Regex(
        "\\b(TRANSMITENTES?|OUTORGANTES?(?:\\s+(?:VENDEDOR|DOADOR|CEDENTE)\\p{L}*)?|VENDEDOR\\p{L}*|DOADOR\\p{L}*|CEDENTES?|" +
            "ADQUIRENTES?|OUTORGAD\\p{L}*(?:\\s+(?:COMPRADOR|DONATARI|CESSIONARI)\\p{L}*)?|COMPRADOR\\p{L}*|DONATARI\\p{L}*|CESSIONARI\\p{L}*|" +
            "TITULO|IMOVEL|OBJETO|SOMENTE|VALOR|PROPRIETARI\\p{L}*|CREDOR\\p{L}*|DEVEDOR\\p{L}*|EMITENTES?|CONDICOES|FORMA DO TITULO|DATA)\\s*[:\\-]",
    )
    private fun isSeller(l: String) = l.startsWith("TRANSMIT") || l.startsWith("OUTORGANTE") || l.startsWith("VENDEDOR") || l.startsWith("DOADOR") || l.startsWith("CEDENTE")
    private fun isBuyer(l: String) = l.startsWith("ADQUIR") || (l.startsWith("OUTORGAD") && !l.startsWith("OUTORGANTE")) || l.startsWith("COMPRADOR") || l.startsWith("DONATARI") || l.startsWith("CESSIONARI")

    /** label -> text of each "LABEL: ..." field in the act. */
    fun fields(act: String): List<Pair<String, String>> {
        val f = fold(act)
        val ms = LABELS.findAll(f).toList()
        return ms.mapIndexed { i, m ->
            val end = if (i + 1 < ms.size) ms[i + 1].range.first else act.length
            m.groupValues[1].replace(Regex("\\s+"), " ") to act.substring(m.range.last + 1, end).trim()
        }
    }

    private val LIEN = Regex("HIPOTEC|PENHORA|ALIENACAO FIDUCIARIA|ARRESTO|SEQUESTRO|CEDULA|USUFRUTO|SERVIDAO|INDISPONIBILIDADE")
    private val TRANSFER = Regex("COMPRA E VENDA|DOACAO|PERMUTA|PARTILHA|INVENTARIO|ADJUDICACAO|ARREMATACAO|USUCAPIAO|DACAO|INTEGRALIZACAO|HERANCA|CESSAO|COMPRA")
    private val MARK = Regex("(?m)^\\s*(R|REG|REGISTRO|AV|AVERB\\p{L}*)\\s*[.\\-–:]?\\s*N?[ºO°.]?\\s*0*(\\d{1,3})\\b")

    /** Splits the matrícula text into the opening and its acts (R.1, AV.2 ...). */
    fun split(text: String): List<Triple<String, Int?, String>> {
        val f = fold(text)
        val ms = MARK.findAll(f).toList()
        val out = mutableListOf<Triple<String, Int?, String>>()
        val firstStart = ms.firstOrNull()?.range?.first ?: text.length
        out += Triple("Abertura", null, text.substring(0, firstStart))
        ms.forEachIndexed { i, m ->
            val end = if (i + 1 < ms.size) ms[i + 1].range.first else text.length
            val kind = if (m.groupValues[1].startsWith("A")) "AV" else "R"
            out += Triple("$kind.${m.groupValues[2].toInt()}", m.groupValues[2].toInt(), text.substring(m.range.first, end))
        }
        return out
    }

    private fun date(act: String): String? =
        Regex("(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})\\s*[./-]\\s*(\\d{2,4})").find(act)?.let { "${it.groupValues[1]}/${it.groupValues[2]}/${it.groupValues[3]}" }
            ?: Regex("(\\d{1,2})\\s+de\\s+(\\p{L}+)\\s+de\\s+(\\d{4})", RegexOption.IGNORE_CASE).find(act)?.value

    fun parse(text: String): List<Act> = split(text).map { (label, number, raw) ->
        val act = flat(raw)
        val f = fold(act)
        val fl = fields(act)
        val title = fl.firstOrNull { it.first == "TITULO" || it.first == "FORMA DO TITULO" }?.second?.let { flat(it).split('.').first().take(80) }
        if (number == null) {
            val owners = fl.filter { it.first.startsWith("PROPRIETARI") }.flatMap { people(it.second) }
            val imovel = fl.filter { it.first == "IMOVEL" || it.first == "OBJETO" || it.first == "SOMENTE" }.joinToString(" ") { it.second }
            val area = landAreas(imovel.ifBlank { act }).firstOrNull()?.m2
            val og = fl.filter { it.first.startsWith("PROPRIETARI") }.flatMap { groups(it.second) }
            return@map Act(label, null, false, Kind.OPENING, date(act), null, emptyList(), owners, og, area, null, act)
        }
        val isR = label.startsWith("R")
        val from = fl.filter { isSeller(it.first) }.flatMap { people(it.second) }
        val toGroups = fl.filter { isBuyer(it.first) }.flatMap { groups(it.second) }
        val to = toGroups.flatten()
        val obj = fl.filter { it.first == "OBJETO" || it.first == "SOMENTE" || it.first == "IMOVEL" }.joinToString(" ") { it.second }
        val remIdx = f.indexOf("REMANESCENTE")
        val kind = when {
            to.isNotEmpty() && (TRANSFER.containsMatchIn(f) || !LIEN.containsMatchIn(f)) -> Kind.TRANSFER
            Regex("CANCELAD|CANCELAMENTO|BAIXA D|QUITACAO|FICA SEM EFEITO|EXTINC").containsMatchIn(f) -> Kind.CANCEL
            Regex("DESMEMBR|DESTACAD|DESTAQUE|TRANSPORTAD|ABERTA A MATRICULA|ABERTURA DA MATRICULA|DESAPROPRIA").containsMatchIn(f) -> Kind.SPLIT
            remIdx >= 0 -> Kind.REMAINDER
            LIEN.containsMatchIn(f) -> Kind.LIEN
            else -> Kind.OTHER
        }
        val areaText = obj.ifBlank { act }
        val area = when (kind) {
            Kind.REMAINDER -> landAreas(act).firstOrNull { it.at > remIdx }?.m2
            Kind.SPLIT -> landAreas(act).firstOrNull { remIdx < 0 || it.at < remIdx }?.m2
            else -> landAreas(areaText).firstOrNull()?.m2
        }
        val frac = if (kind == Kind.TRANSFER) fraction(Regex("\\d{1,2}\\s*[./]\\s*\\d{1,2}\\s*[./]\\s*\\d{2,4}").replace(areaText, " ")) else null
        val ofWhole = frac != null && Regex("(METADE|PARTE|%|/\\d)[^.]{0,25}(DO IMOVEL|DA AREA TOTAL|DO TODO|DESTE IMOVEL|DO REFERIDO IMOVEL)").containsMatchIn(fold(areaText))
        Act(label, number, isR, kind, date(act), title, from, to, toGroups, area, frac, act).also { a ->
            if (ofWhole) a.notes += "whole"
            // a split act may also say what remains
            if (kind == Kind.SPLIT && remIdx >= 0) landAreas(act).firstOrNull { it.at > remIdx }?.let { a.notes += "rem:${it.m2}" }
        }
    }

    // ------------------------------------------------------------------ adding it up

    fun analyze(text: String): Result {
        val acts = parse(text)
        val warnings = mutableListOf<String>()
        if (text.contains(Transcribe.ILEGIVEL)) warnings += "O texto tem trechos ilegíveis; nomes ou áreas podem ter ficado de fora."
        if (areas(text).any { it.unit == "alqueire" }) warnings += "Alqueire contado como alqueire mineiro (4,84 ha)."

        // cancellations first, so canceled acts are skipped
        for (a in acts.filter { it.kind == Kind.CANCEL }) {
            val f = fold(a.text)
            Regex("(?<![A-Z])(R|AV)\\s*[.\\-]?\\s*0*(\\d{1,3})\\b").findAll(f).forEach { m ->
                val ref = (if (m.groupValues[1] == "AV") "AV." else "R.") + m.groupValues[2].toInt()
                if (ref != a.label) acts.firstOrNull { it.label == ref }?.let { it.canceled = true; a.notes += "cancela o $ref" }
            }
        }

        val opening = acts.first()
        var total = opening.areaM2
        val known = total != null
        // amounts are m² when the total is known, otherwise shares of 1
        val holders = mutableListOf<Holder>()
        if (opening.toGroups.isNotEmpty()) opening.toGroups.forEach { holders += Holder(it, (total ?: 1.0) / opening.toGroups.size, "Abertura") }
        else warnings += "Não achei o proprietário da abertura (campo PROPRIETÁRIO)."
        if (total == null) warnings += "Não achei a área do imóvel na abertura; mostro só as partes (%)."
        opening.summary = buildString {
            append(if (opening.to.isEmpty()) "Proprietário não encontrado" else "Proprietário: " + opening.to.joinToString(" e "))
            append(" · Área: " + (total?.let { fmtArea(it) } ?: "não encontrada"))
        }
        var left = 0.0
        var latest: Holder? = holders.firstOrNull()
        val liens = linkedMapOf<String, String>()

        fun findHolders(names: List<String>): List<Holder> =
            holders.filter { h -> h.names.any { hn -> names.any { same(hn, it) } } }
        fun holdersIn(text: String): List<Holder> {
            val f = fold(text)
            return holders.filter { h -> h.names.any { n -> val t = tokens(n); t.size >= 2 && f.contains(t.first()) && f.contains(t.last()) } }
        }
        fun take(from: List<Holder>, amount: Double) {
            val sum = from.sumOf { it.amount }
            if (sum <= 0) return
            for (h in from) h.amount -= amount * h.amount / sum
            holders.removeAll { it.amount <= 1e-6 * (total ?: 1.0) }
        }

        for (a in acts.drop(1)) {
            if (a.canceled) { a.summary = "Cancelado"; continue }
            when (a.kind) {
                Kind.TRANSFER -> {
                    var sellers = if (a.from.isNotEmpty()) findHolders(a.from) else emptyList()
                    if (sellers.isEmpty()) {
                        sellers = if (holders.size == 1) holders.toList() else holdersIn(a.text)
                        if (sellers.isEmpty() && holders.isNotEmpty()) {
                            sellers = holders.toList()
                            a.notes += "Não reconheci quem vendeu (${a.from.joinToString(" e ").ifEmpty { "sem nome" }}); considerei todos os donos atuais."
                        } else if (a.from.isNotEmpty() && holders.size > 1) a.notes += "Vendedor reconhecido pelo nome no texto."
                    }
                    val has = sellers.sumOf { it.amount }
                    val ofWhole = a.notes.remove("whole")
                    var amount = when {
                        a.fraction != null && ofWhole -> {
                            a.notes += "Parte: ${pct(a.fraction)} do imóvel todo."
                            min(has, holders.sumOf { it.amount } * a.fraction)
                        }
                        a.fraction != null -> {
                            a.notes += "Parte: ${pct(a.fraction)} do que o vendedor tinha."
                            has * a.fraction
                        }
                        a.areaM2 != null && known -> min(has, a.areaM2)
                        else -> has
                    }
                    if (a.areaM2 != null && known && a.areaM2 > has * 1.03) a.notes += "A área citada (${fmtArea(a.areaM2)}) é maior do que o vendedor tinha (${fmtArea(has)})."
                    if (a.areaM2 != null && !known) a.notes += "Sem a área total, não dá para saber se a venda foi do todo ou de uma parte; considerei o todo."
                    if (abs(amount - has) < 1e-6 * (total ?: 1.0) * 10) amount = has
                    take(sellers, amount)
                    val buyerGroups = a.toGroups.ifEmpty { listOf(a.to) }
                    for (g in buyerGroups) {
                        val part = amount / buyerGroups.size
                        val existing = holders.firstOrNull { h -> h.names.any { hn -> g.any { same(hn, it) } } }
                        latest = existing?.also { it.amount += part } ?: Holder(g, part, a.label).also { holders += it }
                    }
                    if (buyerGroups.size > 1) a.notes += "${buyerGroups.size} adquirentes; considerei partes iguais."
                    a.summary = "${a.title ?: "Transmissão"}: " +
                        (if (sellers.isEmpty()) "" else sellers.joinToString(" / ") { it.names.joinToString(" e ") } + " → ") +
                        a.to.joinToString(" e ") + " · " + (if (known) fmtArea(amount) else pct(amount)) +
                        (if (abs(amount - has) < 1e-9) " (tudo que o vendedor tinha)" else "")
                }
                Kind.SPLIT -> {
                    val rem = a.notes.firstOrNull { it.startsWith("rem:") }?.removePrefix("rem:")?.toDouble()
                    a.notes.removeAll { it.startsWith("rem:") }
                    val now = holders.sumOf { it.amount }
                    val out = when {
                        a.areaM2 != null && known -> a.areaM2
                        rem != null && known -> now - rem
                        else -> null
                    }
                    if (out == null || !known) {
                        a.summary = "Parte do imóvel saiu da matrícula (área não encontrada)"
                        a.notes += "Não consegui calcular quanto saiu."
                    } else {
                        val from = (if (a.from.isNotEmpty()) findHolders(a.from) else emptyList()).ifEmpty { holdersIn(a.text) }.ifEmpty { holders.toList() }
                        take(from, min(out, from.sumOf { it.amount })); left += out
                        a.summary = "Saiu da matrícula: ${fmtArea(out)}" + (Regex("MATRICULA\\s*N?[ºO°.]?\\s*(\\d[\\d.]*)").find(fold(a.text))?.let { " (para a matrícula ${it.groupValues[1]})" } ?: "")
                    }
                }
                Kind.REMAINDER -> {
                    val now = holders.sumOf { it.amount }
                    val r = a.areaM2
                    if (r != null && known && now > 0) {
                        if (r < now) left += now - r
                        for (h in holders) h.amount *= r / now
                        a.summary = "Área remanescente: ${fmtArea(r)}"
                    } else a.summary = "Fala de área remanescente (não consegui usar)"
                }
                Kind.LIEN -> {
                    val what = LIEN.find(fold(a.text))!!.value.lowercase().replaceFirstChar { it.uppercase() }
                        .replace("Hipotec", "Hipoteca").replace("Alienacao fiduciaria", "Alienação fiduciária").replace("Cedula", "Cédula")
                        .replace("Servidao", "Servidão").replace("Sequestro", "Sequestro").replace("Indisponibilidade", "Indisponibilidade")
                    liens[a.label] = "$what (${a.label}${a.date?.let { ", $it" } ?: ""})"
                    a.summary = "$what — não muda o dono"
                }
                Kind.CANCEL -> a.summary = if (a.notes.isEmpty()) "Cancelamento (não achei de qual ato)" else a.notes.joinToString(", ").replaceFirstChar { it.uppercase() }
                Kind.OTHER -> a.summary = flat(a.text).removePrefix(a.label).take(90).trim()
                Kind.OPENING -> {}
            }
            // canceled liens leave the list
            if (a.kind == Kind.CANCEL) acts.filter { it.canceled }.forEach { liens.remove(it.label) }
        }
        val sum = holders.sumOf { it.amount }
        val remaining = if (known) sum else null
        if (known && total != null && remaining != null && remaining > total * 1.01) warnings += "As contas passaram da área total; confira as áreas das vendas."
        return Result(
            total, left, holders.map { it to (if (sum > 0) it.amount / sum else 0.0) }, remaining,
            latest?.takeIf { it in holders }, acts, liens.values.toList(), warnings,
        )
    }

    fun pct(f: Double) = String.format(java.util.Locale("pt", "BR"), "%.1f%%", f * 100).replace(",0%", "%")

    fun fmtArea(m2: Double): String {
        val br = java.util.Locale("pt", "BR")
        return if (m2 >= 10000) String.format(br, "%.2f ha (%,.0f m²)", m2 / 10000, m2)
        else String.format(br, "%,.2f m²", m2).replace(",00 m²", " m²")
    }
}
