// SPDX-License-Identifier: GPL-3.0-or-later
package sk.devtools

// Natívne sieťové nástroje (bez Cloudflare Workera). Všetky funkcie sú blokujúce – volaj ich z Dispatchers.IO.

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class Resp(
    val url: String, val status: Int, val statusText: String, val headers: List<Pair<String, String>>,
    val body: ByteArray?, val truncated: Boolean, val contentType: String, val totalMs: Long,
    val hops: List<Hop>, val error: String?
) {
    val text: String
        get() {
            val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
            val charset = runCatching { charset(cs ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
            return String(body ?: ByteArray(0), charset)
        }
    val isBinary: Boolean
        get() {
            val t = contentType.lowercase()
            return t.startsWith("image/") || t.startsWith("audio/") || t.startsWith("video/") || t.startsWith("font/") ||
                t.contains("octet-stream") || t.contains("pdf") || t.contains("zip") || t.contains("gzip")
        }
    fun header(name: String): String? = headers.filter { it.first.equals(name, true) }.joinToString(", ") { it.second }.ifEmpty { null }
}

private val REDIRECTS = setOf(301, 302, 303, 307, 308)

fun httpFetch(
    url0: String, method: String = "GET", headers: List<Pair<String, String>> = emptyList(), body: String? = null,
    basic: Pair<String, String>? = null, follow: Boolean = true, readBody: Boolean = true,
    maxBody: Int = 1_500_000, maxHops: Int = 10, timeoutMs: Int = 15000
): Resp {
    var url = normalizeUrl(url0)
    var m = method.uppercase()
    var b = body
    val hops = mutableListOf<Hop>()
    val t00 = System.nanoTime()
    var i = 1
    while (true) {
        var c: HttpURLConnection? = null
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = m
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "DevTools/1.0")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (basic != null) c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("${basic.first}:${basic.second}".toByteArray()))
            if (!b.isNullOrEmpty() && m != "GET" && m != "HEAD") {
                c.doOutput = true
                c.outputStream.use { it.write(b!!.toByteArray()) }
            }
            val t0 = System.nanoTime()
            val code = c.responseCode
            val ms = (System.nanoTime() - t0) / 1_000_000
            val loc = c.getHeaderField("Location")
            hops += Hop(i, url, code, c.responseMessage ?: "", ms, loc, null)
            val hs = c.headerFields.entries.filter { it.key != null }.flatMap { e -> e.value.map { e.key to it } }
            if (follow && code in REDIRECTS && !loc.isNullOrBlank()) {
                if (i > maxHops) {
                    hops += Hop(i + 1, url, 0, "", 0, null, str(R.string.too_many_redirects_limit, maxHops))
                    return Resp(url, code, c.responseMessage ?: "", hs, null, false, "", (System.nanoTime() - t00) / 1_000_000, hops, str(R.string.too_many_redirects_limit, maxHops))
                }
                url = URL(URL(url), loc).toString()
                if (code != 307 && code != 308 && m != "HEAD") { m = "GET"; b = null }
                i++
                continue
            }
            var data: ByteArray? = null
            var trunc = false
            if (readBody && m != "HEAD") {
                val st = if (code >= 400) c.errorStream else c.inputStream
                if (st != null) {
                    val o = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    st.use { s ->
                        while (true) {
                            val n = s.read(buf)
                            if (n < 0) break
                            if (o.size() + n > maxBody) { o.write(buf, 0, maxBody - o.size()); trunc = true; break }
                            o.write(buf, 0, n)
                        }
                    }
                    data = o.toByteArray()
                }
            }
            return Resp(url, code, c.responseMessage ?: "", hs, data, trunc, c.contentType ?: "", (System.nanoTime() - t00) / 1_000_000, hops, null)
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            hops += Hop(i, url, 0, "", 0, null, msg)
            return Resp(url, 0, "", emptyList(), null, false, "", (System.nanoTime() - t00) / 1_000_000, hops, msg)
        } finally {
            c?.disconnect()
        }
    }
}

