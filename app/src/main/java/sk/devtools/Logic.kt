// SPDX-License-Identifier: GPL-3.0-or-later
package sk.devtools

// Čistá logika nových nástrojov (bez Androidu a Compose), aby sa dala testovať samostatne.

import java.math.BigDecimal
import java.math.MathContext
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.Collator
import java.text.Normalizer
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val SK: Locale get() = Locale.getDefault()
internal val rng = SecureRandom()

// ───────────────────────── JSON ─────────────────────────

class JsonErr(msg: String) : Exception(msg)

object Js {
    fun parse(s: String): Any? {
        val p = JsParser(s)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i < s.length) p.fail(str(R.string.unexpected_character, s[p.i]))
        return v
    }

    fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c == '\b' -> b.append("\\b")
            c == '\u000C' -> b.append("\\f")
            c < ' ' -> b.append("\\u%04x".format(c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    fun fmt(n: Number): String = when (n) {
        is Double -> when {
            n.isNaN() || n.isInfinite() -> "null"
            n == Math.rint(n) && Math.abs(n) < 1e15 -> n.toLong().toString()
            else -> n.toString()
        }
        else -> n.toString()
    }

    fun stringify(v: Any?, indent: Int = 0, level: Int = 0): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean -> v.toString()
        is Number -> fmt(v)
        is Map<*, *> -> if (v.isEmpty()) "{}" else {
            val nl = if (indent > 0) "\n" + " ".repeat(indent * (level + 1)) else ""
            val end = if (indent > 0) "\n" + " ".repeat(indent * level) else ""
            val sep = if (indent > 0) ": " else ":"
            v.entries.joinToString(",", "{", end + "}") { e -> nl + quote(e.key.toString()) + sep + stringify(e.value, indent, level + 1) }
        }
        is List<*> -> if (v.isEmpty()) "[]" else {
            val nl = if (indent > 0) "\n" + " ".repeat(indent * (level + 1)) else ""
            val end = if (indent > 0) "\n" + " ".repeat(indent * level) else ""
            v.joinToString(",", "[", end + "]") { x -> nl + stringify(x, indent, level + 1) }
        }
        else -> quote(v.toString())
    }
}

internal class JsParser(val s: String) {
    var i = 0
    fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\r' || s[i] == '\n')) i++ }
    fun fail(m: String): Nothing = throw JsonErr(str(R.string.position, m, i))

    fun value(): Any? {
        if (i >= s.length) fail(str(R.string.unexpected_end_of_input))
        val c = s[i]
        return when {
            c == '{' -> obj()
            c == '[' -> arr()
            c == '"' -> str()
            s.startsWith("true", i) -> { i += 4; true }
            s.startsWith("false", i) -> { i += 5; false }
            s.startsWith("null", i) -> { i += 4; null }
            c == '-' || c in '0'..'9' -> number()
            else -> fail(str(R.string.unexpected_character, c))
        }
    }

    fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++; ws()
        if (i < s.length && s[i] == '}') { i++; return m }
        while (true) {
            ws()
            if (i >= s.length || s[i] != '"') fail(str(R.string.expected_a_key_in_quotes))
            val k = str(); ws()
            if (i >= s.length || s[i] != ':') fail(str(R.string.expected))
            i++; ws()
            m[k] = value(); ws()
            if (i >= s.length) fail(str(R.string.unterminated_object))
            if (s[i] == ',') { i++; continue }
            if (s[i] == '}') { i++; return m }
            fail(str(R.string.expected_or_2))
        }
    }

    fun arr(): List<Any?> {
        val a = ArrayList<Any?>()
        i++; ws()
        if (i < s.length && s[i] == ']') { i++; return a }
        while (true) {
            ws()
            a.add(value()); ws()
            if (i >= s.length) fail(str(R.string.unterminated_array))
            if (s[i] == ',') { i++; continue }
            if (s[i] == ']') { i++; return a }
            fail(str(R.string.expected_or))
        }
    }

    fun str(): String {
        i++
        val b = StringBuilder()
        while (true) {
            if (i >= s.length) fail(str(R.string.unterminated_string))
            val c = s[i++]
            if (c == '"') return b.toString()
            if (c == '\\') {
                if (i >= s.length) fail(str(R.string.unterminated_string))
                val e = s[i++]
                when (e) {
                    '"' -> b.append('"')
                    '\\' -> b.append('\\')
                    '/' -> b.append('/')
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000C')
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    't' -> b.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) fail(str(R.string.invalid_u_sequence))
                        val h = s.substring(i, i + 4)
                        if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail(str(R.string.invalid_u_sequence))
                        b.append(h.toInt(16).toChar())
                        i += 4
                    }
                    else -> fail(str(R.string.invalid_escape, e))
                }
            } else if (c < ' ') fail(str(R.string.invalid_control_character_in_string))
            else b.append(c)
        }
    }

    fun number(): Any {
        val st = i
        while (i < s.length && s[i] in "+-0123456789.eE") i++
        val t = s.substring(st, i)
        if (!NUMRE.matches(t)) { i = st; fail(str(R.string.invalid_number)) }
        return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
    }
}

private val NUMRE = Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?$")

fun prettyJson(text: String, indent: Int = 2): String = Js.stringify(Js.parse(text), indent)

// ───────────────────────── YAML ─────────────────────────

object Yml {
    private class Row(val r: String, val ind: Int, val t: String)

    private val KEY = Regex("""^("(?:[^"\\]|\\.)*"|'[^']*'|[^\s#\[\]{}"'-][^:#]*?|-[^\s:][^:]*?)\s*:(?:\s+(.*))?$""")
    private val BLOCK = Regex("^[|>][+-]?$")
    private val FLOWTOK = Regex("""("(?:[^"\\]|\\.)*"|'[^']*')|([^\s,:\[\]{}][^,:\[\]{}]*)""")

