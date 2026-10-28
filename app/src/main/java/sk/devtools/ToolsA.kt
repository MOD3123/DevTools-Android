// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sk.devtools

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

// ───────────────────────── Dáta a bezpečnosť ─────────────────────────

@Composable
fun JwtTool() = Page("JWT debugger", str(R.string.decodes_the_header_and_payload_the_signa)) {
    var t by rememberSaveable { mutableStateOf("") }
    Field(t, { t = it }, "Token", lines = 4)
    if (t.isNotBlank()) {
        val r = remember(t) { runCatching { jwtDecode(t) } }
        r.onSuccess { Out(it.msg, "Info"); Out(it.header, "Header"); Out(it.payload, "Payload"); Out(it.sig, "Signature") }
        r.onFailure { Err(it.message ?: str(R.string.error)) }
    }
}

@Composable
fun HmacTool() = Page("HMAC", str(R.string.sign_a_message_with_a_secret_key_hmac_in)) {
    var m by rememberSaveable { mutableStateOf("") }
    var k by rememberSaveable { mutableStateOf("") }
    var a by rememberSaveable { mutableStateOf("SHA-256") }
    Field(m, { m = it }, str(R.string.message), lines = 4)
    Field(k, { k = it }, str(R.string.secret_key))
    Chips(listOf("SHA-256", "SHA-384", "SHA-512", "SHA-1"), a) { a = it }
    if (k.isNotEmpty()) {
        val r = remember(m, k, a) { runCatching { hmacOf(a, k, m) } }
        r.onSuccess { Out(it.first, "Hex"); Out(it.second, "Base64") }
        r.onFailure { Err(str(R.string.calculation_failed, it.message)) }
    } else Err(str(R.string.enter_the_secret_key))
}

@Composable
fun JpathTool() = Page(str(R.string.jsonpath_query), str(R.string.search_in_json_supports_a_b_0_key_and_ke)) {
    var j by rememberSaveable { mutableStateOf("") }
    var p by rememberSaveable { mutableStateOf("\$.users[*].name") }
    Field(j, { j = it }, "JSON", lines = 7)
    Field(p, { p = it }, str(R.string.path))
    if (j.isNotBlank()) {
        val r = remember(j, p) { runCatching { jsonPath(Js.parse(j.trim()), p) } }
        r.onSuccess { Out(it.first, str(R.string.found, it.second)) }
        r.onFailure { Err(str(R.string.error_3, it.message)) }
    }
}

// ───────────────────────── Konvertory ─────────────────────────

@Composable
fun CsvTool() = Page("JSON ⇄ CSV", str(R.string.convert_an_array_of_objects_to_csv_and_b)) {
    var t by rememberSaveable { mutableStateOf("") }
    var m by rememberSaveable { mutableStateOf("JSON → CSV") }
    Chips(listOf("JSON → CSV", "CSV → JSON"), m) { m = it }
    Field(t, { t = it }, str(R.string.input), lines = 8)
    if (t.isNotBlank()) {
        val r = remember(t, m) { runCatching { if (m == "JSON → CSV") jsonToCsv(t) else csvToJson(t) } }
        r.onSuccess { Out(it.second, "Info"); Out(it.first, str(R.string.output)) }
        r.onFailure { Err(if (m == "JSON → CSV") str(R.string.invalid_json, it.message) else it.message ?: str(R.string.error)) }
    }
}

@Composable
fun EntTool() = Page("HTML entity", str(R.string.encode_and_decode_characters_such_as_amp)) {
    var t by rememberSaveable { mutableStateOf("") }
    Field(t, { t = it }, str(R.string.input), lines = 4)
    if (t.isNotEmpty()) {
        Out(htmlEncode(t), str(R.string.encoded))
        Out(htmlDecode(t), str(R.string.decoded))
    }
}

