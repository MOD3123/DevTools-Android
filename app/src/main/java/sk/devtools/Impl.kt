// SPDX-License-Identifier: GPL-3.0-or-later
package sk.devtools

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()

private val BODY_METHODS = listOf("POST", "PUT", "PATCH", "DELETE")
private val FOLLOW = str(R.string.follow_redirects)
private val REDIR_CODES = intArrayOf(301, 302, 303, 307, 308)

@Composable
fun ReqTool() = Page(str(R.string.http_requests), str(R.string.sends_an_http_request_with_custom_header)) {
    var u by rememberSaveable { mutableStateOf("") }
    var m by rememberSaveable { mutableStateOf("GET") }
    var h by rememberSaveable { mutableStateOf("") }
    var b by rememberSaveable { mutableStateOf("") }
    var f by rememberSaveable { mutableStateOf(FOLLOW) }
    var ua by rememberSaveable { mutableStateOf(UAS[0].first) }
    var au by rememberSaveable { mutableStateOf(AUTH_NONE) }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var tok by rememberSaveable { mutableStateOf("") }
    val hist = rememberHist("hist_req")

    Field(u, { u = it }, str(R.string.url_or_ip_address))
    Chips(listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"), m) { m = it }
    UaPicker(ua) { ua = it }
    Field(h, { h = it }, str(R.string.headers_one_per_line_name_value), lines = 3)
    if (m in BODY_METHODS) Field(b, { b = it }, str(R.string.request_body_body), lines = 4)
    AuthFields(au, { au = it }, user, { user = it }, pass, { pass = it }, tok, { tok = it })
    Chips(listOf(FOLLOW, str(R.string.do_not_follow)), f) { f = it }

    val follow = f == FOLLOW
    val basic = if (au == "Basic") user to pass else null
    val bearer = if (au == "Bearer") tok else null
    val body = if (m in BODY_METHODS) b.ifEmpty { null } else null
    val hs = withUa(parseHeaders(h), ua) +
        (if (!bearer.isNullOrBlank()) listOf("Authorization" to "Bearer " + bearer.trim()) else emptyList())

    Run(str(R.string.send), u.isNotBlank()) {
        hist.add(
            JSONObject().put("k", "$m ${u.trim()}").put("label", "$m ${u.trim()}")
                .put("u", u.trim()).put("m", m).put("h", h).put("b", b).put("f", if (f == FOLLOW) "1" else "0")
                .put("ua", ua).put("au", au).put("user", user)
        )
        withContext(Dispatchers.IO) { httpFetch(u, m, hs, body, basic, follow).report() }
    }
    if (u.isNotBlank()) Out(toCurl(u, m, withUa(parseHeaders(h), ua), body, basic, bearer, follow), str(R.string.export_as_curl))
    HistoryBox(hist) { o ->
        u = o.optString("u"); m = o.optString("m", "GET"); h = o.optString("h"); b = o.optString("b")
        f = o.optString("f").let { if (it == "0" || it == "Nenasledovať" || it == "Do not follow") str(R.string.do_not_follow) else FOLLOW }; ua = o.optString("ua", UAS[0].first)
        au = o.optString("au").let { if (it == "Basic" || it == "Bearer") it else AUTH_NONE }; user = o.optString("user"); pass = ""; tok = ""
    }
}

@Composable
fun RedirTool() = Page(str(R.string.redirect_checker), str(R.string.follows_the_redirect_chain_step_by_step)) {
    var u by rememberSaveable { mutableStateOf("") }
    var m by rememberSaveable { mutableStateOf("GET") }
    var h by rememberSaveable { mutableStateOf("") }
    var b by rememberSaveable { mutableStateOf("") }
    var ua by rememberSaveable { mutableStateOf(UAS[0].first) }
    var au by rememberSaveable { mutableStateOf(AUTH_NONE) }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var tok by rememberSaveable { mutableStateOf("") }
    val hist = rememberHist("hist_redir")

    Field(u, { u = it }, str(R.string.url_or_ip_address_several_addresses_one), lines = 2)
    Chips(listOf("GET", "HEAD", "POST"), m) { m = it }
    UaPicker(ua) { ua = it }
    Field(h, { h = it }, str(R.string.headers_one_per_line_name_value), lines = 2)
    if (m == "POST") Field(b, { b = it }, str(R.string.request_body_body), lines = 4)
    AuthFields(au, { au = it }, user, { user = it }, pass, { pass = it }, tok, { tok = it })

    Run(str(R.string.check), u.isNotBlank()) {
        val urls = u.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(10)
        hist.add(
            JSONObject().put("k", "$m ${urls.joinToString(" ")}").put("label", "$m ${urls.joinToString("  ·  ")}")
                .put("u", u.trim()).put("m", m).put("h", h).put("b", b)
                .put("ua", ua).put("au", au).put("user", user)
        )
        val hs = withUa(parseHeaders(h), ua)
        val results = coroutineScope {
            urls.map { url ->
                async {
                    url to traceRequest(
                        url, m, headers = hs, body = if (m == "POST") b.ifEmpty { null } else null,
                        basic = if (au == "Basic") user to pass else null,
                        bearer = if (au == "Bearer") tok else null
                    )
                }
            }.awaitAll()
        }
        fun count(hops: List<Hop>) = hops.count { it.status in REDIR_CODES }
        fun last(hops: List<Hop>) = hops.lastOrNull { it.error.isNullOrBlank() && it.status > 0 }
        fun head(hops: List<Hop>) = buildString {
            append(str(R.string.redirects_2, count(hops)))
            last(hops)?.let { append(str(R.string.final_status_final_address, it.status, it.url)) }
        }
        if (results.size == 1) head(results[0].second) + "\n\n" + results[0].second.render()
        else {
            val sum = results.joinToString("\n") { (url, hops) ->
                val l = last(hops)
                "• $url\n  " + (if (l != null) str(R.string.redirects_target, l.status, count(hops), l.url) else str(R.string.error_5) + (hops.lastOrNull()?.error ?: str(R.string.no_response)))
            }
            str(R.string.addresses_checked, results.size, sum) +
                results.joinToString("\n\n") { (url, hops) -> "━━━ $url ━━━\n" + hops.render() }
        }
    }
    HistoryBox(hist) { o ->
        u = o.optString("u"); m = o.optString("m", "GET"); h = o.optString("h"); b = o.optString("b")
        ua = o.optString("ua", UAS[0].first); au = o.optString("au").let { if (it == "Basic" || it == "Bearer") it else AUTH_NONE }; user = o.optString("user"); pass = ""; tok = ""
    }
}

@Composable
fun DnsTool() = Page("DNS lookup", str(R.string.records_a_aaaa_cname_mx_ns_txt_soa_caa_s)) {
    var nm by rememberSaveable { mutableStateOf("") }
    var t by rememberSaveable { mutableStateOf("ALL") }
    Field(nm, { nm = it }, str(R.string.example_com_or_0_0_0_0))
    Chips(listOf("ALL", "A", "AAAA", "CNAME", "MX", "NS", "TXT", "SOA", "CAA", "SRV", "PTR"), t) { t = it }
    Run(str(R.string.look_up), nm.isNotBlank()) {
        dnsLookup(nm, t)
    }
}

@Composable
fun TsTool() = Page("Unix timestamp", str(R.string.convert_between_a_timestamp_and_a_date)) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; delay(1000) } }
    Out(now.toString(), str(R.string.current_timestamp))
    var t by rememberSaveable { mutableStateOf("") }
    Field(t, { t = it }, str(R.string.timestamp_seconds_or_milliseconds))
    t.trim().toLongOrNull()?.let { n ->
        val i = Instant.ofEpochMilli(if (n > 99_999_999_999L) n else n * 1000)
        Out(str(R.string.utc_local, i, i.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.RFC_1123_DATE_TIME)))
    }
    var d by rememberSaveable { mutableStateOf("2024-01-01T00:00") }
    Field(d, { d = it }, str(R.string.date_and_time_iso_local))
    runCatching { LocalDateTime.parse(d.trim()).atZone(ZoneId.systemDefault()).toEpochSecond() }.getOrNull()
        ?.let { Out(it.toString(), "Timestamp") }
}

