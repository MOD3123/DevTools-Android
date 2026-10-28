// SPDX-License-Identifier: GPL-3.0-or-later
package sk.devtools

// Doplnkové sieťové nástroje. Všetky funkcie sú blokujúce – volaj ich z Dispatchers.IO.

import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// ───────────────────────── User-Agent ─────────────────────────

val UAS = listOf(
    str(R.string.default_label_2) to "DevTools/1.0",
    "Android" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
    "iPhone" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
    "Desktop" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    "Googlebot" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
    "curl" to "curl/8.7.1"
)

fun uaOf(name: String) = UAS.firstOrNull { it.first == name }?.second ?: UAS[0].second

// Pridá User-Agent na začiatok hlavičiek, ak nie je predvolený a používateľ si nenastavil vlastný.
fun withUa(h: List<Pair<String, String>>, ua: String): List<Pair<String, String>> =
    if (ua == UAS[0].first || h.any { it.first.equals("User-Agent", true) }) h else listOf("User-Agent" to uaOf(ua)) + h

fun parseHeaders(t: String): List<Pair<String, String>> = t.lines().mapNotNull { l ->
    val i = l.indexOf(':')
    if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
}

// ───────────────────────── Export cURL ─────────────────────────

private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

fun toCurl(
    url: String, method: String, headers: List<Pair<String, String>>, body: String?,
    basic: Pair<String, String>?, bearer: String?, follow: Boolean
): String {
    val p = mutableListOf("curl")
    if (method == "HEAD") p.add("-I") else if (method != "GET") p.add("-X $method")
    if (follow) p.add("-L")
    headers.forEach { p.add("-H " + shq(it.first + ": " + it.second)) }
    if (basic != null) p.add("-u " + shq(basic.first + ":" + basic.second))
    else if (!bearer.isNullOrBlank()) p.add("-H " + shq("Authorization: Bearer " + bearer.trim()))
    if (!body.isNullOrEmpty() && method != "GET" && method != "HEAD") p.add("--data-raw " + shq(body))
    p.add(shq(normalizeUrl(url)))
    return p.joinToString(" \\\n  ")
}

internal fun Resp.hv(name: String): String? =
    headers.filter { it.first.equals(name, true) }.joinToString(", ") { it.second }.ifEmpty { null }

private fun dur(sec: Long): String = when {
    sec < 60 -> "$sec s"
    sec < 3600 -> "${sec / 60} min"
    sec < 86400 -> "${sec / 3600} h"
    else -> str(R.string.days, sec / 86400)
}

// ───────────────────────── SSL / TLS certifikát ─────────────────────────

// Na zistenie certifikátu sa pripojíme aj k serveru s neplatným certifikátom; dôveryhodnosť vyhodnotíme zvlášť.
internal val TRUST_ALL = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun dn(name: String, key: String): String? =
    Regex("(?:^|,)\\s*" + key + "=((?:\\\\.|[^,])+)").find(name)?.groupValues?.get(1)?.replace("\\", "")

private fun fp(c: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) }

private fun keyInfo(c: X509Certificate): String = when (val k = c.publicKey) {
    is RSAPublicKey -> "RSA ${k.modulus.bitLength()} bit"
    is ECPublicKey -> "EC ${k.params.order.bitLength()} bit"
    else -> k.algorithm
}

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