    fun scalar(v0: String): Any? {
        val v = v0.trim()
        if (v == "" || v == "~" || v.equals("null", true)) return null
        if (v.equals("true", true)) return true
        if (v.equals("false", true)) return false
        if (NUMRE.matches(v)) return if (v.any { it == '.' || it == 'e' || it == 'E' }) v.toDouble() else (v.toLongOrNull() ?: v.toDouble())
        if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) return try { Js.parse(v) } catch (e: Exception) { v.substring(1, v.length - 1) }
        if (v.length >= 2 && v.startsWith("'") && v.endsWith("'")) return v.substring(1, v.length - 1).replace("''", "'")
        if (v.startsWith("[") || v.startsWith("{")) return flow(v)
        return v
    }

    private fun flow(v: String): Any? {
        try { return Js.parse(v) } catch (e: Exception) { /* skús voľnejší zápis */ }
        val fixed = FLOWTOK.replace(v) { m ->
            val q = m.groups[1]?.value
            val b = m.groups[2]?.value
            if (q != null) (if (q[0] == '"') q else Js.quote(q.substring(1, q.length - 1))) else Js.stringify(scalar(b ?: ""))
        }
        return Js.parse(fixed)
    }

    private fun strip(r: String): String {
        var q: Char? = null
        for (k in r.indices) {
            val c = r[k]
            if (q != null) { if (c == q) q = null }
            else if (c == '"' || c == '\'') q = c
            else if (c == '#' && (k == 0 || r[k - 1].isWhitespace())) return r.substring(0, k)
        }
        return r
    }

    private class Parser(val rows: MutableList<Row>) {
        var i = 0
        fun err(m: String): Nothing = throw Exception(str(R.string.line, i + 1, m))
        fun skip() { while (i < rows.size && (rows[i].t.isEmpty() || rows[i].t == "---" || rows[i].t == "...")) i++ }
        fun isSeq(t: String) = t == "-" || t.startsWith("- ")
        class KV(val k: String, val v: String)

        fun keyOf(t: String): KV? {
            val m = KEY.matchEntire(t) ?: return null
            val k = m.groupValues[1]
            return KV(if (k.startsWith("\"") || k.startsWith("'")) scalar(k).toString() else k, m.groups[2]?.value ?: "")
        }

        fun block(): Any? {
            skip()
            if (i >= rows.size) return null
            val c = rows[i]
            if (isSeq(c.t)) return seq(c.ind)
            if (keyOf(c.t) != null) return map(c.ind)
            i++
            return scalar(c.t)
        }

        fun bscalar(h: String, ind: Int): String {
            val l = mutableListOf<String>()
            var bi = -1
            while (i < rows.size) {
                val w = rows[i]
                if (w.r.isBlank()) { l.add(""); i++; continue }
                if (w.ind <= ind) break
                if (bi < 0) bi = w.ind
                l.add(w.r.substring(minOf(bi, w.r.length)))
                i++
            }
            while (l.isNotEmpty() && l.last() == "") l.removeAt(l.size - 1)
            var t = l.joinToString("\n")
            if (h[0] == '>') t = t.replace(Regex("([^\\n])\\n(?=[^\\n])"), "\$1 ").replace("\n\n", "\n")
            return if (h.endsWith("-")) t else t + "\n"
        }

        fun value(v: String, ind: Int, inMap: Boolean): Any? {
            if (BLOCK.matches(v)) return bscalar(v, ind)
            if (v != "") return scalar(v)
            skip()
            if (i < rows.size) {
                val n = rows[i]
                if (n.ind > ind) return block()
                if (inMap && n.ind == ind && isSeq(n.t)) return seq(ind)
            }
            return null
        }

        fun map(ind: Int): Map<String, Any?> {
            val o = LinkedHashMap<String, Any?>()
            while (true) {
                skip()
                if (i >= rows.size || rows[i].ind != ind) break
                val kv = keyOf(rows[i].t) ?: err(str(R.string.expected_key_value))
                i++
                o[kv.k] = value(kv.v, ind, true)
            }
            return o
        }

        fun seq(ind: Int): List<Any?> {
            val a = mutableListOf<Any?>()
            while (true) {
                skip()
                if (i >= rows.size || rows[i].ind != ind || !isSeq(rows[i].t)) break
                val rest = rows[i].t.substring(1).trim()
                if (rest.isEmpty()) {
                    i++; skip()
                    a.add(if (i < rows.size && rows[i].ind > ind) block() else null)
                } else if (keyOf(rest) != null) {
                    val o = rows[i].r.indexOf('-') + 1
                    val ni = o + rows[i].r.substring(o).indexOfFirst { !it.isWhitespace() }
                    rows[i] = Row("", ni, rest)
                    a.add(map(ni))
                } else {
                    i++
                    a.add(value(rest, ind, false))
                }
            }
            return a
        }
    }

    fun parse(src: String): Any? {
        val rows = src.replace("\r", "").replace("\t", "  ").split("\n").map { r ->
            val ind = r.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) r.length else it }
            Row(r, ind, strip(r).trim())
        }.toMutableList()
        val p = Parser(rows)
        val r = p.block()
        p.skip()
        if (p.i < rows.size) p.err(str(R.string.unexpected_indentation))
        return r
    }

    private val Y1 = Regex("^(true|false|null|~|yes|no|on|off)$", RegexOption.IGNORE_CASE)
    private val Y2 = Regex("^[-+]?[\\d.]+([eE][+-]?\\d+)?$")
    private val Y3 = Regex("^\\s|\\s$|^[-?:,\\[\\]{}#&*!|>'\"%@`]|: | #|\\n")
    private fun yStr(s: String) = if (s == "" || Y1.matches(s) || Y2.matches(s) || Y3.containsMatchIn(s)) Js.quote(s) else s
    private fun full(x: Any?) = (x is Map<*, *> && x.isNotEmpty()) || (x is List<*> && x.isNotEmpty())

    fun dump(v: Any?, d: Int = 0): String {
        val p = "  ".repeat(d)
        return when (v) {
            is List<*> -> if (v.isEmpty()) "[]" else v.joinToString("\n") { x ->
                val s = dump(x, d + 1)
                p + "- " + (if (full(x)) s.trimStart() else s)
            }
            is Map<*, *> -> if (v.isEmpty()) "{}" else v.entries.joinToString("\n") { e ->
                val k = yStr(e.key.toString())
                if (full(e.value)) p + k + ":\n" + dump(e.value, d + 1) else p + k + ": " + dump(e.value, d + 1)
            }
            null -> "null"
            is String -> yStr(v)
            is Number -> Js.fmt(v)
            else -> v.toString()
        }
    }
}

