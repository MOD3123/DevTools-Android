// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sk.devtools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ───────────────────────── WebSocket tester ─────────────────────────

@Composable
fun WsTool() = Page("WebSocket tester", str(R.string.connects_to_ws_or_wss_sends_a_message_an)) {
    var u by rememberSaveable { mutableStateOf("") }
    var m by rememberSaveable { mutableStateOf("") }
    var h by rememberSaveable { mutableStateOf("") }
    var s by rememberSaveable { mutableStateOf("10 s") }
    Field(u, { u = it }, "wss://echo.example.com/socket")
    Field(m, { m = it }, str(R.string.message_to_send_optional), lines = 3)
    Field(h, { h = it }, str(R.string.headers_origin_authorization_name_value), lines = 2)
    Text(str(R.string.how_long_to_listen), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Chips(listOf("5 s", "10 s", "30 s", "60 s"), s) { s = it }
    Run(str(R.string.connect), u.isNotBlank()) {
        val sec = s.substringBefore(' ').toIntOrNull() ?: 10
        withContext(Dispatchers.IO) { wsTest(u, m, parseHeaders(h), sec) }
    }
}

// ───────────────────────── GeoIP + reverzné DNS ─────────────────────────

@Composable
fun GeoTool() = Page(str(R.string.geoip_and_reverse_dns), str(R.string.country_network_asn_organization_and_rev)) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    Field(q, { q = it }, str(R.string.s_8_8_8_8_or_example_com))
    Run(str(R.string.look_up), q.isNotBlank()) { geoLookup(ctx, q) }
}

// ───────────────────────── HTTP/2, HTTP/3 a TLS ─────────────────────────

@Composable
fun ProtoTool() = Page("HTTP/2, HTTP/3 a TLS", str(R.string.which_tls_versions_the_server_accepts_wh)) {
    var h by rememberSaveable { mutableStateOf("") }
    Field(h, { h = it }, str(R.string.example_com_or_example_com_8443))
    Run(str(R.string.check), h.isNotBlank()) { withContext(Dispatchers.IO) { protoCheck(h) } }
}

// ───────────────────────── JWT generátor ─────────────────────────

@Composable
fun JwtGenTool() = Page(str(R.string.jwt_generator), str(R.string.creates_and_signs_a_jwt_with_the_hmac_al)) {
    var alg by rememberSaveable { mutableStateOf("HS256") }
    var hd by rememberSaveable { mutableStateOf("{\"typ\":\"JWT\"}") }
    var pl by rememberSaveable { mutableStateOf("{\n  \"sub\": \"1234567890\",\n  \"name\": \"Ján Novák\"\n}") }
    var sec by rememberSaveable { mutableStateOf("") }
    var iat by rememberSaveable { mutableStateOf(str(R.string.iat_now)) }
    var exp by rememberSaveable { mutableStateOf(str(R.string.no_exp)) }
    Chips(listOf("HS256", "HS384", "HS512"), alg) { alg = it }
    Field(hd, { hd = it }, str(R.string.header_json_alg_is_added_automatically), lines = 2)
    Field(pl, { pl = it }, "Payload (JSON)", lines = 6)
    Field(sec, { sec = it }, str(R.string.secret_key))
    Chips(listOf(str(R.string.iat_now), str(R.string.no_iat)), iat) { iat = it }
    Chips(listOf(str(R.string.no_exp), "exp +1 h", "exp +24 h", str(R.string.exp_7_days), str(R.string.exp_30_days)), exp) { exp = it }
    if (sec.isEmpty()) Err(str(R.string.enter_the_secret_key))
    else {
        val expSec = when (exp) {
            "exp +1 h" -> 3600L
            "exp +24 h" -> 86400L
            str(R.string.exp_7_days) -> 7 * 86400L
            str(R.string.exp_30_days) -> 30 * 86400L
            else -> null
        }
        val r = remember(alg, hd, pl, sec, iat, exp) { runCatching { jwtMake(alg, hd, pl, sec, iat == str(R.string.iat_now), expSec) } }
        r.onSuccess { Out(it, "Token") }
        r.onFailure { Err(it.message ?: str(R.string.could_not_create_the_token)) }
    }
}

// ───────────────────────── Formátovač XML, HTML, SQL ─────────────────────────

@Composable
fun FmtTool() = Page(str(R.string.xml_html_and_sql_formatter), str(R.string.clean_indentation_or_minification_of_cod)) {
    var t by rememberSaveable { mutableStateOf("XML") }
    var mode by rememberSaveable { mutableStateOf(str(R.string.format_2)) }
    var src by rememberSaveable { mutableStateOf("") }
    Chips(listOf("XML", "HTML", "SQL"), t) { t = it }
    Chips(listOf(str(R.string.format_2), str(R.string.minify)), mode) { mode = it }
    Field(src, { src = it }, str(R.string.input), lines = 8)
    if (src.isNotBlank()) {
        val r = remember(t, mode, src) {
            runCatching {
                when (t) {
                    "SQL" -> if (mode == str(R.string.format_2)) formatSql(src) else minifySql(src)
                    else -> if (mode == str(R.string.format_2)) formatMarkup(src, t == "HTML") else minifyMarkup(src)
                }
            }
        }
        r.onSuccess { Out(it, str(R.string.result_characters, it.length)) }
        r.onFailure { Err(it.message ?: str(R.string.formatting_failed)) }
    }
}