fun tlsInfo(input: String): String {
    val t = input.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringAfterLast('@')
    val host = t.substringBefore(':').ifEmpty { throw Exception(str(R.string.enter_a_host)) }
    val port = if (t.contains(':')) (t.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: throw Exception(str(R.string.invalid_port))) else 443

    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(TRUST_ALL), null) }
    val raw = Socket()
    raw.connect(InetSocketAddress(host, port), 8000)
    raw.soTimeout = 8000
    val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
    class Hs(val chain: List<X509Certificate>, val protocol: String, val cipher: String, val nameOk: Boolean)
    val hs = try {
        s.startHandshake()
        val sess = s.session
        Hs(
            sess.peerCertificates.filterIsInstance<X509Certificate>(), sess.protocol, sess.cipherSuite,
            runCatching { HttpsURLConnection.getDefaultHostnameVerifier().verify(host, sess) }.getOrDefault(false)
        )
    } finally {
        runCatching { s.close() }
    }
    val chain = hs.chain
    val protocol = hs.protocol
    val cipher = hs.cipher
    val nameOk = hs.nameOk
    if (chain.isEmpty()) throw Exception(str(R.string.the_server_did_not_send_any_certificate))

    val trustErr = runCatching {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        tm.checkServerTrusted(chain.toTypedArray(), "RSA")
    }.exceptionOrNull()

    val leaf = chain[0]
    val left = Duration.between(Instant.now(), leaf.notAfter.toInstant()).toDays()
    val sb = StringBuilder()
    sb.append("$host:$port\n")
    sb.append(str(R.string.protocol_cipher, protocol, cipher))
    sb.append(if (trustErr == null) str(R.string.the_certificate_is_trusted_verified_by_t) else str(R.string.the_certificate_is_not_trusted, trustErr.message ?: trustErr.javaClass.simpleName))
    sb.append(if (nameOk) str(R.string.the_host_name_matches_the_certificate) else str(R.string.the_host_name_does_not_match_the_certifi, host))
    sb.append(when {
        left < 0 -> str(R.string.the_certificate_expired_days_ago, -left)
        Instant.now().isBefore(leaf.notBefore.toInstant()) -> str(R.string.the_certificate_is_not_valid_yet)
        left < 14 -> str(R.string.the_certificate_expires_in_days, left)
        else -> str(R.string.still_valid_for_days, left)
    })
    sb.append(str(R.string.chain, chain.size) + (if (chain.size == 1) str(R.string.certificate) else if (chain.size < 5) str(R.string.certificates_2) else str(R.string.certificates)) + "\n")

    chain.forEachIndexed { i, c ->
        val self = c.subjectX500Principal == c.issuerX500Principal
        val role = when {
            i == 0 -> str(R.string.leaf_certificate)
            self -> str(R.string.root_certificate)
            else -> str(R.string.intermediate_certificate)
        }
        val subj = c.subjectX500Principal.name
        val iss = c.issuerX500Principal.name
        sb.append("\n[${i + 1}] $role\n")
        sb.append(str(R.string.subject, dn(subj, "CN") ?: subj) + (dn(subj, "O")?.let { "  ($it)" } ?: "") + "\n")
        sb.append(str(R.string.issuer, dn(iss, "CN") ?: iss) + (dn(iss, "O")?.let { "  ($it)" } ?: "") + (if (self) "  (self-signed)" else "") + "\n")
        sb.append(str(R.string.validity_to, DATE_FMT.format(c.notBefore.toInstant()), DATE_FMT.format(c.notAfter.toInstant())))
        if (i == 0) {
            val san = runCatching {
                c.subjectAlternativeNames?.mapNotNull { e -> if (e[0] == 2 || e[0] == 7) e[1] as? String else null }
            }.getOrNull().orEmpty()
            if (san.isNotEmpty()) sb.append(str(R.string.alternative_names) + san.take(12).joinToString(", ") + (if (san.size > 12) str(R.string.more, san.size - 12) else "") + "\n")
        }
        sb.append(str(R.string.key_signature, keyInfo(c), c.sigAlgName))
        sb.append(str(R.string.serial_number, c.serialNumber.toString(16).uppercase()))
        sb.append("SHA-256: ${fp(c)}\n")
    }
    return sb.toString().trimEnd()
}

// ───────────────────────── CORS ─────────────────────────