// ───────────────────────── JSONPath / TypeScript / query ─────────────────────────

fun jsonPath(root: Any?, pathIn: String): Pair<String, Int> {
    val path = pathIn.trim().removePrefix("$")
    val re = Regex("""\.\.([^.\[\s]+)|\.([^.\[\s]+)|\[(\d+|\*|'[^']*'|"[^"]*")\]""")
    val tk = mutableListOf<Pair<Boolean, String>>()
    var last = 0
    while (true) {
        val m = re.find(path, last) ?: break
        if (m.range.first != last) break
        last = m.range.last + 1
        val g1 = m.groups[1]?.value
        val g2 = m.groups[2]?.value
        val g3 = m.groups[3]?.value
        if (g1 != null) tk.add(true to g1)
        else tk.add(false to (g2 ?: g3!!.replace(Regex("^['\"]|['\"]$"), "")))
    }
    if (last != path.length) throw Exception(str(R.string.unsupported_path_syntax))
    fun kids(n: Any?): List<Any?> = when (n) { is Map<*, *> -> n.values.toList(); is List<*> -> n; else -> emptyList() }
    fun all(n: Any?): List<Any?> = listOf(n) + kids(n).flatMap { all(it) }
    fun ch(n: Any?, k: String): List<Any?> = when {
        k == "*" -> kids(n)
        n is Map<*, *> -> if (n.containsKey(k)) listOf(n[k]) else emptyList()
        n is List<*> -> k.toIntOrNull()?.let { if (it in n.indices) listOf(n[it]) else emptyList() } ?: emptyList()
        else -> emptyList()
    }
    var cur: List<Any?> = listOf(root)
    for ((d, k) in tk) cur = if (d) cur.flatMap { all(it) }.flatMap { ch(it, k) } else cur.flatMap { ch(it, k) }
    val single = cur.size == 1 && !path.contains('*') && !path.contains("..")
    return Js.stringify(if (single) cur[0] else cur, 2) to cur.size
}

private val IDENT = Regex("^[A-Za-z_$][\\w$]*$")

fun tsType(v: Any?, d: Int): String = when (v) {
    null -> "null"
    is List<*> -> if (v.isEmpty()) "unknown[]" else {
        val t = v.map { tsType(it, d) }.distinct()
        (if (t.size > 1) "(" + t.joinToString(" | ") + ")" else t[0]) + "[]"
    }
    is Map<*, *> -> if (v.isEmpty()) "{}" else {
        val p = "  ".repeat(d + 1)
        "{\n" + v.entries.joinToString("\n") { e ->
            val k = e.key.toString()
            p + (if (IDENT.matches(k)) k else Js.quote(k)) + ": " + tsType(e.value, d + 1) + ";"
        } + "\n" + "  ".repeat(d) + "}"
    }
    is String -> "string"
    is Boolean -> "boolean"
    else -> "number"
}

fun jsonToTs(text: String, name: String): String {
    val o = Js.parse(text)
    val n = name.trim().ifEmpty { "Root" }
    return if (o is Map<*, *>) "interface $n ${tsType(o, 0)}" else "type $n = ${tsType(o, 0)};"
}

fun queryToJson(input: String): String {
    val s = Regex("^[^?=&]*\\?").replace(input.trim(), "")
    val o = LinkedHashMap<String, Any?>()
    for (part in s.split('&')) {
        if (part.isEmpty()) continue
        val i = part.indexOf('=')
        val k = URLDecoder.decode(if (i < 0) part else part.substring(0, i), "UTF-8")
        val v = if (i < 0) "" else URLDecoder.decode(part.substring(i + 1), "UTF-8")
        val ex = o[k]
        @Suppress("UNCHECKED_CAST")
        when {
            !o.containsKey(k) -> o[k] = v
            ex is MutableList<*> -> (ex as MutableList<Any?>).add(v)
            else -> o[k] = mutableListOf<Any?>(ex, v)
        }
    }
    return Js.stringify(o, 2)
}

fun jsonToQuery(text: String): String {
    val o = Js.parse(text) as? Map<*, *> ?: throw Exception(str(R.string.expected_an_object))
    val parts = mutableListOf<String>()
    for ((k, v) in o) {
        val items = if (v is List<*>) v else listOf(v)
        for (x in items) {
            val sv = if (x is Map<*, *> || x is List<*>) Js.stringify(x) else if (x is Number) Js.fmt(x) else x.toString()
            parts.add(URLEncoder.encode(k.toString(), "UTF-8") + "=" + URLEncoder.encode(sv, "UTF-8"))
        }
    }
    return parts.joinToString("&")
}

// ───────────────────────── JWT ─────────────────────────

fun b64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trim().replace('+', '-').replace('/', '_').trimEnd('='))

fun fmtEpoch(sec: Long): String =
    ZonedDateTime.ofInstant(Instant.ofEpochSecond(sec), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(SK))

class JwtInfo(val header: String, val payload: String, val sig: String, val msg: String, val ok: Boolean)

fun jwtDecode(t0: String): JwtInfo {
    val p = t0.trim().split('.')
    if (p.size != 3) throw Exception(str(R.string.a_jwt_must_have_3_parts_separated_by_dot))
    try {
        val h = Js.parse(String(b64u(p[0]), Charsets.UTF_8)) as Map<*, *>
        val pl = Js.parse(String(b64u(p[1]), Charsets.UTF_8)) as Map<*, *>
        var m = str(R.string.algorithm, h["alg"] ?: "?")
        var ok = true
        (pl["exp"] as? Number)?.toLong()?.let {
            val expired = it < Instant.now().epochSecond
            if (expired) ok = false
            m += " | exp: ${fmtEpoch(it)} (${if (expired) str(R.string.expired) else str(R.string.valid)})"
        }
        (pl["iat"] as? Number)?.toLong()?.let { m += " | iat: ${fmtEpoch(it)}" }
        return JwtInfo(Js.stringify(h, 2), Js.stringify(pl, 2), p[2], m, ok)
    } catch (e: Exception) {
        throw Exception(str(R.string.could_not_decode_the_token))
    }
}