fun Resp.report(): String {
    val sb = StringBuilder()
    if (error != null) {
        if (hops.size > 1) sb.append(hops.render()).append("\n\n")
        return sb.append(str(R.string.error_2)).append(error).toString()
    }
    val kb = body?.let { if (it.size > 1024) "%.1f kB".format(Locale.ROOT, it.size / 1024.0) else "${it.size} B" }
    sb.append("HTTP $status $statusText  ·  $totalMs ms").append(if (kb != null) "  ·  $kb" else "")
    if (hops.size > 1) sb.append(str(R.string.redirects, hops.size - 1))
    if (hops.size > 1) sb.append("\n\n").append(hops.render())
    sb.append(str(R.string.response_headers)).append(headers.joinToString("\n") { it.first + ": " + it.second })
    if (body != null) {
        sb.append(str(R.string.response_body))
        if (isBinary) sb.append(str(R.string.binary_content_b, contentType.ifEmpty { "?" }, body.size))
        else {
            var t = text
            if (contentType.contains("json", true)) runCatching { t = Js.stringify(Js.parse(t), 2) }
            sb.append(t)
        }
        if (truncated) sb.append(str(R.string.truncated))
    }
    return sb.toString()
}

// ───────────────────────── Bezpečnostné hlavičky ─────────────────────────

private class Lines {
    val l = mutableListOf<String>()
    fun ok(t: String) { l.add("✓ $t") }
    fun wa(t: String) { l.add("! $t") }
    fun bad(t: String) { l.add("✗ $t") }
}

private val SEC = listOf(
    Triple("strict-transport-security", "HSTS", "Pridaj Strict-Transport-Security: max-age=31536000; includeSubDomains."),
    Triple("content-security-policy", "Content-Security-Policy", str(R.string.set_a_csp_against_xss)),
    Triple("x-content-type-options", "X-Content-Type-Options", str(R.string.add_the_value_nosniff)),
    Triple("x-frame-options", "X-Frame-Options", str(R.string.add_deny_or_sameorigin_or_csp_frame_ance)),
    Triple("referrer-policy", "Referrer-Policy", str(R.string.add_e_g_strict_origin_when_cross_origin)),
    Triple("permissions-policy", "Permissions-Policy", str(R.string.restrict_browser_features_camera_locatio))
)

fun securityHeaders(url: String): String {
    val r = httpFetch(url, readBody = false)
    if (r.error != null) throw Exception(r.error)
    val h = r.headers.groupBy({ it.first.lowercase() }, { it.second }).mapValues { it.value.joinToString(", ") }
    val L = Lines()
    var n = 0; var tot = 0
    for ((k, t, adv) in SEC) {
        if (k == "strict-transport-security" && !r.url.startsWith("https:")) { L.wa(str(R.string.hsts_the_page_is_not_served_over_https)); continue }
        tot++
        var has = h.containsKey(k)
        if (k == "x-frame-options" && !has && Regex("frame-ancestors", RegexOption.IGNORE_CASE).containsMatchIn(h["content-security-policy"] ?: "")) has = true
        if (has) { n++; L.ok(t + ": " + (h[k] ?: "(cez CSP frame-ancestors)").take(100)) } else L.bad(str(R.string.missing_3, t, adv))
    }
    listOf("server", "x-powered-by").forEach { k -> h[k]?.let { if (it.any(Char::isDigit)) L.wa(str(R.string.the_header_reveals_the_version, k, it)) } }
    return str(R.string.http_score_of, r.url, r.status, n, tot) + L.l.joinToString("\n")
}

// ───────────────────────── Analýza stránky ─────────────────────────

private val ATTR = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
private fun attrs(tag: String): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    ATTR.findAll(tag).forEach { a -> m[a.groupValues[1].lowercase()] = htmlDecode(a.groups[2]?.value ?: a.groups[3]?.value ?: a.groups[4]?.value ?: "") }
    // atribúty bez hodnoty (napr. alt) zachytíme len ako prítomnosť
    Regex("""\s([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?=\s|/?>|$)""").findAll(ATTR.replace(tag, " ")).forEach { a -> m.putIfAbsent(a.groupValues[1].lowercase(), "") }
    return m
}