fun corsTest(url: String, origin: String, method: String, reqHeaders: String, ua: String): String {
    val o = origin.trim().ifEmpty { throw Exception(str(R.string.enter_an_origin)) }
    val base = withUa(emptyList(), ua)
    val rh = reqHeaders.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    val preH = base + listOf("Origin" to o, "Access-Control-Request-Method" to method) +
        (if (rh.isNotEmpty()) listOf("Access-Control-Request-Headers" to rh.joinToString(",")) else emptyList())
    val pre = httpFetch(url, "OPTIONS", preH, readBody = false, follow = false)
    val act = httpFetch(url, method, base + listOf("Origin" to o), readBody = false, follow = false)

    var bad = 0
    val out = StringBuilder()
    fun ok(t: String) { out.append("  ✓ $t\n") }
    fun wa(t: String) { out.append("  ! $t\n") }
    fun no(t: String) { bad++; out.append("  ✗ $t\n") }

    fun section(title: String, r: Resp, isPre: Boolean) {
        out.append("$title → ")
        if (r.error != null) { out.append("chyba\n"); no(r.error); out.append("\n"); return }
        out.append("HTTP ${r.status} ${r.statusText}".trim() + "\n")
        r.headers.filter { it.first.startsWith("access-control-", true) || it.first.equals("vary", true) }
            .forEach { out.append("    ${it.first}: ${it.second}\n") }
        val acao = r.hv("access-control-allow-origin")
        val creds = r.hv("access-control-allow-credentials")?.equals("true", true) == true
        when {
            acao == null -> no(str(R.string.access_control_allow_origin_is_missing_t))
            acao == "*" -> if (creds) no(str(R.string.the_value_cannot_be_combined_with_allow)) else ok(str(R.string.all_origins_are_allowed))
            acao == o -> ok(str(R.string.origin_is_allowed, o))
            else -> no(str(R.string.only_is_allowed_not, acao, o))
        }
        if (acao != null && acao != "*" && r.hv("vary")?.contains("origin", true) != true) wa(str(R.string.vary_origin_is_missing_a_cache_may_retur))
        if (creds) ok(str(R.string.the_response_allows_cookies_and_credenti))
        if (isPre) {
            if (r.status !in 200..299) no(str(R.string.the_preflight_must_return_a_2xx_status_t, r.status))
            val am = r.hv("access-control-allow-methods")?.split(',')?.map { it.trim().uppercase() }
            val simple = method in listOf("GET", "HEAD", "POST")
            if (am == null) { if (simple) wa(str(R.string.access_control_allow_methods_is_missing, method)) else no(str(R.string.access_control_allow_methods_is_missing_2)) }
            else if (method.uppercase() in am || "*" in am) ok(str(R.string.method_is_allowed, method)) else no(str(R.string.method_is_not_in_the_list, method, am.joinToString(", ")))
            if (rh.isNotEmpty()) {
                val ah = r.hv("access-control-allow-headers")?.split(',')?.map { it.trim().lowercase() }
                if (ah == null) no(str(R.string.access_control_allow_headers_is_missing, rh.joinToString(", ")))
                else {
                    val miss = rh.filter { it !in ah && "*" !in ah }
                    if (miss.isEmpty()) ok(str(R.string.the_headers_are_allowed, rh.joinToString(", "))) else no(str(R.string.headers_not_allowed, miss.joinToString(", ")))
                }
            }
            r.hv("access-control-max-age")?.let { ok(str(R.string.the_preflight_is_cached_for_s, it)) }
        } else {
            r.hv("access-control-expose-headers")?.let { ok(str(R.string.javascript_will_see_the_headers, it)) }
        }
        out.append("\n")
    }

    section("Preflight (OPTIONS)", pre, true)
    section(str(R.string.actual_request, method), act, false)
    out.append(if (bad == 0) str(R.string.result_the_browser_would_allow_the_reque, o) else str(R.string.result_the_browser_would_block_the_reque, bad))
    return out.toString()
}

