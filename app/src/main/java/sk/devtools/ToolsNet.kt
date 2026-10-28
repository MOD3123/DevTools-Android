// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sk.devtools

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private fun cleanHost(s: String) = s.trim().lowercase().removePrefix("http://").removePrefix("https://").substringBefore('/').substringAfterLast('@').substringBefore(':')

@Composable
fun CurlTool() = Page(str(R.string.curl_command), str(R.string.paste_a_curl_command_and_run_it_or_conve)) {
    var t by rememberSaveable { mutableStateOf("") }
    var m by rememberSaveable { mutableStateOf("fetch (JS)") }
    Field(t, { t = it }, "curl -X POST 'https://api.example.com/items' -H 'Content-Type: application/json' -d '{\"a\":1}'", lines = 5)
    Run(str(R.string.start), t.isNotBlank()) {
        withContext(Dispatchers.IO) {
            val o = curlParse(t)
            if (o.url.isEmpty()) throw Exception(str(R.string.the_url_is_missing_in_the_command))
            val j = o.user.indexOf(':')
            val basic = if (o.user.isEmpty()) null else (if (j < 0) o.user else o.user.substring(0, j)) to (if (j < 0) "" else o.user.substring(j + 1))
            httpFetch(o.url, curlMethod(o), curlHeaders(o), o.body.ifEmpty { null }, basic, follow = o.follow).report()
        }
    }
    Chips(listOf("fetch (JS)", "Python requests"), m) { m = it }
    if (t.isNotBlank()) {
        val r = remember(t, m) {
            runCatching {
                val o = curlParse(t)
                if (o.url.isEmpty()) throw Exception(str(R.string.the_url_is_missing_in_the_command))
                curlConvert(o, m.startsWith("fetch"))
            }
        }
        Res(r, str(R.string.conversion))
    }
}

@Composable
fun MailTool() = Page(str(R.string.email_check), str(R.string.checks_a_domain_s_mx_spf_dmarc_dkim_and)) {
    var d by rememberSaveable { mutableStateOf("") }
    var sel by rememberSaveable { mutableStateOf("") }
    Field(d, { d = it }, "example.com")
    Field(sel, { sel = it }, str(R.string.dkim_selectors_optional_default_google_s), mono = false)
    Run(str(R.string.check), d.isNotBlank()) {
        val dom = cleanHost(d)
        val mx = dnsData(dom, "MX")
        val txt = dnsData(dom, "TXT")
        val dmarc = runCatching { dnsData("_dmarc.$dom", "TXT") }.getOrDefault(emptyList())
        val mts = runCatching { dnsData("_mta-sts.$dom", "TXT") }.getOrDefault(emptyList())
        val sels = sel.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty {
            listOf("default", "google", "selector1", "selector2", "k1", "k2", "s1", "s2", "mail", "dkim", "smtp")
        }
        val dkim = coroutineScope {
            sels.map { s -> async { s to runCatching { dnsData("$s._domainkey.$dom", "TXT") }.getOrDefault(emptyList()) } }.awaitAll()
        }.filter { (_, v) -> v.any { it.contains("v=DKIM1", true) || it.contains("p=") } }.toMap()
        mailReport(dom, mx, txt, dmarc, dkim, mts)
    }
}

@Composable
fun PortTool() = Page(str(R.string.port_check), str(R.string.finds_out_whether_a_server_s_tcp_ports_a)) {
    var h by rememberSaveable { mutableStateOf("") }
    var p by rememberSaveable { mutableStateOf("22,80,443") }
    Field(h, { h = it }, str(R.string.example_com_or_ip))
    Field(p, { p = it }, str(R.string.ports_comma_separated))
    Run(str(R.string.check), h.isNotBlank()) { withContext(Dispatchers.IO) { portScan(h, p) } }
}

@Composable
fun SechdrTool() = Page(str(R.string.security_headers), "Audit HSTS, CSP, X-Content-Type-Options, X-Frame-Options, Referrer-Policy a Permissions-Policy.") {
    var u by rememberSaveable { mutableStateOf("") }
    Field(u, { u = it }, "https://example.com")
    Run(str(R.string.check), u.isNotBlank()) { withContext(Dispatchers.IO) { securityHeaders(u) } }
}

@Composable
fun PganTool() = Page(str(R.string.page_analysis), str(R.string.seo_check_title_description_canonical_ro)) {
    var u by rememberSaveable { mutableStateOf("") }
    Field(u, { u = it }, "https://example.com")
    Run(str(R.string.analyze), u.isNotBlank()) { withContext(Dispatchers.IO) { pageAnalysis(u) } }
}

@Composable
fun LinkchkTool() = Page(str(R.string.link_checker), str(R.string.finds_the_links_on_a_page_and_tests_thei_2)) {
    var u by rememberSaveable { mutableStateOf("") }
    Field(u, { u = it }, "https://example.com")
    Run(str(R.string.check), u.isNotBlank()) { withContext(Dispatchers.IO) { linkCheck(u) } }
}

@Composable
fun JwksTool() = Page(str(R.string.jwt_signature_verification), str(R.string.verifies_the_token_s_signature_with_jwks)) {
    var t by rememberSaveable { mutableStateOf("") }
    var u by rememberSaveable { mutableStateOf("") }
    Field(t, { t = it }, "Token", lines = 4)
    Field(u, { u = it }, str(R.string.jwks_url_optional))
    Run(str(R.string.verify_signature), t.isNotBlank()) { withContext(Dispatchers.IO) { jwksVerify(t, u).first } }
}