// ───────────────────────── Farby ─────────────────────────

fun rgb2hsl(r0: Int, g0: Int, b0: Int): Triple<Int, Int, Int> {
    val r = r0 / 255.0; val g = g0 / 255.0; val b = b0 / 255.0
    val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
    val l = (mx + mn) / 2; val d = mx - mn
    var h = 0.0; var s = 0.0
    if (d != 0.0) {
        s = d / (1 - Math.abs(2 * l - 1))
        h = when (mx) { r -> ((g - b) / d) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 }
        h *= 60
        if (h < 0) h += 360
    }
    return Triple(Math.round(h).toInt(), Math.round(s * 100).toInt(), Math.round(l * 100).toInt())
}

fun hsl2rgb(h: Double, s0: Double, l0: Double): Triple<Int, Int, Int> {
    val s = s0 / 100; val l = l0 / 100
    val a = s * minOf(l, 1 - l)
    fun k(n: Int) = (n + h / 30) % 12
    fun f(n: Int) = Math.round((l - a * maxOf(-1.0, minOf(k(n) - 3, minOf(9 - k(n), 1.0)))) * 255).toInt()
    return Triple(f(0), f(8), f(4))
}

fun parseColor(v0: String): Triple<Int, Int, Int>? {
    val v = v0.trim()
    Regex("^#?([0-9a-f]{3}|[0-9a-f]{6})$", RegexOption.IGNORE_CASE).matchEntire(v)?.let {
        var h = it.groupValues[1]
        if (h.length == 3) h = h.map { c -> "$c$c" }.joinToString("")
        return Triple(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
    }
    Regex("^rgb\\(\\s*(\\d+)[,\\s]+(\\d+)[,\\s]+(\\d+)\\s*\\)$", RegexOption.IGNORE_CASE).matchEntire(v)?.let {
        val (a, b, c) = it.destructured
        return Triple(a.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255), c.toInt().coerceIn(0, 255))
    }
    Regex("^hsl\\(\\s*(\\d+)[,\\s]+(\\d+)%[,\\s]+(\\d+)%\\s*\\)$", RegexOption.IGNORE_CASE).matchEntire(v)?.let {
        val (a, b, c) = it.destructured
        return hsl2rgb(a.toDouble(), b.toDouble().coerceIn(0.0, 100.0), c.toDouble().coerceIn(0.0, 100.0))
    }
    return null
}

fun hexOf(r: Int, g: Int, b: Int) = "#%02x%02x%02x".format(r, g, b)

fun relLum(r: Int, g: Int, b: Int): Double {
    fun c(x: Int): Double { val v = x / 255.0; return if (v <= .03928) v / 12.92 else Math.pow((v + .055) / 1.055, 2.4) }
    return .2126 * c(r) + .7152 * c(g) + .0722 * c(b)
}

fun contrastRatio(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Double {
    val l1 = relLum(a.first, a.second, a.third); val l2 = relLum(b.first, b.second, b.third)
    return (maxOf(l1, l2) + .05) / (minOf(l1, l2) + .05)
}

fun contrastReport(r: Double): String {
    fun t(ok: Boolean) = if (ok) str(R.string.passed) else str(R.string.failed)
    return str(R.string.aa_normal_text_4_5_1_aa_large_text_3_1, t(r >= 4.5), t(r >= 3)) +
        str(R.string.aaa_normal_text_7_1_aaa_large_text_4_5_1, t(r >= 7), t(r >= 4.5))
}

// ───────────────────────── Text ─────────────────────────

class DiffLine(val kind: Char, val text: String)

fun diffLines(a0: String, b0: String): Triple<List<DiffLine>, Int, Int> {
    val a = a0.split("\n"); val b = b0.split("\n")
    if (a.size.toLong() * b.size > 4_000_000L) throw Exception(str(R.string.the_texts_are_too_long))
    val l = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.size - 1 downTo 0) for (j in b.size - 1 downTo 0)
        l[i][j] = if (a[i] == b[j]) l[i + 1][j + 1] + 1 else maxOf(l[i + 1][j], l[i][j + 1])
    var i = 0; var j = 0; var ad = 0; var de = 0
    val out = ArrayList<DiffLine>()
    while (i < a.size || j < b.size) {
        if (i < a.size && j < b.size && a[i] == b[j]) { out.add(DiffLine(' ', a[i])); i++; j++ }
        else if (j < b.size && (i == a.size || l[i][j + 1] >= l[i + 1][j])) { out.add(DiffLine('+', b[j])); j++; ad++ }
        else { out.add(DiffLine('-', a[i])); i++; de++ }
    }
    return Triple(out, ad, de)
}

