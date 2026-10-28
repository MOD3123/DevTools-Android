// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sk.devtools

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ───────────────────────── Úložisko (obľúbené, nedávne, história) ─────────────────────────

object Store {
    private fun p(c: Context) = c.getSharedPreferences("devtools", Context.MODE_PRIVATE)
    fun get(c: Context, k: String): String? = p(c).getString(k, null)
    fun put(c: Context, k: String, v: String) { p(c).edit().putString(k, v).apply() }
    fun ids(c: Context, k: String): List<String> = (get(c, k) ?: "").split(',').filter { it.isNotEmpty() }
    fun saveIds(c: Context, k: String, v: List<String>) = put(c, k, v.joinToString(","))
}

// História posledných požiadaviek. Heslá ani tokeny sa nikdy neukladajú.
class Hist(private val ctx: Context, private val key: String) {
    val items = mutableStateListOf<JSONObject>()

    init {
        runCatching {
            val a = JSONArray(Store.get(ctx, key) ?: "[]")
            for (i in 0 until a.length()) items.add(a.getJSONObject(i))
        }
    }

    fun add(o: JSONObject) {
        val k = o.optString("k")
        items.removeAll { it.optString("k") == k }
        items.add(0, o)
        while (items.size > 15) items.removeAt(items.lastIndex)
        save()
    }

    fun clear() { items.clear(); save() }

    private fun save() { Store.put(ctx, key, JSONArray(items.toList()).toString()) }
}

@Composable
fun rememberHist(key: String): Hist {
    val c = LocalContext.current
    return remember(key) { Hist(c, key) }
}

@Composable
fun HistoryBox(h: Hist, onPick: (JSONObject) -> Unit) {
    if (h.items.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(str(R.string.history, h.items.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(if (open) str(R.string.hide) else str(R.string.show), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                h.items.toList().forEach { o ->
                    ListItem(
                        headlineContent = { Text(o.optString("label"), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPick(o) }
                    )
                }
                TextButton(onClick = { h.clear() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(str(R.string.clear_history)) }
            }
        }
    }
}

// ───────────────────────── Spoločné polia: User-Agent a autentifikácia ─────────────────────────

@Composable
fun UaPicker(sel: String, on: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("User-Agent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Chips(UAS.map { it.first }, sel, on)
    }
}

val AUTH_NONE = str(R.string.no_authentication)

@Composable
fun AuthFields(
    type: String, onType: (String) -> Unit,
    user: String, onUser: (String) -> Unit,
    pass: String, onPass: (String) -> Unit,
    tok: String, onTok: (String) -> Unit
) {
    Chips(listOf(AUTH_NONE, "Basic", "Bearer"), type, onType)
    if (type == "Basic") {
        Field(user, onUser, str(R.string.username), mono = false)
        Field(pass, onPass, str(R.string.password))
    }
    if (type == "Bearer") Field(tok, onTok, "Token")
}

// ───────────────────────── Nové nástroje ─────────────────────────

@Composable
fun SslTool() = Page(str(R.string.ssl_tls_certificate), str(R.string.validity_issuer_alternative_names_and_th)) {
    var h by rememberSaveable { mutableStateOf("") }
    Field(h, { h = it }, str(R.string.example_com_or_example_com_8443))
    Run(str(R.string.check), h.isNotBlank()) { withContext(Dispatchers.IO) { tlsInfo(h) } }
}

@Composable
fun CorsTool() = Page("CORS tester", str(R.string.simulates_a_preflight_and_an_actual_requ)) {
    var u by rememberSaveable { mutableStateOf("") }
    var o by rememberSaveable { mutableStateOf("https://example.com") }
    var m by rememberSaveable { mutableStateOf("GET") }
    var rh by rememberSaveable { mutableStateOf("") }
    var ua by rememberSaveable { mutableStateOf(UAS[0].first) }
    Field(u, { u = it }, "https://api.example.com/data")
    Field(o, { o = it }, str(R.string.origin_the_request_comes_from))
    Chips(listOf("GET", "POST", "PUT", "PATCH", "DELETE"), m) { m = it }
    Field(rh, { rh = it }, str(R.string.custom_headers_e_g_authorization_content), mono = false)
    UaPicker(ua) { ua = it }
    Run(str(R.string.test_cors), u.isNotBlank()) { withContext(Dispatchers.IO) { corsTest(u, o, m, rh, ua) } }
}

@Composable
fun CacheTool() = Page("Cache a CDN", str(R.string.checks_cache_control_etag_and_revalidati)) {
    var u by rememberSaveable { mutableStateOf("") }
    var ua by rememberSaveable { mutableStateOf(UAS[0].first) }
    Field(u, { u = it }, "https://example.com/style.css")
    UaPicker(ua) { ua = it }
    Run(str(R.string.check), u.isNotBlank()) { withContext(Dispatchers.IO) { cacheCheck(u, ua) } }
}

@Composable
fun PingTool() = Page(str(R.string.ping_and_latency), str(R.string.tcp_ping_dns_lookup_time_port_connection)) {
    var h by rememberSaveable { mutableStateOf("") }
    var p by rememberSaveable { mutableStateOf("443") }
    var n by rememberSaveable { mutableStateOf("5") }
    Field(h, { h = it }, str(R.string.example_com_or_ip))
    Field(p, { p = it }, "Port")
    Chips(listOf("5", "10", "20"), n) { n = it }
    Run(str(R.string.start_ping), h.isNotBlank()) {
        withContext(Dispatchers.IO) { tcpPing(h, p.trim().toIntOrNull() ?: throw Exception(str(R.string.invalid_port)), n.toInt()) }
    }
}