@Composable
fun EscTool() = Page(str(R.string.string_escaping), str(R.string.escape_and_unescape_for_json_and_javascr)) {
    var t by rememberSaveable { mutableStateOf("") }
    var op by rememberSaveable { mutableStateOf(ESC_OPS[0].first) }
    Field(t, { t = it }, str(R.string.input), lines = 4)
    Chips(ESC_OPS.map { it.first }, op) { op = it }
    if (t.isNotEmpty()) {
        val r = remember(t, op) { runCatching { ESC_OPS.first { it.first == op }.second(t) } }
        r.onSuccess { Out(it, str(R.string.output)) }
        r.onFailure { Err(str(R.string.the_input_could_not_be_processed)) }
    }
}

@Composable
fun HexbTool() = Page(str(R.string.text_hex_binary), str(R.string.convert_text_utf_8_to_hexadecimal_or_bin)) {
    var t by rememberSaveable { mutableStateOf("") }
    var op by rememberSaveable { mutableStateOf(HEX_OPS[0].first) }
    Field(t, { t = it }, str(R.string.input), lines = 4)
    Chips(HEX_OPS.map { it.first }, op) { op = it }
    if (t.isNotEmpty()) {
        val r = remember(t, op) { runCatching { HEX_OPS.first { it.first == op }.second(t) } }
        r.onSuccess { Out(it, str(R.string.output)) }
        r.onFailure { Err(str(R.string.the_input_has_an_invalid_format_for_this)) }
    }
}

@Composable
fun UnitsTool() = Page(str(R.string.unit_converter), str(R.string.length_weight_data_time_and_temperature)) {
    var c by rememberSaveable { mutableStateOf(str(R.string.length)) }
    var u by rememberSaveable { mutableStateOf("m") }
    var v by rememberSaveable { mutableStateOf("1") }
    Chips(UNITS.keys.toList(), c) { c = it; u = UNITS.getValue(it).keys.first() }
    Field(v, { v = it }, str(R.string.value))
    val units = UNITS.getValue(c).keys.toList()
    Chips(units, if (u in units) u else units.first()) { u = it }
    val x = v.trim().replace(',', '.').toDoubleOrNull()
    if (x != null) Out(unitReport(c, if (u in units) u else units.first(), x))
}

@Composable
fun RemTool() = Page("px ↔ rem", str(R.string.convert_css_units_based_on_the_base_font)) {
    var b by rememberSaveable { mutableStateOf("16") }
    var px by rememberSaveable { mutableStateOf("16") }
    var rem by rememberSaveable { mutableStateOf("1") }
    val base = b.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: 16.0
    fun f(d: Double) = Js.fmt((Math.round(d * 10000) / 10000.0))
    Field(b, { b = it }, str(R.string.base_px))
    Field(px, { px = it; rem = it.trim().replace(',', '.').toDoubleOrNull()?.let { p -> f(p / base) } ?: "" }, "px")
    Field(rem, { rem = it; px = it.trim().replace(',', '.').toDoubleOrNull()?.let { r -> f(r * base) } ?: "" }, "rem")
    Out(listOf(10, 12, 14, 16, 18, 20, 24, 28, 32, 40, 48, 64).joinToString("\n") { "$it px".padEnd(10) + f(it / base) + " rem" }, str(R.string.table))
}

@Composable
fun TsgenTool() = Page("JSON → TypeScript", str(R.string.generates_a_typescript_interface_from_js)) {
    var t by rememberSaveable { mutableStateOf("") }
    var n by rememberSaveable { mutableStateOf("Root") }
    Field(t, { t = it }, "JSON", lines = 7)
    Field(n, { n = it }, str(R.string.type_name))
    if (t.isNotBlank()) {
        val r = remember(t, n) { runCatching { jsonToTs(t.trim(), n) } }
        r.onSuccess { Out(it, "TypeScript") }
        r.onFailure { Err(str(R.string.invalid_json, it.message)) }
    }
}

@Composable
fun YamlTool() = Page("YAML ⇄ JSON", str(R.string.convert_between_yaml_and_json_supports_m)) {
    var t by rememberSaveable { mutableStateOf("") }
    var m by rememberSaveable { mutableStateOf("YAML → JSON") }
    Chips(listOf("YAML → JSON", "JSON → YAML"), m) { m = it }
    Field(t, { t = it }, str(R.string.input), lines = 8)
    if (t.isNotBlank()) {
        val r = remember(t, m) { runCatching { if (m == "YAML → JSON") Js.stringify(Yml.parse(t), 2) else Yml.dump(Js.parse(t.trim())) } }
        r.onSuccess { Out(it, str(R.string.output)) }
        r.onFailure { Err(if (m == "YAML → JSON") str(R.string.yaml_error, it.message) else str(R.string.invalid_json, it.message)) }
    }
}