@Composable
fun B64Tool() = Page("Base64", str(R.string.encode_and_decode_base64)) {
    var t by rememberSaveable { mutableStateOf("") }
    Field(t, { t = it }, "Text", lines = 3)
    if (t.isNotEmpty()) {
        Out(Base64.getEncoder().encodeToString(t.toByteArray()), str(R.string.encoded))
        Out(runCatching { String(Base64.getDecoder().decode(t.trim())) }.getOrDefault(str(R.string.invalid_base64)), str(R.string.decoded))
    }
}

@Composable
fun UrlTool() = Page("URL encode / decode", str(R.string.encode_and_decode_urls)) {
    var t by rememberSaveable { mutableStateOf("") }
    Field(t, { t = it }, str(R.string.text_or_url), lines = 3)
    if (t.isNotEmpty()) {
        Out(URLEncoder.encode(t, "UTF-8").replace("+", "%20"), str(R.string.encoded))
        Out(runCatching { URLDecoder.decode(t, "UTF-8") }.getOrDefault(str(R.string.invalid_encoding)), str(R.string.decoded))
    }
}

@Composable
fun BaseTool() = Page(str(R.string.number_bases), str(R.string.convert_between_bin_oct_dec_and_hex)) {
    var t by rememberSaveable { mutableStateOf("") }
    var b by rememberSaveable { mutableIntStateOf(10) }
    Field(t, { t = it }, str(R.string.number))
    Chips(listOf("2", "8", "10", "16"), b.toString()) { b = it.toInt() }
    val n = runCatching { BigInteger(t.trim(), b) }.getOrNull()
    if (n != null) Out("BIN  ${n.toString(2)}\nOCT  ${n.toString(8)}\nDEC  ${n.toString(10)}\nHEX  ${n.toString(16).uppercase()}")
    else if (t.isNotBlank()) Err(str(R.string.invalid_number_for_the_selected_base))
}