fun csvEsc(v: Any?): String {
    val s = when (v) { null -> ""; is Map<*, *>, is List<*> -> Js.stringify(v); is Number -> Js.fmt(v); else -> v.toString() }
    return if (s.any { it == '"' || it == ',' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

fun jsonToCsv(text: String): Pair<String, String> {
    var d = Js.parse(text)
    if (d !is List<*>) d = listOf(d)
    val objs = (d as List<*>).map { it as? Map<*, *> ?: throw Exception(str(R.string.expected_an_array_of_objects)) }
    val h = LinkedHashSet<String>()
    objs.forEach { o -> o.keys.forEach { h.add(it.toString()) } }
    val lines = listOf(h.joinToString(",") { csvEsc(it) }) + objs.map { o -> h.joinToString(",") { k -> csvEsc(o[k]) } }
    return lines.joinToString("\n") to str(R.string.rows_columns, objs.size, h.size)
}

fun parseCsv(t: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var r = mutableListOf<String>()
    val c = StringBuilder()
    var q = false
    var i = 0
    while (i < t.length) {
        val ch = t[i]
        if (q) {
            if (ch == '"') { if (i + 1 < t.length && t[i + 1] == '"') { c.append('"'); i++ } else q = false } else c.append(ch)
        } else if (ch == '"') q = true
        else if (ch == ',') { r.add(c.toString()); c.setLength(0) }
        else if (ch == '\n' || ch == '\r') {
            if (ch == '\r' && i + 1 < t.length && t[i + 1] == '\n') i++
            r.add(c.toString()); c.setLength(0); rows.add(r); r = mutableListOf()
        } else c.append(ch)
        i++
    }
    if (c.isNotEmpty() || r.isNotEmpty()) { r.add(c.toString()); rows.add(r) }
    return rows
}

fun csvToJson(text: String): Pair<String, String> {
    val rows = parseCsv(text).filter { r -> r.any { it != "" } }
    if (rows.size < 2) throw Exception(str(R.string.the_csv_must_have_a_header_and_at_least))
    val h = rows[0]
    val list = rows.drop(1).map { r -> LinkedHashMap<String, Any?>().also { m -> h.forEachIndexed { i, k -> m[k] = r.getOrElse(i) { "" } } } }
    return Js.stringify(list, 2) to str(R.string.records, rows.size - 1)
}

private val ENT = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0", "copy" to "©", "reg" to "®",
    "euro" to "€", "hellip" to "…", "ndash" to "–", "mdash" to "—", "laquo" to "«", "raquo" to "»", "trade" to "™",
    "deg" to "°", "plusmn" to "±", "times" to "×", "divide" to "÷", "middot" to "·", "bull" to "•", "pound" to "£",
    "yen" to "¥", "cent" to "¢", "sect" to "§", "para" to "¶", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”"
)

fun htmlEncode(t: String) = t.replace(Regex("[&<>\"']")) { when (it.value) { "&" -> "&amp;"; "<" -> "&lt;"; ">" -> "&gt;"; "\"" -> "&quot;"; else -> "&#39;" } }

fun htmlDecode(t: String) = t.replace(Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z][a-zA-Z0-9]*);")) { m ->
    val g = m.groupValues[1]
    when {
        g.startsWith("#x") || g.startsWith("#X") -> g.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        g.startsWith("#") -> g.substring(1).toIntOrNull()?.let { if (it in 0..0x10FFFF) String(Character.toChars(it)) else m.value } ?: m.value
        else -> ENT[g] ?: m.value
    }
}

val LINE_OPS: List<Pair<String, (List<String>) -> List<String>>> = listOf(
    str(R.string.sort_a_z) to { a -> val c = Collator.getInstance(Locale.getDefault()); a.sortedWith { x, y -> c.compare(x, y) } },
    str(R.string.sort_z_a) to { a -> val c = Collator.getInstance(Locale.getDefault()); a.sortedWith { x, y -> c.compare(y, x) } },
    str(R.string.remove_duplicates) to { a -> a.distinct() },
    str(R.string.reverse_order) to { a -> a.reversed() },
    str(R.string.trim_whitespace) to { a -> a.map { it.trim() } },
    str(R.string.remove_empty) to { a -> a.filter { it.isNotBlank() } },
    str(R.string.shuffle) to { a -> a.toMutableList().also { l -> for (i in l.size - 1 downTo 1) { val j = rng.nextInt(i + 1); val t = l[i]; l[i] = l[j]; l[j] = t } } }
)

// ───────────────────────── Cron ─────────────────────────

private fun cronField(f: String, min: Int, max: Int): Set<Int> {
    val set = HashSet<Int>()
    for (part in f.split(',')) {
        val sp = part.split('/')
        val r = sp[0]
        val st = if (sp.size > 1) sp[1].toInt() else 1
        require(st > 0)
        val a: Int; val b: Int
        when {
            r == "*" -> { a = min; b = max }
            r.contains('-') -> { val x = r.split('-'); a = x[0].toInt(); b = x[1].toInt() }
            else -> { a = r.toInt(); b = if (sp.size > 1) max else a }
        }
        require(a >= min && b <= max && a <= b)
        var i = a
        while (i <= b) { set.add(i); i += st }
    }
    return set
}

fun cronNext(expr: String, count: Int = 8, from: LocalDateTime = LocalDateTime.now()): List<String> {
    val v = expr.trim().split(Regex("\\s+"))
    if (v.size != 5) throw Exception(str(R.string.the_expression_must_have_exactly_5_field))
    val m: Set<Int>; val h: Set<Int>; val dm: Set<Int>; val mo: Set<Int>; val dw: Set<Int>
    try {
        m = cronField(v[0], 0, 59); h = cronField(v[1], 0, 23); dm = cronField(v[2], 1, 31); mo = cronField(v[3], 1, 12)
        dw = cronField(v[4], 0, 7).map { it % 7 }.toSet()
    } catch (e: Exception) { throw Exception(str(R.string.invalid_expression_check_the_value_range)) }
    val both = v[2] != "*" && v[4] != "*"
    var d = from.withSecond(0).withNano(0).plusMinutes(1)
    val res = mutableListOf<String>()
    val fmt = DateTimeFormatter.ofPattern("EEE dd.MM.yyyy HH:mm", SK)
    var i = 0
    while (i < 527040 && res.size < count) {
        if (d.monthValue in mo && d.hour in h && d.minute in m) {
            val a = d.dayOfMonth in dm
            val b = (d.dayOfWeek.value % 7) in dw
            if (if (both) (a || b) else (a && b)) res.add(d.format(fmt))
        }
        d = d.plusMinutes(1); i++
    }
    return res
}

// ───────────────────────── Krypto / čas ─────────────────────────

fun hmacOf(alg: String, key: String, msg: String): Pair<String, String> {
    val name = "Hmac" + alg.replace("-", "")
    val mac = Mac.getInstance(name)
    mac.init(SecretKeySpec(key.toByteArray(), name))
    val sig = mac.doFinal(msg.toByteArray())
    return sig.joinToString("") { "%02x".format(it) } to Base64.getEncoder().encodeToString(sig)
}

val TZ_LIST = listOf("UTC", "Europe/Bratislava", "Europe/London", "America/New_York", "America/Los_Angeles", "Asia/Dubai", "Asia/Kolkata", "Asia/Tokyo", "Australia/Sydney")