@Composable
fun QstrTool() = Page("Query string ⇄ JSON", str(R.string.convert_url_parameters_to_json_and_back_2)) {
    var t by rememberSaveable { mutableStateOf("") }
    var m by rememberSaveable { mutableStateOf("Query → JSON") }
    Chips(listOf("Query → JSON", "JSON → Query"), m) { m = it }
    Field(t, { t = it }, str(R.string.input), lines = 4)
    if (t.isNotBlank()) {
        val r = remember(t, m) { runCatching { if (m == "Query → JSON") queryToJson(t) else jsonToQuery(t.trim()) } }
        r.onSuccess { Out(it, str(R.string.output)) }
        r.onFailure { Err(str(R.string.invalid_json, it.message)) }
    }
}

class ImgData(val info: String, val uri: String, val b64: String, val bmp: ImageBitmap?)

private fun loadImg(ctx: Context, uri: Uri): ImgData {
    val cr = ctx.contentResolver
    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception(str(R.string.the_file_could_not_be_opened))
    if (bytes.size > 8 * 1024 * 1024) throw Exception(str(R.string.the_image_is_too_large_limit_8_mb))
    val mime = cr.getType(uri) ?: "application/octet-stream"
    val name = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: str(R.string.image)
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1
    while (o.outWidth / (s * 2) >= 1024 || o.outHeight / (s * 2) >= 1024) s *= 2
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
    val b64 = Base64.getEncoder().encodeToString(bytes)
    val full = "data:$mime;base64,$b64"
    return ImgData(str(R.string.kb_kb_in_base64, name, mime, "%.1f".format(java.util.Locale.ROOT, bytes.size / 1024.0), "%.1f".format(java.util.Locale.ROOT, full.length / 1024.0)), full, b64, bmp)
}