@Composable
fun JsonTool() = Page("JSON beautifier", str(R.string.format_and_validate_json)) {
    var t by rememberSaveable { mutableStateOf("") }
    Field(t, { t = it }, "JSON", lines = 6)
    val s = t.trim()
    if (s.isNotEmpty()) {
        val r = runCatching {
            when {
                s.startsWith("{") -> JSONObject(s).toString(2)
                s.startsWith("[") -> JSONArray(s).toString(2)
                else -> error(str(R.string.json_must_be_an_object_or_an_array))
            }
        }
        r.onSuccess { Out(it, str(R.string.formatted_json)) }
        r.onFailure { Err(str(R.string.error_3, it.message)) }
    }
}

private fun digest(alg: String, s: String) =
    MessageDigest.getInstance(alg).digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

@Composable
fun HashTool() = Page("Hash", str(R.string.md5_sha_1_sha_256_and_sha_512_of_a_text)) {
    var t by rememberSaveable { mutableStateOf("") }
    Field(t, { t = it }, "Text", lines = 3)
    if (t.isNotEmpty()) listOf("MD5", "SHA-1", "SHA-256", "SHA-512").forEach { Out(digest(it, t), it) }
}

@Composable
fun UuidTool() = Page(str(R.string.uuid_generator), str(R.string.random_uuid_v4_created_with_a_cryptograp)) {
    var n by rememberSaveable { mutableStateOf("5") }
    var seed by remember { mutableIntStateOf(0) }
    Field(n, { n = it }, str(R.string.count))
    Act(str(R.string.generate)) { seed++ }
    val c = (n.toIntOrNull() ?: 1).coerceIn(1, 100)
    Out(remember(seed, c) { List(c) { UUID.randomUUID().toString() }.joinToString("\n") })
}