fun parseLdt(s0: String): LocalDateTime {
    val s = s0.trim().replace(' ', 'T')
    return try { LocalDateTime.parse(s) } catch (e: Exception) {
        try { LocalDate.parse(s).atStartOfDay() } catch (e2: Exception) { throw Exception(str(R.string.invalid_date_use_the_format_2024_01_31t1)) }
    }
}

fun tzReport(local: String, src: String): String {
    val zdt = parseLdt(local).atZone(try { ZoneId.of(src.trim()) } catch (e: Exception) { throw Exception(str(R.string.unknown_time_zone)) })
    val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(SK)
    return TZ_LIST.joinToString("\n") { z ->
        val x = zdt.withZoneSameInstant(ZoneId.of(z))
        val o = x.offset.totalSeconds / 60
        val a = Math.abs(o)
        z.padEnd(22) + x.format(fmt) + "  (UTC" + (if (o < 0) "-" else "+") + "%02d:%02d)".format(a / 60, a % 60)
    }
}

fun dateDiff(a0: String, b0: String): String {
    val z = ZoneId.systemDefault()
    val a = parseLdt(a0).atZone(z); val b = parseLdt(b0).atZone(z)
    var ms = Duration.between(a, b).toMillis()
    val neg = ms < 0
    ms = Math.abs(ms)
    val s = ms / 1000
    val r = Locale.ROOT
    return str(R.string.difference_d_h_min_s, if (neg) "-" else "", s / 86400, s % 86400 / 3600, s % 3600 / 60, s % 60) +
        str(R.string.days_weeks, String.format(r, "%.3f", ms / 864e5), String.format(r, "%.2f", ms / 6048e5)) +
        str(R.string.hours_minutes_seconds, String.format(r, "%.2f", ms / 36e5), ms / 60000, s)
}

// ───────────────────────── Escape / hex / šifry ─────────────────────────

val ESC_OPS: List<Pair<String, (String) -> String>> = listOf(
    "Escape (JSON)" to { t -> Js.quote(t).let { it.substring(1, it.length - 1) } },
    "Unescape (JSON)" to { t -> Js.parse("\"" + Regex("\\\\?\"").replace(t, Regex.escapeReplacement("\\\"")) + "\"") as String },
    "Znaky na \\uXXXX" to { t -> t.map { c -> if (c.code > 0x7f) "\\u%04x".format(c.code) else c.toString() }.joinToString("") },
    "\\uXXXX na znaky" to { t -> Regex("\\\\u([0-9a-fA-F]{4})").replace(t) { it.groupValues[1].toInt(16).toChar().toString() } }
)

val HEX_OPS: List<Pair<String, (String) -> String>> = listOf(
    "Text → Hex" to { t -> t.toByteArray().joinToString(" ") { "%02x".format(it) } },
    "Hex → Text" to { t ->
        val h = t.replace(Regex("0x|[\\s,]", RegexOption.IGNORE_CASE), "")
        require(h.length % 2 == 0 && h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
        String(ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, Charsets.UTF_8)
    },
    str(R.string.text_binary) to { t -> t.toByteArray().joinToString(" ") { Integer.toBinaryString(it.toInt() and 255).padStart(8, '0') } },
    str(R.string.binary_text) to { t ->
        val h = t.replace(Regex("\\s"), "")
        require(h.length % 8 == 0 && h.all { it == '0' || it == '1' })
        String(ByteArray(h.length / 8) { h.substring(it * 8, it * 8 + 8).toInt(2).toByte() }, Charsets.UTF_8)
    }
)

private val MO = mapOf(
    'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..",
    'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
    'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--", 'Z' to "--..",
    '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
    '8' to "---..", '9' to "----.", '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '!' to "-.-.--", '/' to "-..-.", '-' to "-....-"
)
private val MR = MO.entries.associate { it.value to it.key }

private fun rot(t: String, n: Int) = t.map { c ->
    if (c in 'a'..'z' || c in 'A'..'Z') { val b = if (c <= 'Z') 'A'.code else 'a'.code; (((c.code - b + n % 26) + 26) % 26 + b).toChar() } else c
}.joinToString("")

val CIPHERS = listOf("Caesar", "ROT13", "Atbash", "Text → Morse", "Morse → Text")

fun cipher(mode: String, t: String, key: Int): String = when (mode) {
    "Caesar" -> rot(t, key)
    "ROT13" -> rot(t, 13)
    "Atbash" -> t.map { c ->
        if (c in 'a'..'z' || c in 'A'..'Z') { val b = if (c <= 'Z') 'A'.code else 'a'.code; (b + 25 - (c.code - b)).toChar() } else c
    }.joinToString("")
    "Text → Morse" -> Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").uppercase()
        .mapNotNull { c -> if (c == ' ') "/" else MO[c] }.joinToString(" ")
    else -> t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("") { x -> if (x == "/") " " else (MR[x]?.toString() ?: "?") }
}

fun slugOf(t: String, sep: String) = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
    .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString(sep)

fun unicodeReport(t: String): Pair<String, String> {
    val cps = t.codePoints().toArray()
    val shown = cps.take(300)
    val lines = shown.joinToString("\n") { cp ->
        val s = String(Character.toChars(cp))
        val u8 = s.toByteArray().joinToString(" ") { "%02x".format(it) }
        val u16 = s.map { "%04x".format(it.code) }.joinToString(" ")
        (if (cp < 32 || cp == 127) "·" else s).padEnd(4) + ("U+" + "%04X".format(cp)).padEnd(10) + ("UTF-8: $u8").padEnd(26) + "UTF-16: $u16"
    } + (if (cps.size > 300) "\n…" else "")
    return lines to str(R.string.code_points_utf_16_units_utf_8_bytes, cps.size, t.length, t.toByteArray().size)
}

fun textStats(t: String): String {
    val w = Regex("[\\p{L}\\p{N}'’-]+").findAll(t).map { it.value }.toList()
    val f = LinkedHashMap<String, Int>()
    w.forEach { x -> val k = x.lowercase(); if (k.length > 2) f[k] = (f[k] ?: 0) + 1 }
    val top = f.entries.sortedByDescending { it.value }.take(8)
    val sentences = Regex("[^.!?]+[.!?]+").findAll(t).count().let { if (it == 0 && t.isNotBlank()) 1 else it }
    val rows = listOf(
        str(R.string.characters) to t.codePointCount(0, t.length), str(R.string.characters_without_spaces) to t.replace(Regex("\\s"), "").let { it.codePointCount(0, it.length) },
        str(R.string.words) to w.size, str(R.string.sentences) to sentences, str(R.string.lines_2) to (if (t.isEmpty()) 0 else t.split("\n").size),
        str(R.string.paragraphs) to t.split(Regex("\\n\\s*\\n")).count { it.isNotBlank() },
        str(R.string.reading_time) to "${Math.ceil(w.size / 200.0).toInt()} min", str(R.string.bytes_utf_8) to t.toByteArray().size
    )
    return rows.joinToString("\n") { it.first.padEnd(20) + it.second } +
        (if (top.isNotEmpty()) str(R.string.most_frequent_words) + top.joinToString("\n") { it.key.padEnd(20) + it.value + "×" } else "")
}

private val LW = ("lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua enim ad minim " +
    "veniam quis nostrud exercitation ullamco laboris nisi aliquip ex ea commodo consequat duis aute irure in reprehenderit voluptate velit esse cillum " +
    "fugiat nulla pariatur excepteur sint occaecat cupidatat non proident sunt culpa qui officia deserunt mollit anim id est laborum").split(" ")

fun lorem(unit: String, n0: Int, classic: Boolean): String {
    val n = n0.coerceIn(1, if (unit == "w") 500 else 50)
    fun w() = LW[rng.nextInt(LW.size)]
    fun sent(): String { val ws = MutableList(5 + rng.nextInt(10)) { w() }; ws[0] = ws[0].replaceFirstChar { it.uppercase() }; return ws.joinToString(" ") + "." }
    fun para() = List(3 + rng.nextInt(4)) { sent() }.joinToString(" ")
    val r = when (unit) {
        "p" -> MutableList(n) { para() }
        "s" -> mutableListOf(List(n) { sent() }.joinToString(" "))
        else -> mutableListOf(List(n) { w() }.joinToString(" "))
    }
    if (classic) r[0] = "Lorem ipsum dolor sit amet, " + r[0].replaceFirstChar { it.lowercase() }
    return r.joinToString("\n\n")
}

private fun cssClean(t: String) = t.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("\\s+"), " ").trim()