@Composable
fun ImgTool() = Page(str(R.string.image_base64), str(R.string.convert_an_image_to_a_data_uri_or_plain_2)) {
    val ctx = LocalContext.current
    val sc = rememberCoroutineScope()
    var d by remember { mutableStateOf<ImgData?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            busy = true; err = null
            sc.launch {
                try { d = withContext(Dispatchers.IO) { loadImg(ctx, uri) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { err = e.message ?: str(R.string.an_error_occurred); d = null }
                busy = false
            }
        }
    }
    Act(if (busy) str(R.string.loading) else str(R.string.choose_an_image)) { if (!busy) pick.launch("image/*") }
    err?.let { Err(it) }
    d?.let { x ->
        Out(x.info, str(R.string.file))
        x.bmp?.let { Image(it, null, Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Fit) }
        fun cut(s: String) = if (s.length > 1500) s.take(1500) + "…" else s
        Out(cut(x.uri), "Data URI", copy = x.uri)
        Out(cut(x.b64), str(R.string.plain_base64), copy = x.b64)
    }
}

// ───────────────────────── Text ─────────────────────────

@Composable
fun RxTool() = Page("Regex tester", str(R.string.try_a_regular_expression_and_highlight_m)) {
    var p by rememberSaveable { mutableStateOf("") }
    var f by rememberSaveable { mutableStateOf("") }
    var t by rememberSaveable { mutableStateOf("") }
    Field(p, { p = it }, str(R.string.expression))
    Field(f, { f = it }, str(R.string.flags))
    Field(t, { t = it }, "Text", lines = 5)
    val hl = MaterialTheme.colorScheme.primaryContainer
    if (p.isNotEmpty()) {
        val r = remember(p, f, t) {
            runCatching {
                val o = mutableSetOf<RegexOption>()
                if ('i' in f) o.add(RegexOption.IGNORE_CASE)
                if ('m' in f) o.add(RegexOption.MULTILINE)
                if ('s' in f) o.add(RegexOption.DOT_MATCHES_ALL)
                Regex(p, o).findAll(t).take(2000).toList()
            }
        }
        r.onSuccess { ms ->
            val a = buildAnnotatedString {
                var last = 0
                for (m in ms) {
                    append(t.substring(last, m.range.first))
                    withStyle(SpanStyle(background = hl)) { append(m.value) }
                    last = m.range.last + 1
                }
                append(t.substring(last))
            }
            RichOut(a, str(R.string.matches, ms.size))
            val g = ms.withIndex().filter { it.value.groupValues.size > 1 }.joinToString("\n") { (i, m) ->
                "#${i + 1}: " + m.groups.drop(1).joinToString(", ") { x -> x?.value?.let { v -> Js.quote(v) } ?: "—" }
            }
            if (g.isNotEmpty()) Out(g, str(R.string.groups))
        }
        r.onFailure { Err(it.message ?: str(R.string.invalid_expression)) }
    }
}

@Composable
fun DiffTool() = Page(str(R.string.text_comparison), str(R.string.line_diff_of_two_texts)) {
    var a by rememberSaveable { mutableStateOf("") }
    var b by rememberSaveable { mutableStateOf("") }
    Field(a, { a = it }, str(R.string.original), lines = 5)
    Field(b, { b = it }, str(R.string.new_label), lines = 5)
    val add = Color(0x442E7D32)
    val del = Color(0x44C62828)
    val r = remember(a, b) { runCatching { diffLines(a, b) } }
    r.onSuccess { (lines, ad, de) ->
        Out(if (ad > 0 || de > 0) str(R.string.added_removed, ad, de) else str(R.string.the_texts_are_identical), str(R.string.summary_2))
        val an = buildAnnotatedString {
            lines.forEachIndexed { i, l ->
                if (i > 0) append("\n")
                when (l.kind) {
                    '+' -> withStyle(SpanStyle(background = add)) { append("+ " + l.text) }
                    '-' -> withStyle(SpanStyle(background = del)) { append("- " + l.text) }
                    else -> append("  " + l.text)
                }
            }
        }
        RichOut(an, str(R.string.differences))
    }
    r.onFailure { Err(it.message ?: str(R.string.error)) }
}

@Composable
fun LinesTool() = Page(str(R.string.line_editing), str(R.string.sorting_removing_duplicates_and_other_ed)) {
    var t by rememberSaveable { mutableStateOf("") }
    var op by rememberSaveable { mutableStateOf("") }
    Field(t, { t = it }, str(R.string.input_one_entry_per_line), lines = 8)
    Chips(LINE_OPS.map { it.first }, op) { op = it }
    if (op.isNotEmpty() && t.isNotEmpty()) {
        val a = t.split("\n")
        val r = LINE_OPS.first { it.first == op }.second(a)
        Out(str(R.string.lines, a.size, r.size), "Info")
        Out(r.joinToString("\n"), str(R.string.output))
    }
}

@Composable
fun MdTool() = Page(str(R.string.markdown_preview), str(R.string.basic_markdown_with_a_live_preview_headi)) {
    var t by rememberSaveable { mutableStateOf(str(R.string.example_this_is_bold_text_italic_and_cod)) }
    Field(t, { t = it }, "Markdown", lines = 8)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) { MarkdownView(t) }
    }
}

@Composable
fun SlugTool() = Page(str(R.string.slug_generator), str(R.string.creates_a_url_friendly_string_from_text)) {
    var t by rememberSaveable { mutableStateOf("Čo je nové v Bratislave? Máj 2026") }
    var s by rememberSaveable { mutableStateOf("-") }
    Field(t, { t = it }, "Text", mono = false)
    Chips(listOf("-", "_"), s) { s = it }
    Out(slugOf(t, s), "Slug")
}

@Composable
fun UniTool() = Page(str(R.string.unicode_inspector), str(R.string.code_points_utf_8_bytes_and_utf_16_units)) {
    var t by rememberSaveable { mutableStateOf("Čaj ☕ 😀") }
    Field(t, { t = it }, "Text", mono = false)
    if (t.isNotEmpty()) {
        val (lines, info) = remember(t) { unicodeReport(t) }
        Out(info, str(R.string.summary))
        Out(lines, str(R.string.characters))
    }
}