// ───────────────────────── Cache a CDN ─────────────────────────

private val CDN_HEADERS = listOf(
    "cf-ray" to "Cloudflare", "x-amz-cf-id" to "Amazon CloudFront", "x-amz-cf-pop" to "Amazon CloudFront",
    "x-vercel-id" to "Vercel", "x-nf-request-id" to "Netlify", "x-azure-ref" to "Azure Front Door",
    "x-fastly-request-id" to "Fastly", "x-sucuri-id" to "Sucuri", "x-akamai-transformed" to "Akamai",
    "x-github-request-id" to "GitHub Pages", "x-bunny-cache-state" to "BunnyCDN", "x-served-by" to "Fastly / Varnish"
)

fun cacheCheck(url: String, ua: String): String {
    val r = httpFetch(url, headers = withUa(emptyList(), ua) + listOf("Accept-Encoding" to "gzip, br"), readBody = false)
    if (r.error != null) throw Exception(r.error)
    val out = StringBuilder("${r.url} (HTTP ${r.status})\n\n")
    fun ok(t: String) { out.append("✓ $t\n") }
    fun wa(t: String) { out.append("! $t\n") }

    out.append(str(R.string.browser_cache))
    val cc = r.hv("cache-control")
    val d = LinkedHashMap<String, String>()
    cc?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.forEach {
        d[it.substringBefore('=').trim()] = it.substringAfter('=', "").trim().trim('"')
    }
    if (cc != null) out.append("Cache-Control: $cc\n")
    val maxAge = d["max-age"]?.toLongOrNull()
    val sMax = d["s-maxage"]?.toLongOrNull()
    when {
        "no-store" in d -> wa(str(R.string.the_response_is_never_stored_no_store))
        "no-cache" in d -> wa(str(R.string.every_use_must_be_revalidated_no_cache))
        maxAge != null && maxAge > 0 -> ok(str(R.string.the_browser_keeps_a_copy_for, dur(maxAge)) + (if ("immutable" in d) " a nerevaliduje (immutable)." else "."))
        maxAge == 0L -> wa(str(R.string.max_age_0_the_copy_is_stale_immediately))
        cc == null && r.hv("expires") == null -> wa(str(R.string.both_cache_control_and_expires_are_missi))
    }
    if (sMax != null) ok(str(R.string.shared_caches_cdn_keep_a_copy_for_s_maxa, dur(sMax)))
    if ("private" in d) wa(str(R.string.private_only_the_browser_stores_it_not_t)) else if ("public" in d) ok(str(R.string.public_a_cdn_and_proxies_may_store_it_to))
    d["stale-while-revalidate"]?.toLongOrNull()?.let { ok(str(R.string.after_expiry_a_stale_copy_is_served_for, dur(it))) }
    if ("must-revalidate" in d) out.append(str(R.string.must_revalidate_revalidation_is_required))
    r.hv("expires")?.let { out.append("Expires: $it\n") }
    r.hv("pragma")?.let { out.append("Pragma: $it\n") }
    r.hv("vary")?.let { out.append("Vary: $it\n") }

    out.append(str(R.string.revalidation))
    val etag = r.hv("etag")
    val lm = r.hv("last-modified")
    etag?.let { out.append("ETag: $it\n") }
    lm?.let { out.append("Last-Modified: $it\n") }
    if (etag == null && lm == null) wa(str(R.string.both_etag_and_last_modified_are_missing))
    else {
        val cond = if (etag != null) listOf("If-None-Match" to etag) else listOf("If-Modified-Since" to lm!!)
        val c2 = httpFetch(r.url, headers = withUa(emptyList(), ua) + cond, readBody = false)
        if (c2.error != null) wa(str(R.string.the_conditional_request_test_failed, c2.error))
        else if (c2.status == 304) ok(str(R.string.the_conditional_request_returned_304_rev))
        else wa(str(R.string.the_conditional_request_returned_http_in, c2.status))
    }
    r.hv("age")?.toLongOrNull()?.let { out.append(str(R.string.age_s_in_the_shared_cache, it, dur(it))) }
    r.hv("content-encoding")?.let { out.append(str(R.string.compression, it)) } ?: r.hv("content-type")?.let {
        if (it.contains("text", true) || it.contains("json", true) || it.contains("javascript", true) || it.contains("xml", true)) wa(str(R.string.text_response_without_compression_conten))
    }

    out.append(str(R.string.cdn_and_proxy))
    val found = LinkedHashSet<String>()
    CDN_HEADERS.forEach { (h, n) -> if (r.hv(h) != null) found.add(n) }
    val server = r.hv("server")?.lowercase() ?: ""
    val via = r.hv("via")?.lowercase() ?: ""
    if ("cloudflare" in server) found.add("Cloudflare")
    if ("akamai" in server) found.add("Akamai")
    if ("cloudfront" in via) found.add("Amazon CloudFront")
    if ("varnish" in via || r.hv("x-varnish") != null) found.add("Varnish")
    if ("vercel" in server) found.add("Vercel")
    if ("netlify" in server) found.add("Netlify")
    if (found.isEmpty()) out.append(str(R.string.no_known_cdn_was_detected)) else ok(str(R.string.detected) + found.joinToString(", "))
    listOf("server", "via", "cf-cache-status", "x-cache", "x-cache-status", "x-cache-hits", "x-served-by", "x-varnish", "server-timing").forEach { h ->
        r.hv(h)?.let { out.append("$h: $it\n") }
    }
    return out.toString().trimEnd()
}