fun pageAnalysis(url: String): String {
    val r = httpFetch(url)
    if (r.error != null) throw Exception(r.error)
    if (r.body == null || r.isBinary) throw Exception(str(R.string.the_response_is_not_html))
    val html = r.text
    val L = Lines()
    val metas = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { attrs(it.value) }.toList()
    fun meta(n: String) = metas.firstOrNull { it["name"]?.equals(n, true) == true }?.get("content")?.trim()?.ifEmpty { null }
    fun prop(n: String) = metas.firstOrNull { it["property"] == n }?.get("content")?.trim()?.ifEmpty { null }
    val links = Regex("<link\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { attrs(it.value) }.toList()

    val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        ?.let { htmlDecode(it).replace(Regex("\\s+"), " ").trim() } ?: ""
    if (title.isEmpty()) L.bad(str(R.string.missing_title)) else if (title.length > 60) L.wa(str(R.string.title_has_characters_recommended_up_to_6, title.length, title)) else L.ok("Title (${title.length}): $title")
    val ds = meta("description")
    if (ds == null) L.bad(str(R.string.missing_meta_description)) else if (ds.length > 160 || ds.length < 50) L.wa(str(R.string.description_has_characters_recommended_5, ds.length)) else L.ok("Description (${ds.length} znakov).")
    val cn = links.firstOrNull { (it["rel"] ?: "").split(Regex("\\s+")).any { x -> x.equals("canonical", true) } }?.get("href")
    if (cn != null) L.ok("Canonical: $cn") else L.wa(str(R.string.missing_canonical))
    val rb = meta("robots")
    if (rb != null && rb.contains("noindex", true)) L.bad(str(R.string.meta_robots_contains_noindex, rb)) else L.ok("Meta robots: " + (rb ?: str(R.string.not_set_indexable)))
    val lang = Regex("<html\\s[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let { attrs(it.value)["lang"] }
    if (!lang.isNullOrEmpty()) L.ok(str(R.string.language, lang)) else L.wa(str(R.string.missing_lang_attribute))
    if (meta("viewport") != null) L.ok(str(R.string.viewport_is_set)) else L.wa(str(R.string.missing_meta_viewport))
    val h1 = Regex("<h1[\\s>]", RegexOption.IGNORE_CASE).findAll(html).count()
    if (h1 == 1) L.ok(str(R.string.a_single_h1_heading)) else L.wa(str(R.string.h1_count_1_is_recommended, h1))
    val imgs = Regex("<img\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { attrs(it.value) }.toList()
    val noalt = imgs.count { !it.containsKey("alt") }
    if (noalt > 0) L.wa(str(R.string.images_without_alt_of, noalt, imgs.size)) else L.ok(str(R.string.all_images_have_alt, imgs.size))
    listOf("og:title", "og:description", "og:image", "og:url").forEach { k -> prop(k)?.let { L.ok("$k: " + it.take(80)) } ?: L.wa(str(R.string.missing, k)) }
    meta("twitter:card")?.let { L.ok("twitter:card: $it") } ?: L.wa(str(R.string.missing_twitter_card))
    val hl = links.filter { (it["rel"] ?: "").contains("alternate", true) && it.containsKey("hreflang") }.map { it["hreflang"] }
    if (hl.isNotEmpty()) L.ok("hreflang: " + hl.joinToString(", "))
    L.l.add("")
    val u = URL(r.url)
    val origin = u.protocol + "://" + u.authority
    for (f in listOf("robots.txt", "sitemap.xml")) {
        val x = httpFetch("$origin/$f", maxBody = 200_000)
        if (x.error == null && x.status == 200 && x.body != null) L.ok("$f existuje (${x.body.size} B)") else L.wa("$f: " + (if (x.status > 0) "HTTP ${x.status}" else str(R.string.unavailable)))
        if (f == "robots.txt" && x.status == 200 && x.body != null) {
            val sm = Regex("^sitemap:\\s*(\\S+)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).findAll(x.text).map { it.groupValues[1] }.toList()
            if (sm.isNotEmpty()) L.l.add(str(R.string.sitemap_in_robots_txt) + sm.joinToString(", "))
        }
    }
    val errs = L.l.count { it.startsWith("✗") }
    return r.url + "\n" + (if (errs > 0) str(R.string.errors, errs) else str(R.string.no_critical_errors)) + "\n\n" + L.l.joinToString("\n")
}

// ───────────────────────── Kontrola odkazov ─────────────────────────

fun linkCheck(url: String): String {
    val r = httpFetch(url)
    if (r.error != null) throw Exception(r.error)
    if (r.body == null || r.isBinary) throw Exception(str(R.string.the_response_is_not_html))
    val base = URL(r.url)
    val all = LinkedHashSet<String>()
    Regex("<a\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(r.text).forEach { a ->
        val href = attrs(a.value)["href"]?.trim() ?: return@forEach
        if (href.isEmpty() || href.startsWith("#") || Regex("^(mailto|tel|javascript|data):", RegexOption.IGNORE_CASE).containsMatchIn(href)) return@forEach
        val abs = runCatching { URL(base, htmlDecode(href)).toString().substringBefore('#') }.getOrNull() ?: return@forEach
        if (abs.startsWith("http://") || abs.startsWith("https://")) all.add(abs)
    }
    val todo = all.take(24)
    val pool = Executors.newFixedThreadPool(6)
    class Res(val url: String, val status: Int, val loc: String?, val error: String?, val internal: Boolean)
    val res = try {
        pool.invokeAll(todo.map { l ->
            Callable {
                var x = httpFetch(l, method = "HEAD", follow = false, readBody = false, timeoutMs = 8000)
                if (x.error != null || x.status >= 400) x = httpFetch(l, follow = false, readBody = false, timeoutMs = 8000)
                Res(l, x.status, x.hops.lastOrNull()?.location, x.error, runCatching { URL(l).host == base.host }.getOrDefault(false))
            }
        }).map { it.get() }
    } finally { pool.shutdown() }
    val bad = res.filter { it.error != null || it.status >= 400 }
    fun f(x: Res) = (if (x.error != null) "CHYBA" else x.status.toString()).padEnd(7) + (if (x.internal) "int  " else "ext  ") + x.url +
        (if (x.loc != null) "\n            → ${x.loc}" else "") + (if (x.error != null) "  (${x.error})" else "")
    val out = mutableListOf(str(R.string.links_on_the_page_checked_broken, all.size, res.size, bad.size), "")
    if (bad.isNotEmpty()) { out.add(str(R.string.broken_links)); bad.forEach { out.add(f(it)) }; out.add("") }
    out.add(str(R.string.all_checked)); res.forEach { out.add(f(it)) }
    return out.joinToString("\n")
}

// ───────────────────────── Porty ─────────────────────────

fun portScan(host0: String, spec: String): String {
    val host = host0.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':').ifEmpty { throw Exception(str(R.string.enter_a_host)) }
    val ports = spec.split(Regex("[,\\s]+")).filter { it.isNotBlank() }.map {
        it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: throw Exception(str(R.string.invalid_port_2, it))
    }.distinct()
    if (ports.isEmpty() || ports.size > 10) throw Exception(str(R.string.enter_1_to_10_ports))
    class P(val port: Int, val open: Boolean, val err: String, val ms: Long)
    val pool = Executors.newFixedThreadPool(ports.size)
    val res = try {
        pool.invokeAll(ports.map { p ->
            Callable {
                val t0 = System.nanoTime()
                try {
                    Socket().use { it.connect(InetSocketAddress(host, p), 4000) }
                    P(p, true, "", (System.nanoTime() - t0) / 1_000_000)
                } catch (e: Exception) {
                    val msg = when {
                        e is SocketTimeoutException -> str(R.string.timed_out)
                        e is UnknownHostException -> str(R.string.unknown_host)
                        e is ConnectException && (e.message ?: "").contains("refused", true) -> str(R.string.closed)
                        else -> e.message ?: e.javaClass.simpleName
                    }
                    P(p, false, msg, (System.nanoTime() - t0) / 1_000_000)
                }
            }
        }).map { it.get() }
    } finally { pool.shutdown() }
    return str(R.string.of_open_port_state_time, host, res.count { it.open }, res.size) +
        res.sortedBy { it.port }.joinToString("\n") { it.port.toString().padEnd(8) + (if (it.open) str(R.string.open) else it.err).padEnd(22) + it.ms + " ms" }
}

// ───────────────────────── Kontrola e-mailu ─────────────────────────

fun mailReport(domain: String, mx: List<String>, txt: List<String>, dmarc: List<String>, dkim: Map<String, List<String>>, mtasts: List<String>): String {
    val L = Lines()
    val o = mutableListOf(domain, "", "MX")
    if (mx.isNotEmpty()) mx.forEach { o.add("✓ $it") } else o.add(str(R.string.no_mx_record_the_domain_cannot_receive_m))
    o.add(""); o.add("SPF")
    val spf = txt.filter { Regex("^v=spf1\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    if (spf.isEmpty()) L.bad(str(R.string.spf_record_is_missing)) else {
        if (spf.size > 1) L.bad(str(R.string.multiple_spf_records_the_specification_a))
        val r = spf[0]
        L.l.add("  $r")
        val all = Regex("\\s([-~?+]?)all\\b", RegexOption.IGNORE_CASE).find(r)
        if (all != null) {
            when (all.groupValues[1]) {
                "-" -> L.ok(str(R.string.ends_with_all_strict))
                "~" -> L.wa(str(R.string.ends_with_all_soft_fail_consider_all))
                else -> L.bad(str(R.string.the_all_mechanism_is) + (all.groupValues[1].ifEmpty { "+" }) + str(R.string.all_allows_anyone))
            }
        } else if (r.contains("redirect=")) L.ok(str(R.string.uses_redirect)) else L.wa(str(R.string.the_all_mechanism_is_missing))
        val n = Regex("(?:^|\\s)[-~?+]?(?:include:|a\\b|mx\\b|ptr\\b|exists:|redirect=)", RegexOption.IGNORE_CASE).findAll(r).count()
        if (n > 10) L.bad(str(R.string.about_dns_lookups_the_limit_is_10, n)) else L.ok(str(R.string.about_of_10_dns_lookups_direct_only_with, n))
    }
    o.addAll(L.l); L.l.clear()
    o.add(""); o.add("DMARC")
    val dm = dmarc.filter { it.startsWith("v=DMARC1", true) }
    if (dm.isEmpty()) L.bad(str(R.string.dmarc_record_is_missing_dmarc)) else {
        val r = dm[0]
        val p = Regex("\\bp=(\\w+)", RegexOption.IGNORE_CASE).find(r)?.groupValues?.get(1) ?: ""
        L.l.add("  $r")
        if (p.equals("reject", true) || p.equals("quarantine", true)) L.ok("Politika p=$p.") else L.wa("Politika p=none, iba monitoring.")
        if (Regex("\\brua=", RegexOption.IGNORE_CASE).containsMatchIn(r)) L.ok(str(R.string.sends_aggregate_reports_rua)) else L.wa(str(R.string.rua_is_missing_you_will_not_receive_repo))
    }
    o.addAll(L.l); L.l.clear()
    o.add(""); o.add("DKIM")
    if (dkim.isNotEmpty()) dkim.keys.forEach { o.add(str(R.string.selector, it)) } else o.add(str(R.string.nothing_was_found_in_the_tried_selectors))
    o.add(""); o.add("MTA-STS")
    o.add(if (mtasts.any { it.contains("v=STSv1", true) }) str(R.string.mta_sts_is_set_up) else str(R.string.mta_sts_is_not_set_up_optional))
    return o.joinToString("\n")
}

// ───────────────────────── JWT overenie podpisu (JWKS) ─────────────────────────

private fun bi(m: Map<*, *>, k: String) = BigInteger(1, b64u(m[k] as? String ?: throw Exception(str(R.string.is_missing_in_the_jwk, k))))

private fun derFromRaw(sig: ByteArray): ByteArray {
    val n = sig.size / 2
    fun enc(b: ByteArray): ByteArray { val v = BigInteger(1, b).toByteArray(); return byteArrayOf(0x02, v.size.toByte()) + v }
    val body = enc(sig.copyOfRange(0, n)) + enc(sig.copyOfRange(n, sig.size))
    return if (body.size < 128) byteArrayOf(0x30, body.size.toByte()) + body else byteArrayOf(0x30, 0x81.toByte(), body.size.toByte()) + body
}

internal fun verifySig(alg: String, jwk: Map<*, *>, data: ByteArray, sig: ByteArray): Boolean {
    val bits = alg.substring(2)
    val hash = "SHA-$bits"
    return when {
        alg.startsWith("RS") || alg.startsWith("PS") -> {
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(bi(jwk, "n"), bi(jwk, "e")))
            val s = if (alg.startsWith("RS")) Signature.getInstance("SHA${bits}withRSA") else Signature.getInstance("RSASSA-PSS").also {
                it.setParameter(PSSParameterSpec(hash, "MGF1", MGF1ParameterSpec(hash), bits.toInt() / 8, 1))
            }
            s.initVerify(pub); s.update(data); s.verify(sig)
        }
        alg.startsWith("ES") -> {
            val curve = when (jwk["crv"]) { "P-256" -> "secp256r1"; "P-384" -> "secp384r1"; "P-521" -> "secp521r1"; else -> throw Exception(str(R.string.unsupported_curve, jwk["crv"])) }
            val ap = AlgorithmParameters.getInstance("EC"); ap.init(ECGenParameterSpec(curve))
            val spec = ap.getParameterSpec(ECParameterSpec::class.java)
            val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(bi(jwk, "x"), bi(jwk, "y")), spec))
            val s = Signature.getInstance("SHA${bits}withECDSA")
            s.initVerify(pub); s.update(data); s.verify(derFromRaw(sig))
        }
        else -> throw Exception(str(R.string.algorithm_is_not_supported_rs_ps_and_es, alg))
    }
}

fun jwksVerify(token: String, jwksUrl0: String): Pair<String, Boolean> {
    val p = token.trim().split('.')
    if (p.size != 3) throw Exception(str(R.string.a_jwt_must_have_3_parts_separated_by_dot))
    val h = runCatching { Js.parse(String(b64u(p[0]))) as Map<*, *> }.getOrNull() ?: throw Exception(str(R.string.could_not_decode_the_token_header))
    val pl = runCatching { Js.parse(String(b64u(p[1]))) as Map<*, *> }.getOrNull() ?: throw Exception(str(R.string.could_not_decode_the_token_payload))
    val alg = h["alg"]?.toString() ?: throw Exception(str(R.string.alg_is_missing_in_the_header))
    val kid = h["kid"]?.toString()
    val iss = pl["iss"]?.toString()
    var jwksUrl = jwksUrl0.trim()
    if (jwksUrl.isEmpty()) {
        if (iss.isNullOrEmpty()) throw Exception(str(R.string.the_token_has_no_iss_enter_the_jwks_url))
        val b = iss.trimEnd('/')
        val a = httpFetch("$b/.well-known/jwks.json", maxBody = 300_000)
        if (a.error == null && a.status == 200 && a.text.contains("\"keys\"")) jwksUrl = "$b/.well-known/jwks.json"
        else {
            val c = httpFetch("$b/.well-known/openid-configuration", maxBody = 300_000)
            val u = if (c.error == null && c.status == 200) (runCatching { Js.parse(c.text) as Map<*, *> }.getOrNull()?.get("jwks_uri") as? String) else null
            jwksUrl = u ?: throw Exception(str(R.string.could_not_determine_the_jwks_from_iss_en))
        }
    }
    val r = httpFetch(jwksUrl, maxBody = 500_000)
    if (r.error != null) throw Exception(r.error)
    if (r.status != 200) throw Exception(str(R.string.jwks_returned_http, r.status))
    val keys = ((runCatching { Js.parse(r.text) as Map<*, *> }.getOrNull() ?: throw Exception(str(R.string.jwks_is_not_valid_json)))["keys"] as? List<*>)
        ?.mapNotNull { it as? Map<*, *> } ?: throw Exception(str(R.string.the_jwks_has_no_keys_field))
    val kty = if (alg.startsWith("ES")) "EC" else "RSA"
    var cand = keys.filter { it["kty"] == kty && it["use"] != "enc" }
    if (kid != null) cand = cand.filter { it["kid"] == kid }.ifEmpty { throw Exception(str(R.string.no_key_with_kid_was_found_in_the_jwks, kid)) }
    if (cand.isEmpty()) throw Exception(str(R.string.there_is_no_suitable_key_in_the_jwks))
    val data = (p[0] + "." + p[1]).toByteArray(Charsets.US_ASCII)
    val sig = b64u(p[2])
    var valid = false
    var lastErr: Exception? = null
    for (k in cand) { try { if (verifySig(alg, k, data, sig)) { valid = true; break } } catch (e: Exception) { lastErr = e } }
    if (!valid && lastErr != null && cand.size == 1) throw Exception(str(R.string.verification_failed, lastErr.message))
    val now = Instant.now().epochSecond
    val exp = (pl["exp"] as? Number)?.toLong()
    val nbf = (pl["nbf"] as? Number)?.toLong()
    val out = str(R.string.signature_algorithm_kid_jwks_issuer, if (valid) str(R.string.valid) else str(R.string.invalid), alg, kid ?: "—", jwksUrl, iss ?: "—") +
        str(R.string.validity) + (if (exp == null) str(R.string.no_exp) else (if (exp < now) str(R.string.expired) else str(R.string.valid)) + " (${fmtEpoch(exp)})") +
        (if (nbf != null) "\nnbf          " + (if (nbf > now) str(R.string.not_yet_valid) else str(R.string.ok)) + " (${fmtEpoch(nbf)})" else "")
    return out to valid
}