@Composable
fun TstatTool() = Page(str(R.string.text_statistics), str(R.string.number_of_characters_words_sentences_rea)) {
    var t by rememberSaveable { mutableStateOf("") }
    Field(t, { t = it }, str(R.string.paste_or_type_text), lines = 8, mono = false)
    Out(remember(t) { textStats(t) })
}

@Composable
fun LoremTool() = Page("Lorem ipsum", str(R.string.placeholder_text_generator_by_paragraphs)) {
    var n by rememberSaveable { mutableStateOf("3") }
    var u by rememberSaveable { mutableStateOf(str(R.string.paragraphs)) }
    var c by rememberSaveable { mutableStateOf(true) }
    var seed by remember { mutableIntStateOf(0) }
    Field(n, { n = it }, str(R.string.count))
    Chips(listOf(str(R.string.paragraphs), str(R.string.sentences), str(R.string.words)), u) { u = it }
    Chk(str(R.string.start_with_lorem_ipsum_dolor_sit_amet), c) { c = it }
    Act(str(R.string.generate)) { seed++ }
    Out(remember(n, u, c, seed) { lorem(when (u) { str(R.string.paragraphs) -> "p"; str(R.string.sentences) -> "s"; else -> "w" }, n.trim().toIntOrNull() ?: 1, c) })
}

@Composable
fun CipherTool() = Page(str(R.string.ciphers_and_morse_code), str(R.string.caesar_cipher_rot13_atbash_and_morse_cod_2)) {
    var t by rememberSaveable { mutableStateOf("") }
    var m by rememberSaveable { mutableStateOf("Caesar") }
    var k by rememberSaveable { mutableStateOf("3") }
    Field(t, { t = it }, "Text…", lines = 3, mono = false)
    Chips(CIPHERS, m) { m = it }
    if (m == "Caesar") Field(k, { k = it }, str(R.string.shift))
    if (t.isNotEmpty()) Out(cipher(m, t, k.trim().toIntOrNull() ?: 0))
}

// ───────────────────────── Dizajn a web ─────────────────────────

@Composable
fun CssTool() = Page(str(R.string.css_minifier), str(R.string.minify_and_format_css_spaces_in_strings)) {
    var t by rememberSaveable { mutableStateOf("") }
    var m by rememberSaveable { mutableStateOf(str(R.string.minify)) }
    Chips(listOf(str(R.string.minify), str(R.string.format_2)), m) { m = it }
    Field(t, { t = it }, "a { color: red; }", lines = 8)
    if (t.isNotBlank()) Out(remember(t, m) { if (m == str(R.string.minify)) cssMin(t) else cssFormat(t) })
}

@Composable
fun UrlpTool() = Page(str(R.string.url_parser), str(R.string.splits_an_address_into_parts_and_lists_t)) {
    var t by rememberSaveable { mutableStateOf("") }
    Field(t, { t = it }, "https://example.com:8080/cesta?a=1&b=dva#cast")
    if (t.isNotBlank()) {
        val r = remember(t) {
            runCatching {
                val u = URI(t.trim())
                if (u.scheme == null || (u.host == null && u.authority == null)) throw Exception("x")
                val user = u.userInfo?.substringBefore(':')
                val base = listOf(
                    str(R.string.protocol) to u.scheme + ":", str(R.string.host) to (u.host ?: u.authority), "Port" to (if (u.port >= 0) u.port.toString() else str(R.string.default_label)),
                    str(R.string.path) to u.rawPath.orEmpty().ifEmpty { "/" }, "Hash" to (u.rawFragment?.let { "#$it" } ?: "—"),
                    str(R.string.origin) to u.scheme + "://" + (u.host ?: u.authority) + (if (u.port >= 0) ":${u.port}" else ""), str(R.string.user) to (user ?: "—")
                ).joinToString("\n") { it.first.padEnd(12) + it.second }
                val ps = u.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.map {
                    val i = it.indexOf('=')
                    URLDecoder.decode(if (i < 0) it else it.substring(0, i), "UTF-8") to URLDecoder.decode(if (i < 0) "" else it.substring(i + 1), "UTF-8")
                } ?: emptyList()
                base + "\n\nParametre (${ps.size})\n" + (if (ps.isEmpty()) "  —" else ps.joinToString("\n") { "  ${it.first} = ${it.second}" })
            }
        }
        r.onSuccess { Out(it, str(R.string.valid_url)) }
        r.onFailure { Err(str(R.string.invalid_url_enter_it_with_the_protocol_f)) }
    }
}

