// SPDX-License-Identifier: GPL-3.0-or-later
package sk.devtools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

suspend fun http(url: String, method: String = "GET", body: String? = null): String = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = method
    c.connectTimeout = 15000
    c.readTimeout = 30000
    if (body != null) {
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
    }
    val s = if (c.responseCode < 400) c.inputStream else (c.errorStream ?: c.inputStream)
    s.bufferedReader().use { it.readText() }
}

class Hop(
    val n: Int,
    val url: String,
    val status: Int,
    val text: String,
    val ms: Long,
    val location: String?,
    val error: String?
)

private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$""")

internal fun normalizeUrl(s: String): String {
    val t = s.trim()
    if (t.contains("://")) return t
    return if (IPV4.matches(t)) "http://$t" else "https://$t"
}

// Natívne sledovanie presmerovaní: každý krok vybavíme zvlášť, aby bolo vidieť celý reťazec.
suspend fun traceRequest(
    input: String, method: String, maxHops: Int = 10,
    headers: List<Pair<String, String>> = emptyList(), body: String? = null,
    basic: Pair<String, String>? = null, bearer: String? = null
): List<Hop> = withContext(Dispatchers.IO) {
    val hops = mutableListOf<Hop>()
    var url = normalizeUrl(input)
    val origin = runCatching { URL(url).let { it.protocol + "://" + it.host + ":" + (if (it.port < 0) it.defaultPort else it.port) } }.getOrNull()
    var m = method
    var b = body
    for (i in 1..maxHops) {
        var c: HttpURLConnection? = null
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = m
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "DevTools/1.0")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            // Prihlasovacie údaje posielame len na pôvodný server, nikdy nie na iný hostiteľ po presmerovaní.
            val same = runCatching { URL(url).let { it.protocol + "://" + it.host + ":" + (if (it.port < 0) it.defaultPort else it.port) } }.getOrNull() == origin
            if (same) {
                if (basic != null) c.setRequestProperty("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("${basic.first}:${basic.second}".toByteArray()))
                else if (!bearer.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer " + bearer.trim())
            }
            if (m == "POST") {
                c.doOutput = true
                val bytes = (b ?: "").toByteArray()
                c.setFixedLengthStreamingMode(bytes.size)
                if (bytes.isNotEmpty()) c.outputStream.use { it.write(bytes) }
            }
            val t0 = System.nanoTime()
            val code = c.responseCode
            val ms = (System.nanoTime() - t0) / 1_000_000
            val loc = c.getHeaderField("Location")
            hops += Hop(i, url, code, c.responseMessage ?: "", ms, loc, null)
            if (code in intArrayOf(301, 302, 303, 307, 308) && !loc.isNullOrBlank()) {
                url = URL(URL(url), loc).toString()
                if (m == "POST" && code != 307 && code != 308) { m = "GET"; b = null }
            } else break
        } catch (e: Exception) {
            hops += Hop(i, url, 0, "", 0, null, e.message ?: e.javaClass.simpleName)
            break
        } finally {
            c?.disconnect()
        }
        if (i == maxHops) hops += Hop(i + 1, url, 0, "", 0, null, str(R.string.too_many_redirects_limit, maxHops))
    }
    hops
}

fun List<Hop>.render(): String = joinToString("\n\n") { h ->
    val st = if (h.status > 0) "${h.status} ${h.text}".trim() else str(R.string.error_4)
    val ms = if (h.status > 0) "  ·  ${h.ms} ms" else ""
    var t = "${h.n}. $st$ms\n${h.url}"
    if (!h.location.isNullOrBlank()) t += "\n→ ${h.location}"
    if (!h.error.isNullOrBlank()) t += "\n${h.error}"
    t
}
