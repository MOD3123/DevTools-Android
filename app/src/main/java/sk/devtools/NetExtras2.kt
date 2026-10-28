// SPDX-License-Identifier: GPL-3.0-or-later
package sk.devtools

// Ďalšie nástroje: WebSocket, GeoIP, HTTP/2 + TLS verzie, JWT generátor, formátovače a návrhy pre zdieľaný text.

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

// ───────────────────────── WebSocket ─────────────────────────

private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

fun wsTest(url0: String, message: String, headers: List<Pair<String, String>>, listenSec: Int): String {
    val u = url0.trim().ifEmpty { throw Exception(str(R.string.enter_the_websocket_address)) }
    val uri = try { URI(if (u.contains("://")) u else "wss://$u") } catch (e: Exception) { throw Exception(str(R.string.invalid_address)) }
    val tls = when (uri.scheme?.lowercase()) {
        "wss", "https" -> true
        "ws", "http" -> false
        else -> throw Exception(str(R.string.use_a_ws_or_wss_address))
    }
    val host = uri.host ?: throw Exception(str(R.string.invalid_host))
    val port = if (uri.port > 0) uri.port else if (tls) 443 else 80
    val path = (uri.rawPath?.ifEmpty { null } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

    val t0 = System.nanoTime()
    val log = StringBuilder()
    fun say(s: String) {
        log.append("[%5d ms] ".format(Locale.ROOT, (System.nanoTime() - t0) / 1_000_000)).append(s).append('\n')
    }
    val rnd = SecureRandom()

    val raw = Socket()
    raw.connect(InetSocketAddress(host, port), 8000)
    val sock: Socket = if (tls) SSLContext.getDefault().socketFactory.createSocket(raw, host, port, true) else raw
    try {
        sock.soTimeout = 8000
        if (sock is SSLSocket) sock.startHandshake()
        say(str(R.string.connected_to, host, port) + if (tls) " (TLS)" else "")
        val inp = sock.getInputStream()
        val out = sock.getOutputStream()

        // Handshake
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { rnd.nextBytes(it) })
        val rq = StringBuilder("GET $path HTTP/1.1\r\nHost: $host" + (if (uri.port > 0) ":${uri.port}" else "") +
            "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n")
        headers.forEach { rq.append(it.first.replace("\r", "").replace("\n", "")).append(": ").append(it.second.replace("\r", "").replace("\n", "")).append("\r\n") }
        rq.append("\r\n")
        out.write(rq.toString().toByteArray())
        out.flush()

        val hb = ByteArrayOutputStream()
        var last4 = 0
        val hDeadline = System.nanoTime() + 8_000_000_000L
        while (true) {
            val b = try { inp.read() } catch (e: SocketTimeoutException) {
                if (System.nanoTime() > hDeadline) throw Exception(str(R.string.timed_out_waiting_for_the_server_s_respo))
                continue
            }
            if (b < 0) throw Exception(str(R.string.the_server_closed_the_connection_2))
            hb.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (hb.size() > 16384) throw Exception(str(R.string.the_server_s_response_is_too_large))
        }
        val head = String(hb.toByteArray(), Charsets.ISO_8859_1)
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        val status = lines.firstOrNull() ?: ""
        val code = status.split(' ').getOrNull(1)?.toIntOrNull()
        val hdr = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        if (code != 101) {
            say(str(R.string.the_server_rejected_the_upgrade_to_webso, status))
            lines.drop(1).forEach { log.append("    ").append(it).append('\n') }
            return log.toString().trimEnd()
        }
        val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray()))
        say(if (hdr["sec-websocket-accept"] == expected) str(R.string.handshake_successful_101_switching_proto) else str(R.string.the_sec_websocket_accept_header_does_not))
        hdr["sec-websocket-protocol"]?.let { say(str(R.string.subprotocol, it)) }

        // Odosielanie rámcov (klient maskuje)
        fun sendFrame(op: Int, payload: ByteArray) {
            val bo = ByteArrayOutputStream()
            bo.write(0x80 or op)
            val n = payload.size
            when {
                n < 126 -> bo.write(0x80 or n)
                n <= 0xFFFF -> { bo.write(0x80 or 126); bo.write(n shr 8); bo.write(n and 0xFF) }
                else -> { bo.write(0x80 or 127); for (i in 7 downTo 0) bo.write(((n.toLong() shr (8 * i)) and 0xFF).toInt()) }
            }
            val mask = ByteArray(4).also { rnd.nextBytes(it) }
            bo.write(mask)
            for (i in payload.indices) bo.write(payload[i].toInt() xor mask[i % 4].toInt())
            out.write(bo.toByteArray())
            out.flush()
        }

        if (message.isNotEmpty()) {
            sendFrame(1, message.toByteArray())
            say("→ " + message.take(2000))
        }

        // Počúvanie
        sock.soTimeout = 1000
        val deadline = System.nanoTime() + listenSec * 1_000_000_000L
        val hard = deadline + 3_000_000_000L
        fun readExact(buf: ByteArray, idleOk: Boolean) {
            var off = 0
            while (off < buf.size) {
                try {
                    val r = inp.read(buf, off, buf.size - off)
                    if (r < 0) throw EOFException()
                    off += r
                } catch (e: SocketTimeoutException) {
                    if (off == 0 && idleOk) throw e
                    if (System.nanoTime() > hard) throw e
                }
            }
        }

        var serverClosed = false
        var fragOp = 0
        val frag = ByteArrayOutputStream()
        var received = 0
        while (System.nanoTime() < deadline && !serverClosed) {
            try {
                val h = ByteArray(2)
                readExact(h, true)
                val fin = (h[0].toInt() and 0x80) != 0
                val op = h[0].toInt() and 0x0F
                var len = (h[1].toInt() and 0x7F).toLong()
                if (len == 126L) {
                    val e = ByteArray(2); readExact(e, false)
                    len = (((e[0].toInt() and 0xFF) shl 8) or (e[1].toInt() and 0xFF)).toLong()
                } else if (len == 127L) {
                    val e = ByteArray(8); readExact(e, false)
                    len = 0
                    for (x in e) len = (len shl 8) or (x.toLong() and 0xFF)
                }
                if (len > 2_000_000L) throw Exception(str(R.string.the_frame_is_too_large_b, len))
                val mk = if ((h[1].toInt() and 0x80) != 0) ByteArray(4).also { readExact(it, false) } else null
                val p = ByteArray(len.toInt())
                readExact(p, false)
                if (mk != null) for (i in p.indices) p[i] = (p[i].toInt() xor mk[i % 4].toInt()).toByte()
                when (op) {
                    0, 1, 2 -> {
                        if (op != 0) { fragOp = op; frag.reset() }
                        frag.write(p)
                        if (fin) {
                            val data = frag.toByteArray()
                            received++
                            if (fragOp == 1) say("← " + String(data, Charsets.UTF_8).take(2000))
                            else say(str(R.string.binary_b, data.size) + data.take(48).joinToString(" ") { "%02x".format(it) })
                            frag.reset()
                        }
                    }
                    8 -> {
                        val c = if (p.size >= 2) ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF) else 0
                        say(str(R.string.the_server_closed_the_connection) + (if (c != 0) str(R.string.code, c) else ""))
                        serverClosed = true
                    }
                    9 -> { sendFrame(10, p); say(str(R.string.ping_answered_with_pong)) }
                    10 -> say("← pong")
                    else -> say(str(R.string.unknown_frame_opcode, op))
                }
            } catch (e: SocketTimeoutException) {
                // nečinnosť, pokračujeme v čakaní
            } catch (e: EOFException) {
                say(str(R.string.the_connection_was_closed_by_the_server))
                serverClosed = true
            }
        }
        if (!serverClosed) {
            say(if (received == 0) str(R.string.no_message_arrived_within_s, listenSec) else str(R.string.messages_received_closing_the_connection, received))
            runCatching { sendFrame(8, byteArrayOf(0x03, 0xE8.toByte())) }
        }
    } finally {
        runCatching { sock.close() }
    }
    return log.toString().trimEnd()
}