fun cssMin(t: String) = cssClean(t).replace(Regex("\\s*([{};,>])\\s*"), "\$1").replace(Regex(":\\s+"), ":").replace(";}", "}")

fun cssFormat(t: String): String {
    var d = 0
    val o = StringBuilder()
    fun trimEnd() { while (o.isNotEmpty() && o.last().isWhitespace()) o.setLength(o.length - 1) }
    for (c in cssClean(t)) when (c) {
        '{' -> { trimEnd(); d++; o.append(" {\n").append("  ".repeat(d)) }
        '}' -> { d = maxOf(0, d - 1); trimEnd(); o.append("\n").append("  ".repeat(d)).append("}\n").append("  ".repeat(d)) }
        ';' -> o.append(";\n").append("  ".repeat(d))
        else -> o.append(c)
    }
    return o.toString().replace(Regex("[ ]+\\n"), "\n")
        .replace(Regex("^(\\s*[\\w-]+):([^\\n{]*;)$", RegexOption.MULTILINE), "\$1: \$2")
        .replace(Regex("\\n{3,}"), "\n\n").replace(Regex("\\n\\s*\\n(\\s*\\})"), "\n\$1").trim()
}

// ───────────────────────── Jednotky, HTTP kódy, čísla ─────────────────────────

val UNITS: Map<String, Map<String, Double>> = linkedMapOf(
    str(R.string.length) to linkedMapOf("m" to 1.0, "km" to 1000.0, "cm" to .01, "mm" to .001, str(R.string.mile) to 1609.344, "yard" to .9144, str(R.string.foot) to .3048, str(R.string.inch) to .0254),
    str(R.string.weight) to linkedMapOf("kg" to 1.0, "g" to .001, "mg" to 1e-6, "t" to 1000.0, str(R.string.pound) to .45359237, str(R.string.ounce) to .028349523125),
    str(R.string.data) to linkedMapOf("B" to 1.0, "KiB" to 1024.0, "MiB" to 1048576.0, "GiB" to 1073741824.0, "TiB" to 1099511627776.0, "kB" to 1e3, "MB" to 1e6, "GB" to 1e9, "TB" to 1e12),
    str(R.string.time) to linkedMapOf("s" to 1.0, "min" to 60.0, "h" to 3600.0, str(R.string.day) to 86400.0, str(R.string.week) to 604800.0, "rok (365 d)" to 31536000.0),
    str(R.string.temperature) to linkedMapOf("°C" to 0.0, "°F" to 0.0, "K" to 0.0)
)

fun fmtSig(n: Double): String {
    if (n.isNaN() || n.isInfinite()) return "—"
    val bd = BigDecimal(n).round(MathContext(10)).stripTrailingZeros()
    val nf = java.text.NumberFormat.getNumberInstance(SK)
    nf.maximumFractionDigits = 30
    nf.minimumFractionDigits = 0
    return nf.format(bd)
}

fun fmtNum(n: Double, frac: Int = 4): String {
    if (n.isNaN() || n.isInfinite()) return "—"
    val nf = java.text.NumberFormat.getNumberInstance(SK)
    nf.maximumFractionDigits = frac
    return nf.format(n)
}

fun unitReport(cat: String, unit: String, v: Double): String {
    val tbl = UNITS.getValue(cat)
    val l: List<Pair<String, Double>> = if (cat == str(R.string.temperature)) {
        val c = when (unit) { "°C" -> v; "°F" -> (v - 32) * 5 / 9; else -> v - 273.15 }
        listOf("°C" to c, "°F" to c * 9 / 5 + 32, "K" to c + 273.15)
    } else {
        val b = v * tbl.getValue(unit)
        tbl.map { (k, m) -> k to b / m }
    }
    return l.joinToString("\n") { it.first.padEnd(14) + fmtSig(it.second) }
}