@Composable
fun HttpTool() = Page(str(R.string.http_status_codes), str(R.string.overview_of_the_most_common_codes_search)) {
    var q by rememberSaveable { mutableStateOf("") }
    Field(q, { q = it }, "404, redirect, timeout…", mono = false)
    Out(remember(q) { httpCodesReport(q) })
}

@Composable
fun ChmodTool() = Page(str(R.string.chmod_calculator), str(R.string.unix_file_permissions_in_octal_and_symbo)) {
    var oct by rememberSaveable { mutableStateOf("755") }
    val valid = Regex("^[0-7]{3}$").matches(oct)
    val perm = if (valid) oct.toInt(8) else 0
    val who = listOf(str(R.string.owner), str(R.string.group), str(R.string.others))
    val what = listOf(str(R.string.read_r) to 4, str(R.string.write_w) to 2, str(R.string.execute_x) to 1)
    fun toggle(i: Int, bit: Int) { oct = "%03o".format(perm xor (bit shl (6 - 3 * i))) }
    who.forEachIndexed { i, w ->
        Column {
            Lbl(w)
            what.forEach { (n, bit) -> Chk(n, perm and (bit shl (6 - 3 * i)) != 0) { toggle(i, bit) } }
        }
    }
    Field(oct, { oct = it.filter { c -> c.isDigit() }.take(3) }, str(R.string.octal))
    if (valid) {
        val sym = (0..2).joinToString("") { i ->
            val d = (perm shr (6 - 3 * i)) and 7
            (if (d and 4 != 0) "r" else "-") + (if (d and 2 != 0) "w" else "-") + (if (d and 1 != 0) "x" else "-")
        }
        Out(sym, str(R.string.symbolic))
        Out(str(R.string.chmod_file, oct), str(R.string.command))
    } else Err(str(R.string.enter_three_octal_digits_0_7))
}

@Composable
fun CronTool() = Page(str(R.string.cron_expressions), str(R.string.validate_a_5_field_cron_expression_and_s)) {
    var e by rememberSaveable { mutableStateOf("*/15 9-17 * * 1-5") }
    Field(e, { e = it }, str(R.string.minute_hour_day_month_day_of_week))
    val r = remember(e) { runCatching { cronNext(e).joinToString("\n").ifEmpty { str(R.string.it_will_not_run_within_the_next_year) } } }
    Res(r, str(R.string.next_runs_local_time))
}

@Composable
fun TzTool() = Page(str(R.string.time_zones), str(R.string.convert_a_date_and_time_from_one_zone_to)) {
    var d by rememberSaveable { mutableStateOf(java.time.LocalDateTime.now().withNano(0).toString()) }
    var z by rememberSaveable { mutableStateOf(java.time.ZoneId.systemDefault().id) }
    Field(d, { d = it }, str(R.string.date_and_time_2024_01_31t14_30_00))
    Field(z, { z = it }, str(R.string.source_zone_e_g_europe_prague))
    Chips(TZ_LIST, z) { z = it }
    Res(remember(d, z) { runCatching { tzReport(d, z) } })
}

@Composable
fun DdTool() = Page(str(R.string.date_difference), str(R.string.how_much_time_has_passed_between_two_dat)) {
    var a by rememberSaveable { mutableStateOf(java.time.LocalDateTime.now().withNano(0).toString()) }
    var b by rememberSaveable { mutableStateOf(java.time.LocalDateTime.now().withNano(0).plusDays(7).toString()) }
    Field(a, { a = it }, "Od (2024-01-31T14:30:00)")
    Field(b, { b = it }, "Do")
    Res(remember(a, b) { runCatching { dateDiff(a, b) } })
}