// ───────────────────────── GeoIP + reverzné DNS ─────────────────────────

// Krajina, ASN a organizácia sa zisťujú offline z databázy zabalenej v aplikácii (pozri Geo.kt).
suspend fun geoLookup(ctx: android.content.Context, input: String): String {
    val q0 = input.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').substringAfterLast('@')
    val q = if (q0.count { it == ':' } > 1) q0.trim('[', ']') else q0.substringBefore(':')
    if (q.isEmpty()) throw Exception(str(R.string.enter_an_ip_address_or_a_domain))
    val addrs = withContext(Dispatchers.IO) {
        try {
            InetAddress.getAllByName(q).distinctBy { it.hostAddress }
        } catch (e: Exception) {
            throw Exception(str(R.string.could_not_resolve_the_host_2))
        }
    }.take(4)
    val sb = StringBuilder()
    if (addrs.size != 1 || addrs[0].hostAddress != q) sb.append("$q → ${addrs.joinToString(", ") { it.hostAddress ?: "?" }}\n\n")
    for (a in addrs) {
        val ip = a.hostAddress ?: continue
        sb.append("═ $ip\n")
        val ptr = runCatching { dnsLookup(ip, "PTR") }.getOrNull()
        val names = ptr?.lines()?.filter { it.startsWith("PTR") }?.map { it.removePrefix("PTR").trim().substringBefore("   TTL").trim() }.orEmpty()
        sb.append(str(R.string.reverse_dns) + (if (names.isEmpty()) str(R.string.no_record) else names.joinToString(", ")) + "\n")
        val g = withContext(Dispatchers.IO) { runCatching { GeoDb.lookup(ctx, a) } }.getOrNull()
        if (g == null) {
            sb.append(str(R.string.geolocation_the_database_could_not_be_lo))
        } else if (a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isAnyLocalAddress) {
            sb.append(str(R.string.geolocation_private_or_local_address))
        } else {
            sb.append(str(R.string.country) + (g.country?.let { "$it (${g.countryCode})" } ?: str(R.string.unknown)) + "\n")
            g.asn?.let { sb.append("ASN: AS$it\n") }
            g.org?.let { sb.append(str(R.string.organization, it)) }
            if (g.countryCode == null && g.asn == null) sb.append(str(R.string.the_address_is_not_in_the_database))
        }
        sb.append("\n")
    }
    sb.append(str(R.string.data_ip_geolocation_by_db_ip_db_ip_com_o))
    return sb.toString().trimEnd()
}