val HTTP_CODES: List<Triple<String, String, String>> = """100|Continue|${str(R.string.http_100)}
101|Switching Protocols|${str(R.string.http_101)}
200|OK|${str(R.string.http_200)}
201|Created|${str(R.string.http_201)}
202|Accepted|${str(R.string.http_202)}
204|No Content|${str(R.string.http_204)}
206|Partial Content|${str(R.string.http_206)}
301|Moved Permanently|${str(R.string.http_301)}
302|Found|${str(R.string.http_302)}
303|See Other|${str(R.string.http_303)}
304|Not Modified|${str(R.string.http_304)}
307|Temporary Redirect|${str(R.string.http_307)}
308|Permanent Redirect|${str(R.string.http_308)}
400|Bad Request|${str(R.string.http_400)}
401|Unauthorized|${str(R.string.http_401)}
403|Forbidden|${str(R.string.http_403)}
404|Not Found|${str(R.string.http_404)}
405|Method Not Allowed|${str(R.string.http_405)}
408|Request Timeout|${str(R.string.http_408)}
409|Conflict|${str(R.string.http_409)}
410|Gone|${str(R.string.http_410)}
413|Payload Too Large|${str(R.string.http_413)}
415|Unsupported Media Type|${str(R.string.http_415)}
418|I'm a teapot|${str(R.string.http_418)}
422|Unprocessable Content|${str(R.string.http_422)}
429|Too Many Requests|${str(R.string.http_429)}
500|Internal Server Error|${str(R.string.http_500)}
501|Not Implemented|${str(R.string.http_501)}
502|Bad Gateway|${str(R.string.http_502)}
503|Service Unavailable|${str(R.string.http_503)}
504|Gateway Timeout|${str(R.string.http_504)}""".split("\n").map { l -> val p = l.split("|"); Triple(p[0], p[1], p[2]) }

fun httpCodesReport(q0: String): String {
    val q = q0.trim().lowercase()
    val r = HTTP_CODES.filter { q.isEmpty() || "${it.first} ${it.second} ${it.third}".lowercase().contains(q) }
    return if (r.isEmpty()) str(R.string.nothing_found_2) else r.joinToString("\n") { it.first + "  " + it.second + "\n     " + it.third }
}

// ───────────────────────── cURL ─────────────────────────

class CurlCmd(var method: String = "", val headers: MutableList<String> = mutableListOf(), var body: String = "", var follow: Boolean = false, var user: String = "", var url: String = "")

fun curlParse(t: String): CurlCmd {
    val tokRe = Regex("""(?:'[^']*'|"(?:[^"\\]|\\.)*"|[^\s'"])+""")
    val partRe = Regex("""'([^']*)'|"((?:[^"\\]|\\.)*)"""")
    val k = tokRe.findAll(t.replace(Regex("\\\\\\r?\\n"), " ")).map { m ->
        partRe.replace(m.value) { p -> p.groups[1]?.value ?: Regex("\\\\(.)").replace(p.groups[2]!!.value) { it.groupValues[1] } }
    }.toList()
    val o = CurlCmd()
    var i = 0
    fun nx() = if (i + 1 < k.size) k[++i] else ""
    while (i < k.size) {
        val x = k[i]
        when {
            x == "curl" -> {}
            x == "-X" || x == "--request" -> o.method = nx()
            x == "-H" || x == "--header" -> o.headers.add(nx())
            Regex("^(-d|--data(-raw|-binary|-ascii|-urlencode)?)$").matches(x) -> o.body = nx()
            x == "-u" || x == "--user" -> o.user = nx()
            x == "-L" || x == "--location" -> o.follow = true
            x == "-I" || x == "--head" -> o.method = "HEAD"
            x == "--url" -> o.url = nx()
            !x.startsWith("-") && o.url.isEmpty() -> o.url = x
        }
        i++
    }
    return o
}

fun curlHeaders(o: CurlCmd): List<Pair<String, String>> = o.headers.filter { it.isNotBlank() }.mapNotNull { l ->
    val i = l.indexOf(':')
    if (i > 0) l.substring(0, i).trim() to l.substring(i + 1).trim() else null
}

fun curlMethod(o: CurlCmd) = o.method.ifEmpty { if (o.body.isNotEmpty()) "POST" else "GET" }

fun curlConvert(o: CurlCmd, js: Boolean): String {
    val h = LinkedHashMap<String, Any?>()
    curlHeaders(o).forEach { h[it.first] = it.second }
    val j = o.user.indexOf(':')
    val user = if (o.user.isEmpty()) null else (if (j < 0) o.user else o.user.substring(0, j)) to (if (j < 0) "" else o.user.substring(j + 1))
    val m = curlMethod(o)
    if (js) {
        if (user != null) h["Authorization"] = "Basic " + Base64.getEncoder().encodeToString((user.first + ":" + user.second).toByteArray())
        val hs = Js.stringify(h, 2).replace("\n", "\n  ")
        return "fetch(${Js.quote(o.url)}, {\n  method: ${Js.quote(m)},\n  headers: $hs" +
            (if (o.body.isNotEmpty()) ",\n  body: ${Js.quote(o.body)}" else "") + (if (o.follow) "" else ",\n  redirect: 'manual'") +
            "\n})\n  .then(r => r.text())\n  .then(console.log);"
    }
    return "import requests\n\nr = requests.request(${Js.quote(m)}, ${Js.quote(o.url)},\n    headers=${Js.stringify(h)}" +
        (if (o.body.isNotEmpty()) ",\n    data=${Js.quote(o.body)}" else "") +
        (if (user != null) ",\n    auth=(${Js.quote(user.first)}, ${Js.quote(user.second)})" else "") +
        ",\n    allow_redirects=${if (o.follow) "True" else "False"})\nprint(r.status_code)\nprint(r.text)"
}