@Composable
fun AspectTool() = Page(str(R.string.aspect_ratio), str(R.string.simplified_aspect_ratio_and_size_convers)) {
    var w by rememberSaveable { mutableStateOf("1920") }
    var h by rememberSaveable { mutableStateOf("1080") }
    var nw by rememberSaveable { mutableStateOf("1280") }
    var nh by rememberSaveable { mutableStateOf("720") }
    fun n(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
    fun f(d: Double) = Js.fmt(Math.round(d * 100) / 100.0)
    Rw {
        Box(Modifier.weight(1f)) { Field(w, { w = it; val a = n(w); val b = n(h); val c = n(nw); if (a != null && b != null && a > 0 && b > 0 && c != null && c > 0) nh = f(c * b / a) }, str(R.string.width)) }
        Box(Modifier.weight(1f)) { Field(h, { h = it; val a = n(w); val b = n(h); val c = n(nw); if (a != null && b != null && a > 0 && b > 0 && c != null && c > 0) nh = f(c * b / a) }, str(R.string.height)) }
    }
    val a = n(w); val b = n(h)
    if (a != null && b != null && a > 0 && b > 0) {
        val g = if (a % 1.0 == 0.0 && b % 1.0 == 0.0) java.math.BigInteger.valueOf(a.toLong()).gcd(java.math.BigInteger.valueOf(b.toLong())).toDouble() else 1.0
        Out(str(R.string.ratio_decimal, Js.fmt(a / g), Js.fmt(b / g), Js.fmt(Math.round(a / b * 10000) / 10000.0)))
        Rw {
            Box(Modifier.weight(1f)) { Field(nw, { nw = it; val c = n(it); nh = if (c != null && c > 0) f(c * b / a) else "" }, str(R.string.new_width)) }
            Box(Modifier.weight(1f)) { Field(nh, { nh = it; val c = n(it); nw = if (c != null && c > 0) f(c * a / b) else "" }, str(R.string.new_height)) }
        }
    } else Err(str(R.string.enter_positive_dimensions))
}

// ───────────────────────── Kalkulačky ─────────────────────────

@Composable
fun RandTool() = Page(str(R.string.random_values), str(R.string.random_numbers_dice_and_coin_rolls_or_pi)) {
    var a by rememberSaveable { mutableStateOf("1") }
    var b by rememberSaveable { mutableStateOf("100") }
    var c by rememberSaveable { mutableStateOf("1") }
    var l by rememberSaveable { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    Rw {
        Box(Modifier.weight(1f)) { Field(a, { a = it }, "Od") }
        Box(Modifier.weight(1f)) { Field(b, { b = it }, "Do") }
        Box(Modifier.weight(1f)) { Field(c, { c = it }, str(R.string.count)) }
    }
    Act(str(R.string.random_numbers)) {
        val x = a.trim().toLongOrNull(); val y = b.trim().toLongOrNull(); val n = (c.trim().toIntOrNull() ?: 1).coerceIn(1, 100)
        out = if (x == null || y == null || y < x || y - x > 4_000_000_000L) str(R.string.enter_a_valid_range_from_to)
        else List(n) { x + (rng.nextDouble() * (y - x + 1)).toLong().coerceAtMost(y - x) }.joinToString(", ")
    }
    Rw {
        Btn(str(R.string.die_d6), Modifier.weight(1f)) { out = str(R.string.die) + (1 + rng.nextInt(6)) }
        Btn(str(R.string.coin), Modifier.weight(1f)) { out = if (rng.nextBoolean()) str(R.string.heads) else str(R.string.tails) }
    }
    Field(l, { l = it }, str(R.string.list_to_pick_from_one_entry_per_line), lines = 4, mono = false)
    Act(str(R.string.pick_a_random_one)) {
        val items = l.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        out = if (items.isEmpty()) str(R.string.enter_at_least_one_line) else items[rng.nextInt(items.size)]
    }
    if (out.isNotEmpty()) Out(out)
}