// ───────────────────────── HTTP/2, HTTP/3 a verzie TLS ─────────────────────────

internal fun hostPort(input: String): Pair<String, Int> {
    val t = input.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringAfterLast('@')
    val host = t.substringBefore(':').ifEmpty { throw Exception(str(R.string.enter_a_host)) }
    val port = if (t.contains(':')) (t.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: throw Exception(str(R.string.invalid_port))) else 443
    return host to port
}

fun protoCheck(input: String): String {
    val (host, port) = hostPort(input)
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(TRUST_ALL), null) }
    fun open(): SSLSocket {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 6000)
        raw.soTimeout = 6000
        return ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
    }

    val tlsLines = StringBuilder()
    var best = ""
    for ((p, label) in listOf("TLSv1" to "TLS 1.0", "TLSv1.1" to "TLS 1.1", "TLSv1.2" to "TLS 1.2", "TLSv1.3" to "TLS 1.3")) {
        val legacy = p == "TLSv1" || p == "TLSv1.1"
        var s: SSLSocket? = null
        try {
            s = open()
            if (p !in s.supportedProtocols) {
                tlsLines.append(str(R.string.this_device_does_not_support_it_it_canno, label))
                continue
            }
            s.enabledProtocols = arrayOf(p)
            s.startHandshake()
            if (legacy) tlsLines.append(str(R.string.the_server_allows_it_deprecated_disablin, label))
            else tlsLines.append(str(R.string.supported, label, s.session.cipherSuite))
            best = label
        } catch (e: Exception) {
            if (s == null) throw Exception(str(R.string.connection_failed) + (e.message ?: e.javaClass.simpleName))
            tlsLines.append(
                when {
                    legacy -> str(R.string.disabled_good, label)
                    p == "TLSv1.3" -> str(R.string.the_server_does_not_support_it_enabling, label)
                    else -> str(R.string.the_server_did_not_accept_it, label)
                }
            )
        } finally {
            runCatching { s?.close() }
        }
    }

    val alpn = StringBuilder()
    if (Build.VERSION.SDK_INT >= 29) {
        var s: SSLSocket? = null
        try {
            s = open()
            val prm = s.sslParameters
            prm.applicationProtocols = arrayOf("h2", "http/1.1")
            s.sslParameters = prm
            s.startHandshake()
            when (val ap = s.applicationProtocol ?: "") {
                "h2" -> alpn.append(str(R.string.http_2_is_supported_alpn_h2))
                "http/1.1" -> alpn.append("! Server vyjednal len HTTP/1.1, HTTP/2 nepodporuje.\n")
                "" -> alpn.append(str(R.string.the_server_does_not_support_alpn_http_2))
                else -> alpn.append(str(R.string.negotiated_protocol, ap))
            }
        } catch (e: Exception) {
            alpn.append(str(R.string.the_alpn_test_failed, e.message ?: e.javaClass.simpleName))
        } finally {
            runCatching { s?.close() }
        }
    } else alpn.append(str(R.string.the_http_2_test_requires_android_10_or_n))

    val h3 = StringBuilder()
    val r = httpFetch("https://" + (if (port == 443) host else "$host:$port"), readBody = false)
    val alt = r.hv("alt-svc")
    if (r.error != null) h3.append(str(R.string.could_not_load_the_response, r.error))
    else if (alt != null && Regex("\\bh3(-\\d+)?\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(alt)) h3.append("✓ Server ohlasuje HTTP/3 (Alt-Svc: $alt)\n")
    else h3.append(str(R.string.the_server_does_not_announce_http_3_with) + (if (alt != null) " (Alt-Svc: $alt)" else "") + ".\n")
    h3.append(str(R.string.note_a_direct_quic_udp_test_is_not_possi))

    return str(R.string.highest_supported_tls_version, host, port, best.ifEmpty { str(R.string.not_detected) }) +
        "Verzie TLS\n$tlsLines\nHTTP/2\n$alpn\nHTTP/3\n$h3".trimEnd()
}

// ───────────────────────── JWT generátor (HMAC) ─────────────────────────

fun jwtMake(alg: String, header: String, payload: String, secret: String, addIat: Boolean, expSec: Long?): String {
    val macName = when (alg) {
        "HS256" -> "HmacSHA256"
        "HS384" -> "HmacSHA384"
        "HS512" -> "HmacSHA512"
        else -> throw Exception(str(R.string.unsupported_algorithm))
    }
    if (secret.isEmpty()) throw Exception(str(R.string.enter_the_secret_key))
    val h = try { JSONObject(header.ifBlank { "{}" }) } catch (e: Exception) { throw Exception(str(R.string.the_header_is_not_a_valid_json_object)) }
    h.put("alg", alg)
    if (!h.has("typ")) h.put("typ", "JWT")
    val p = try { JSONObject(payload.ifBlank { "{}" }) } catch (e: Exception) { throw Exception(str(R.string.the_payload_is_not_a_valid_json_object)) }
    val now = System.currentTimeMillis() / 1000
    if (addIat) p.put("iat", now)
    if (expSec != null) p.put("exp", now + expSec)
    val enc = Base64.getUrlEncoder().withoutPadding()
    val input = enc.encodeToString(h.toString().toByteArray()) + "." + enc.encodeToString(p.toString().toByteArray())
    val mac = Mac.getInstance(macName)
    mac.init(SecretKeySpec(secret.toByteArray(), macName))
    return input + "." + enc.encodeToString(mac.doFinal(input.toByteArray()))
}

// ───────────────────────── Formátovač XML / HTML ─────────────────────────

private val MARKUP_TOK = Regex(
    "<!--[\\s\\S]*?-->|<!\\[CDATA\\[[\\s\\S]*?]]>|<script\\b[^>]*>[\\s\\S]*?</script\\s*>|<style\\b[^>]*>[\\s\\S]*?</style\\s*>|<pre\\b[^>]*>[\\s\\S]*?</pre\\s*>|<[^>]+>|[^<]+",
    RegexOption.IGNORE_CASE
)
private val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
private val RAW_BLOCK_OPEN = Regex("^<(script|style|pre)\\b", RegexOption.IGNORE_CASE)
private val WS = Regex("\\s+")

private fun tagName(t: String) = Regex("^</?\\s*([A-Za-z][\\w:-]*)").find(t)?.groupValues?.get(1)?.lowercase() ?: ""

fun formatMarkup(src: String, html: Boolean): String {
    val toks = MARKUP_TOK.findAll(src.trim()).map { it.value }
        .map { if (it.startsWith("<")) it else it.replace(WS, " ").trim() }
        .filter { it.isNotEmpty() }.toList()
    val sb = StringBuilder()
    var ind = 0
    fun line(s: String) {
        if (sb.isNotEmpty()) sb.append('\n')
        repeat(ind) { sb.append("  ") }
        sb.append(s)
    }
    var i = 0
    while (i < toks.size) {
        val t = toks[i]
        when {
            !t.startsWith("<") -> line(t)
            t.startsWith("</") -> { ind = maxOf(0, ind - 1); line(t) }
            t.startsWith("<!--") || t.startsWith("<![CDATA[") || t.startsWith("<?") || t.startsWith("<!") || t.endsWith("/>") -> line(t)
            html && tagName(t) in VOID_TAGS -> line(t)
            RAW_BLOCK_OPEN.containsMatchIn(t) && t.contains("</") -> line(t)
            else -> {
                val n = tagName(t)
                val a = toks.getOrNull(i + 1)
                val b = toks.getOrNull(i + 2)
                if (a != null && !a.startsWith("<") && b != null && b.startsWith("</") && tagName(b) == n) { line(t + a + b); i += 2 }
                else if (a != null && a.startsWith("</") && tagName(a) == n) { line(t + a); i += 1 }
                else { line(t); ind++ }
            }
        }
        i++
    }
    return sb.toString()
}

fun minifyMarkup(src: String): String {
    val out = StringBuilder()
    MARKUP_TOK.findAll(src).forEach { m ->
        val t = m.value
        if (t.startsWith("<!--")) {
            if (t.startsWith("<!--[if")) out.append(t)
        } else if (t.startsWith("<")) {
            if (RAW_BLOCK_OPEN.containsMatchIn(t) && t.contains("</") && !t.startsWith("</")) out.append(t) else out.append(t.replace(WS, " "))
        } else {
            val c = t.replace(WS, " ")
            if (c.isBlank()) { if (!t.contains('\n')) out.append(' ') } else out.append(c)
        }
    }
    return out.toString().trim()
}

// ───────────────────────── Formátovač SQL ─────────────────────────

private val SQL_TOK = Regex(
    "'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|`[^`]*`|--[^\\n]*|/\\*[\\s\\S]*?\\*/|[A-Za-z_@#][A-Za-z0-9_\$@#]*(?:\\.[A-Za-z_*][A-Za-z0-9_\$]*)*|\\d+(?:\\.\\d+)?|<>|<=|>=|!=|\\|\\||::|[(),;.*=<>+\\-/%]|\\S"
)

private val SQL_KW = setOf(
    "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN", "EXISTS", "AS", "ON", "JOIN",
    "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "ALL",
    "DISTINCT", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "ALTER", "DROP", "INDEX", "PRIMARY",
    "KEY", "FOREIGN", "REFERENCES", "DEFAULT", "CASE", "WHEN", "THEN", "ELSE", "END", "ASC", "DESC", "WITH", "USING",
    "RETURNING", "TOP", "TRUE", "FALSE", "UNIQUE", "CONSTRAINT", "VIEW", "IF", "CASCADE"
)

private val SQL_CLAUSES = listOf(
    listOf("LEFT", "OUTER", "JOIN"), listOf("RIGHT", "OUTER", "JOIN"), listOf("FULL", "OUTER", "JOIN"), listOf("UNION", "ALL"),
    listOf("INSERT", "INTO"), listOf("DELETE", "FROM"), listOf("GROUP", "BY"), listOf("ORDER", "BY"), listOf("LEFT", "JOIN"),
    listOf("RIGHT", "JOIN"), listOf("INNER", "JOIN"), listOf("FULL", "JOIN"), listOf("CROSS", "JOIN"), listOf("JOIN"),
    listOf("SELECT"), listOf("FROM"), listOf("WHERE"), listOf("HAVING"), listOf("LIMIT"), listOf("OFFSET"), listOf("VALUES"),
    listOf("UPDATE"), listOf("SET"), listOf("UNION"), listOf("WITH"), listOf("RETURNING")
)

fun formatSql(src: String): String {
    val toks = SQL_TOK.findAll(src).map { it.value }.toList()
    val sb = StringBuilder()
    var ind = 0
    var atLineStart = true
    var glue = false
    var between = false
    var prev = ""
    var prev2 = ""
    val parens = ArrayList<Boolean>()

    fun nl(level: Int) {
        while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
        if (!atLineStart) sb.append('\n')
        repeat(level) { sb.append("  ") }
        atLineStart = true
        glue = false
    }
    fun put(s: String, space: Boolean = true) {
        if (!atLineStart && space && !glue && sb.isNotEmpty()) sb.append(' ')
        sb.append(s)
        atLineStart = false
        glue = false
    }
    fun clauseLen(i: Int): Int {
        for (c in SQL_CLAUSES) {
            if (i + c.size <= toks.size && c.indices.all { toks[i + it].uppercase() == c[it] }) return c.size
        }
        return 0
    }

    var i = 0
    while (i < toks.size) {
        val raw = toks[i]
        val up = raw.uppercase()
        var consumed = 1
        when {
            raw.startsWith("--") -> { put(raw); nl(ind) }
            raw.startsWith("/*") -> put(raw)
            raw == ";" -> { put(";", false); nl(0); sb.append('\n'); ind = 0; parens.clear(); between = false }
            raw == "(" -> {
                val sub = toks.getOrNull(i + 1)?.uppercase().let { it == "SELECT" || it == "WITH" }
                val fn = prev.isNotEmpty() && (prev.last().isLetterOrDigit() || prev.last() == '_') && prev.uppercase() !in SQL_KW &&
                    prev2.uppercase() !in setOf("INTO", "TABLE", "VIEW", "INDEX", "ON")
                put("(", !fn)
                parens.add(sub)
                if (sub) { ind++; nl(ind) } else glue = true
            }
            raw == ")" -> {
                val sub = if (parens.isNotEmpty()) parens.removeAt(parens.lastIndex) else false
                if (sub) { ind = maxOf(0, ind - 1); nl(ind) }
                put(")", false)
            }
            raw == "," -> {
                put(",", false)
                if (parens.isEmpty() || parens.last()) nl(ind + 1)
            }
            raw == "::" || raw == "." -> { put(raw, false); glue = true }
            else -> {
                val cl = clauseLen(i)
                if (cl > 0) {
                    nl(ind)
                    put(toks.subList(i, i + cl).joinToString(" ") { it.uppercase() })
                    consumed = cl
                    between = false
                } else if (up == "BETWEEN") { between = true; put(up) }
                else if (up == "AND" || up == "OR") {
                    if (up == "AND" && between) { between = false; put(up) } else { nl(ind + 1); put(up) }
                } else put(if (up in SQL_KW && raw.all { it.isLetter() || it == '_' }) up else raw)
            }
        }
        prev2 = prev
        prev = toks[i + consumed - 1]
        i += consumed
    }
    return sb.toString().trim()
}

fun minifySql(src: String): String {
    val sb = StringBuilder()
    var prev = ""
    for (t in SQL_TOK.findAll(src).map { it.value }) {
        if (t.startsWith("--") || t.startsWith("/*")) continue
        val fn = t == "(" && prev.isNotEmpty() && (prev.last().isLetterOrDigit() || prev.last() == '_') && prev.uppercase() !in SQL_KW
        val tight = sb.isEmpty() || t in setOf(",", ";", ")", ".", "::") || prev in setOf("(", ".", "::") || fn
        if (!tight) sb.append(' ')
        sb.append(t)
        prev = t
    }
    return sb.toString()
}

// ───────────────────────── Návrhy nástrojov pre zdieľaný text ─────────────────────────

fun suggestFor(text: String): List<String> {
    val s = text.trim()
    val out = LinkedHashSet<String>()
    val domain = Regex("^(https?://)?([A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(:\\d+)?(/\\S*)?$", RegexOption.IGNORE_CASE)
    when {
        s.startsWith("curl ", true) -> out.addAll(listOf("curl", "req", "redir"))
        s.startsWith("ws://", true) || s.startsWith("wss://", true) -> out.addAll(listOf("ws", "req", "ssl"))
        Regex("^[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*$").matches(s) -> out.addAll(listOf("jwt", "jwks", "b64"))
        Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(s) || (s.count { it == ':' } >= 2 && Regex("^[0-9A-Fa-f:]+$").matches(s)) ->
            out.addAll(listOf("geo", "dns", "ping", "portchk"))
        domain.matches(s) -> out.addAll(listOf("req", "redir", "sechdr", "ssl", "dns", "cache", "cors", "proto", "pgan", "geo", "ws"))
        (s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]")) ->
            out.addAll(listOf("json", "jpath", "tsgen", "yaml", "csv", "jwtgen"))
        s.startsWith("<") && s.endsWith(">") -> out.addAll(listOf("fmt", "ent", "b64"))
        Regex("^\\d{10}(\\d{3})?$").matches(s) -> out.addAll(listOf("ts", "base", "dd"))
        Regex("^#?[0-9A-Fa-f]{6}$").matches(s) -> out.addAll(listOf("col", "ctr", "grad"))
        Regex("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$").matches(s) -> out.addAll(listOf("uuid", "hash"))
        Regex("^(select|insert|update|delete|with|create)\\s", RegexOption.IGNORE_CASE).containsMatchIn(s) -> out.add("fmt")
    }
    out.addAll(listOf("b64", "url", "hash", "tstat", "case", "qr", "fmt", "diff"))
    return out.filter { id -> TOOLS.any { it.id == id } }.take(8)
}
