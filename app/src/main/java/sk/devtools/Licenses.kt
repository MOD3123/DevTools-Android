// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sk.devtools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

private val APP_LICENSE: String? = "GPL-3.0-or-later"
private val APP_COPYRIGHT: String? = "MOD3123"
private val APP_SOURCE_URL: String? = "https://github.com/MOD3123/DevTools-Android/tree/main"

private class Lib(val name: String, val by: String, val license: String, val url: String, val note: String)

private val LIBS = listOf(
    Lib(
        "Jetpack Compose a Material 3", "Google / Android Open Source Project", "Apache-2.0",
        "https://developer.android.com/jetpack/compose", str(R.string.the_app_s_user_interface_including_mater)
    ),
    Lib(
        "AndroidX (Activity, Core)", "Google / Android Open Source Project", "Apache-2.0",
        "https://developer.android.com/jetpack/androidx", str(R.string.core_android_components_file_picking_and)
    ),
    Lib(
        "Kotlin a kotlinx.coroutines", str(R.string.jetbrains_s_r_o_and_contributors), "Apache-2.0",
        "https://github.com/Kotlin/kotlinx.coroutines", str(R.string.programming_language_and_asynchronous_ha)
    ),
    Lib(
        "ZXing Core", "ZXing authors", "Apache-2.0",
        "https://github.com/zxing/zxing", str(R.string.creating_and_reading_qr_codes_and_barcod)
    ),
    Lib(
        "Chrome DevTools Frontend", "The Chromium Authors", "BSD-3-Clause",
        "https://github.com/ChromeDevTools/devtools-frontend", str(R.string.panel_interface_for_the_browser_with_dev)
    ),
    Lib(
        "IP Geolocation by DB-IP", "DB-IP.com", "CC-BY-4.0",
        "https://db-ip.com", str(R.string.offline_ip_country_and_ip_asn_databases)
    )
)

private class Svc(val name: String, val what: String)

private val SERVICES = listOf(
    Svc(str(R.string.dns_servers_configured_in_the_system), str(R.string.the_dns_tools_and_the_email_record_check)),
    Svc("duckduckgo.com", str(R.string.default_search_engine_of_the_built_in_br)),
    Svc(str(R.string.servers_you_enter_yourself), str(R.string.http_ssl_ports_websocket_and_similar_too))
)

@Composable
fun LicTool() = Page(
    str(R.string.open_source_licenses),
    str(R.string.devtools_is_built_on_open_source_librari)
) {
    val uri = LocalUriHandler.current
    fun open(u: String) { runCatching { uri.openUri(u) } }

    if (APP_LICENSE != null) {
        SectionTitle(str(R.string.app_license))
        LCard {
            Text("DevTools", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text(str(R.string.license, APP_LICENSE), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            APP_COPYRIGHT?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            APP_SOURCE_URL?.let { u -> TextButton(onClick = { open(u) }, contentPadding = PaddingValues(0.dp)) { Text(str(R.string.source_code, u)) } }
        }
    }

    SectionTitle(str(R.string.libraries_used))
    LIBS.forEach { l ->
        LCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(
                    l.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text(l.license, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(l.by, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(l.note, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { open(l.url) }, contentPadding = PaddingValues(0.dp)) { Text(l.url.removePrefix("https://")) }
        }
    }

    SectionTitle(str(R.string.external_services))
    Text(
        str(R.string.most_tools_run_locally_these_services_ar),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SERVICES.forEach { s ->
        LCard {
            Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text(s.what, style = MaterialTheme.typography.bodyMedium)
        }
    }

    SectionTitle(str(R.string.full_license_texts))
    LicenseText(str(R.string.gnu_gpl_v3_app_license), "licenses/gpl-3.0.txt")
    LicenseText("Apache License 2.0", "licenses/apache-2.0.txt")
    LicenseText("BSD 3-Clause (The Chromium Authors)", "licenses/bsd-3-clause-chromium.txt")
    LicenseText(str(R.string.db_ip_cc_by_4_0_terms_of_use), "geoip/DBIP-LICENSE.txt")
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
}

@Composable
private fun LCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun LicenseText(title: String, path: String) {
    val ctx = LocalContext.current
    var open by rememberSaveable(path) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().clickable { open = !open }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (open) str(R.string.hide) else str(R.string.show), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (open) {
        val text = remember(path) {
            runCatching { ctx.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault(str(R.string.could_not_load_the_license_text))
        }
        Out(text, title)
    }
}