@Composable
fun PwTool() = Page(str(R.string.password_generator), str(R.string.random_password_from_a_cryptographic_gen)) {
    var len by remember { mutableFloatStateOf(20f) }
    var up by remember { mutableStateOf(true) }
    var lo by remember { mutableStateOf(true) }
    var di by remember { mutableStateOf(true) }
    var sy by remember { mutableStateOf(true) }
    var seed by remember { mutableIntStateOf(0) }
    Text(str(R.string.length_2, len.toInt()))
    Slider(len, { len = it }, valueRange = 8f..64f)
    Chk(str(R.string.uppercase_letters), up) { up = it }
    Chk(str(R.string.lowercase_letters), lo) { lo = it }
    Chk(str(R.string.numbers), di) { di = it }
    Chk(str(R.string.symbols), sy) { sy = it }
    Act(str(R.string.generate)) { seed++ }
    val pool = buildString {
        if (up) append("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        if (lo) append("abcdefghijklmnopqrstuvwxyz")
        if (di) append("0123456789")
        if (sy) append("!@#\$%^&*()-_=+[]{};:,.?")
    }
    if (pool.isNotEmpty()) {
        val pw = remember(seed, len.toInt(), pool) {
            val r = SecureRandom()
            List(len.toInt()) { pool[r.nextInt(pool.length)] }.joinToString("")
        }
        Out(pw, str(R.string.password))
    }
}

@Composable
fun CaseTool() = Page(str(R.string.text_and_letter_case), str(R.string.convert_to_upper_case_lower_case_camelca)) {
    var t by rememberSaveable { mutableStateOf("") }
    Field(t, { t = it }, "Text", lines = 3, mono = false)
    if (t.isNotBlank()) {
        val w = Regex("[^\\p{L}\\p{N}]+|(?<=[a-z])(?=[A-Z])").split(t.trim()).filter { it.isNotEmpty() }.map { it.lowercase() }
        val cap = { x: String -> x.replaceFirstChar { it.uppercase() } }
        Out(t.uppercase(), str(R.string.uppercase))
        Out(t.lowercase(), str(R.string.lowercase))
        Out(t.split(" ").joinToString(" ") { cap(it.lowercase()) }, str(R.string.every_word_capitalized))
        Out(w.mapIndexed { i, x -> if (i == 0) x else cap(x) }.joinToString(""), "camelCase")
        Out(w.joinToString("") { cap(it) }, "PascalCase")
        Out(w.joinToString("_"), "snake_case")
        Out(w.joinToString("-"), "kebab-case")
    }
}

@Composable
fun PctTool() = Page(str(R.string.percentages), str(R.string.basic_calculations_with_percentages)) {
    var a by rememberSaveable { mutableStateOf("") }
    var b by rememberSaveable { mutableStateOf("") }
    Field(a, { a = it }, "A")
    Field(b, { b = it }, "B")
    val x = num(a); val y = num(b)
    if (x != null && y != null) {
        val l = mutableListOf("${a.trim()} % z ${b.trim()} = %.4g".format(x * y / 100))
        if (y != 0.0) l += "${a.trim()} je %.4g %% z ${b.trim()}".format(x / y * 100)
        if (x != 0.0) l += "Zmena z ${a.trim()} na ${b.trim()} = %.4g %%".format((y - x) / x * 100)
        Out(l.joinToString("\n"))
    }
}

@Composable
fun VatTool() = Page(str(R.string.vat_calculator), str(R.string.convert_between_prices_without_and_with)) {
    var a by rememberSaveable { mutableStateOf("100") }
    var r by rememberSaveable { mutableStateOf("23") }
    var gross by rememberSaveable { mutableStateOf(false) }
    Field(a, { a = it }, str(R.string.amount))
    Field(r, { r = it }, "Sadzba (%)")
    Chips(listOf("23", "19", "5"), r) { r = it }
    Chips(listOf(str(R.string.without_vat), str(R.string.with_vat)), if (gross) str(R.string.with_vat) else str(R.string.without_vat)) { gross = it == str(R.string.with_vat) }
    val x = num(a); val p = num(r)
    if (x != null && p != null) {
        val net = if (gross) x / (1 + p / 100) else x
        val g = if (gross) x else x * (1 + p / 100)
        Out(str(R.string.without_vat_2f_vat_2f_with_vat_2f).format(net, g - net, g))
    }
}