// ───────────────────────── Ping (TCP) ─────────────────────────

fun tcpPing(host0: String, port: Int, n: Int): String {
    val host = host0.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').substringAfterLast('@').substringBefore(':')
        .ifEmpty { throw Exception(str(R.string.enter_a_host)) }
    if (port !in 1..65535) throw Exception(str(R.string.invalid_port))
    val t0 = System.nanoTime()
    val addr = try { InetAddress.getByName(host) } catch (e: Exception) { throw Exception(str(R.string.could_not_resolve_the_host)) }
    val dns = (System.nanoTime() - t0) / 1e6
    val sb = StringBuilder("PING $host (${addr.hostAddress})  ·  TCP port $port\n")
    sb.append("DNS: %.0f ms\n\n".format(Locale.ROOT, dns))
    val times = mutableListOf<Double>()
    var lost = 0
    for (i in 1..n) {
        val s = System.nanoTime()
        try {
            Socket().use { it.connect(InetSocketAddress(addr, port), 3000) }
            val ms = (System.nanoTime() - s) / 1e6
            times.add(ms)
            sb.append("$i: %.1f ms\n".format(Locale.ROOT, ms))
        } catch (e: Exception) {
            if (e is ConnectException && (e.message ?: "").contains("refused", true)) {
                val ms = (System.nanoTime() - s) / 1e6
                times.add(ms)
                sb.append(str(R.string.s_1f_ms_port_closed_host_is_responding, i).format(Locale.ROOT, ms))
            } else {
                lost++
                sb.append("$i: " + (if (e is SocketTimeoutException) str(R.string.timed_out) else (e.message ?: e.javaClass.simpleName)) + "\n")
            }
        }
        if (i < n) Thread.sleep(250)
    }
    sb.append(str(R.string.sent_received_loss, n, n - lost, lost * 100 / n))
    if (times.isNotEmpty()) {
        val avg = times.average()
        val jit = if (times.size > 1) times.zipWithNext { a, b -> Math.abs(a - b) }.average() else 0.0
        sb.append("min / priem. / max: %.1f / %.1f / %.1f ms  ·  jitter %.1f ms".format(Locale.ROOT, times.min(), avg, times.max(), jit))
    }
    return sb.toString()
}