// ───────────────────────── QR kód ─────────────────────────
// Vyžaduje závislosť: implementation("com.google.zxing:core:3.5.3")

fun qrBitmap(text: String, ecc: String): Bitmap {
    val level = when (ecc) {
        "L" -> ErrorCorrectionLevel.L
        "Q" -> ErrorCorrectionLevel.Q
        "H" -> ErrorCorrectionLevel.H
        else -> ErrorCorrectionLevel.M
    }
    val hints = mapOf<EncodeHintType, Any>(
        EncodeHintType.ERROR_CORRECTION to level,
        EncodeHintType.MARGIN to 2,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )
    val m = try {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    } catch (e: Exception) {
        throw Exception(str(R.string.the_text_is_too_long_to_encode_into_a_qr))
    }
    val scale = maxOf(1, minOf(12, 1500 / m.width))
    val w = m.width * scale
    val h = m.height * scale
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    val px = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (m.get(x / scale, y / scale)) black else white
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    b.setPixels(px, 0, w, 0, 0, w, h)
    return b
}

fun decodeCode(ctx: Context, uri: Uri): String {
    val cr = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 2000 || bounds.outHeight / sample > 2000) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: throw Exception(str(R.string.the_image_could_not_be_loaded))
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val src = RGBLuminanceSource(w, h, px)
    val hints = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true)
    val reader = MultiFormatReader()
    fun attempt(s: LuminanceSource) = runCatching { reader.decode(BinaryBitmap(HybridBinarizer(s)), hints) }.getOrNull()
    val r = attempt(src) ?: attempt(src.invert()) ?: throw Exception(str(R.string.no_qr_code_or_barcode_was_found_in_the_i))
    return r.text + str(R.string.format, r.barcodeFormat)
}

@Composable
fun QrTool() = Page(str(R.string.qr_code), str(R.string.creates_a_qr_code_from_text_or_reads_a_q)) {
    var mode by rememberSaveable { mutableStateOf(str(R.string.generator)) }
    Chips(listOf(str(R.string.generator), str(R.string.reader)), mode) { mode = it }
    if (mode == str(R.string.generator)) QrGen() else QrRead()
}

@Composable
private fun QrGen() {
    var t by rememberSaveable { mutableStateOf("") }
    var ecc by rememberSaveable { mutableStateOf("M") }
    Field(t, { t = it }, str(R.string.text_or_url), lines = 3, mono = false)
    Text(str(R.string.error_correction_level), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Chips(listOf("L", "M", "Q", "H"), ecc) { ecc = it }
    if (t.isNotEmpty()) {
        val r = remember(t, ecc) { runCatching { qrBitmap(t, ecc) } }
        r.onSuccess { b -> QrImage(b) }
        r.onFailure { Err(it.message ?: str(R.string.the_qr_code_could_not_be_created)) }
    }
}

@Composable
private fun QrImage(b: Bitmap) {
    val ctx = LocalContext.current
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri: Uri? ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = b.asImageBitmap(), contentDescription = str(R.string.qr_code), filterQuality = FilterQuality.None,
            modifier = Modifier.size(280.dp).clip(RoundedCornerShape(20.dp))
        )
    }
    Act(str(R.string.save_as_png)) { saver.launch("qr-kod.png") }
}

@Composable
private fun QrRead() {
    val ctx = LocalContext.current
    var res by remember { mutableStateOf<Result<String>?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) res = runCatching { decodeCode(ctx, uri) }
    }
    Act(str(R.string.choose_an_image_with_a_code)) { pick.launch("image/*") }
    Res(res, str(R.string.code_content))
}

// ───────────────────────── Záloha obľúbených, nedávnych a histórie ─────────────────────────

object Backup {
    fun export(c: Context): String {
        val o = JSONObject()
        o.put("app", "devtools")
        o.put("version", 1)
        o.put("fav", JSONArray(Store.ids(c, "fav")))
        o.put("recent", JSONArray(Store.ids(c, "recent")))
        o.put("autopaste", Store.get(c, "autopaste") != "0")
        o.put("hist_req", JSONArray(Store.get(c, "hist_req") ?: "[]"))
        o.put("hist_redir", JSONArray(Store.get(c, "hist_redir") ?: "[]"))
        return o.toString(2)
    }

    // Prepíše aktuálne dáta dátami zo zálohy. Vráti krátke zhrnutie.
    fun restore(c: Context, txt: String): String {
        val o = JSONObject(txt)
        if (o.optString("app") != "devtools") throw Exception(str(R.string.this_is_not_a_devtools_backup))
        val valid = TOOLS.map { it.id }.toSet()
        fun ids(k: String): List<String> {
            val a = o.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).map { a.optString(it) }.filter { it in valid }.distinct()
        }
        val fav = ids("fav")
        Store.saveIds(c, "fav", fav)
        Store.saveIds(c, "recent", ids("recent").take(8))
        Store.put(c, "autopaste", if (o.optBoolean("autopaste", true)) "1" else "0")
        var hist = 0
        for (k in listOf("hist_req", "hist_redir")) {
            o.optJSONArray(k)?.let { Store.put(c, k, it.toString()); hist += it.length() }
        }
        return str(R.string.favorites_history_entries, fav.size, hist)
    }
}
