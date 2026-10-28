// SPDX-License-Identifier: GPL-3.0-or-later
package sk.devtools

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

// Natívny DNS klient: vlastné skladanie a parsovanie DNS paketov, žiadny sprostredkovateľ.
private val TYPES = mapOf(
    "A" to 1, "NS" to 2, "CNAME" to 5, "SOA" to 6, "PTR" to 12, "MX" to 15,
    "TXT" to 16, "AAAA" to 28, "SRV" to 33, "CAA" to 257
)

private class Rec(val type: String, val ttl: Long, val data: String)
private class Reply(val rcode: Int, val answers: List<Rec>)

private fun buildQuery(name: String, type: Int): ByteArray {
    val o = ByteArrayOutputStream()
    val id = Random.nextInt(65536)
    o.write(id shr 8); o.write(id and 255)
    o.write(0x01); o.write(0x00)                  // flags: RD
    o.write(0); o.write(1)                        // QDCOUNT = 1
    repeat(6) { o.write(0) }                      // AN, NS, AR = 0
    for (label in IDN.toASCII(name.trimEnd('.')).split('.')) {
        val b = label.toByteArray()
        require(b.size in 1..63) { str(R.string.invalid_domain_name) }
        o.write(b.size); o.write(b)
    }
    o.write(0)
    o.write(type shr 8); o.write(type and 255)
    o.write(0); o.write(1)                        // CLASS IN
    return o.toByteArray()
}

private suspend fun exchange(q: ByteArray): ByteArray {
    if (Build.VERSION.SDK_INT >= 29) {
        try { return systemQuery(q) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* záloha nižšie */ }
    }
    return withContext(Dispatchers.IO) { udpOrTcp(q) }
}

@TargetApi(29)
private suspend fun systemQuery(q: ByteArray): ByteArray = suspendCancellableCoroutine { c ->
    val sig = CancellationSignal()
    c.invokeOnCancellation { sig.cancel() }
    DnsResolver.getInstance().rawQuery(
        null, q, DnsResolver.FLAG_EMPTY, Executor { it.run() }, sig,
        object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) { c.resume(answer) }
            override fun onError(error: DnsResolver.DnsException) { c.resumeWithException(error) }
        }
    )
}

// DNS servery aktívnej siete, tak ako ich nastavil systém (DHCP, Wi-Fi alebo ručne).
private fun systemDnsServers(): List<InetAddress> {
    val cm = AppCtx.app.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    val n = cm.activeNetwork ?: return emptyList()
    return cm.getLinkProperties(n)?.dnsServers.orEmpty()
}

private fun udpOrTcp(q: ByteArray): ByteArray {
    var last: Exception? = null
    val servers = systemDnsServers()
    if (servers.isEmpty()) throw Exception(str(R.string.could_not_determine_the_system_dns_serve))
    for (a in servers) {
        try {
            val r = DatagramSocket().use { s ->
                s.soTimeout = 4000
                s.send(DatagramPacket(q, q.size, a, 53))
                val buf = ByteArray(4096)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                buf.copyOf(p.length)
            }
            return if (r.size > 3 && (r[2].toInt() and 0x02) != 0) tcp(q, a) else r   // TC bit -> TCP
        } catch (e: Exception) { last = e }
    }
    throw Exception(str(R.string.dns_server_is_unavailable, last?.message ?: str(R.string.unknown_error)))
}

private fun tcp(q: ByteArray, a: InetAddress): ByteArray = Socket().use { s ->
    s.connect(InetSocketAddress(a, 53), 4000)
    s.soTimeout = 5000
    val o = s.getOutputStream()
    o.write(q.size shr 8); o.write(q.size and 255); o.write(q); o.flush()
    val i = DataInputStream(s.getInputStream())
    val b = ByteArray(i.readUnsignedShort())
    i.readFully(b)
    b
}

private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255)
private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()

private fun readName(b: ByteArray, start: Int): Pair<String, Int> {
    val sb = StringBuilder()
    var o = start
    var end = -1
    var jumps = 0
    while (true) {
        val l = b[o].toInt() and 255
        if (l == 0) { if (end < 0) end = o + 1; break }
        if ((l and 0xC0) == 0xC0) {
            if (end < 0) end = o + 2
            o = ((l and 0x3F) shl 8) or (b[o + 1].toInt() and 255)
            if (++jumps > 20) error(str(R.string.invalid_dns_response))
        } else {
            sb.append(String(b, o + 1, l, Charsets.UTF_8)).append('.')
            o += l + 1
        }
    }
    return (if (sb.isEmpty()) "." else sb.toString()) to end
}

private fun ip6(b: ByteArray): String {
    val w = IntArray(8) { u16(b, it * 2) }
    var bs = -1; var bl = 0; var i = 0
    while (i < 8) {
        if (w[i] == 0) {
            var j = i
            while (j < 8 && w[j] == 0) j++
            if (j - i > bl) { bs = i; bl = j - i }
            i = j
        } else i++
    }
    if (bl < 2) return w.joinToString(":") { it.toString(16) }
    return w.take(bs).joinToString(":") { it.toString(16) } + "::" +
        w.drop(bs + bl).joinToString(":") { it.toString(16) }
}

private fun rdata(b: ByteArray, t: Int, d: Int, len: Int): String = when (t) {
    1 -> (0 until 4).joinToString(".") { (b[d + it].toInt() and 255).toString() }
    28 -> ip6(b.copyOfRange(d, d + 16))
    2, 5, 12 -> readName(b, d).first
    15 -> "${u16(b, d)} ${readName(b, d + 2).first}"
    16 -> {
        val sb = StringBuilder()
        var o = d
        while (o < d + len) {
            val l = b[o].toInt() and 255
            sb.append(String(b, o + 1, l, Charsets.UTF_8))
            o += l + 1
        }
        "\"$sb\""
    }
    6 -> {
        val (m, o1) = readName(b, d)
        val (r, o2) = readName(b, o1)
        "$m $r ${u32(b, o2)} ${u32(b, o2 + 4)} ${u32(b, o2 + 8)} ${u32(b, o2 + 12)} ${u32(b, o2 + 16)}"
    }
    33 -> "${u16(b, d)} ${u16(b, d + 2)} ${u16(b, d + 4)} ${readName(b, d + 6).first}"
    257 -> {
        val flags = b[d].toInt() and 255
        val tl = b[d + 1].toInt() and 255
        "$flags ${String(b, d + 2, tl, Charsets.UTF_8)} \"${String(b, d + 2 + tl, len - 2 - tl, Charsets.UTF_8)}\""
    }
    else -> "($len B)"
}

private fun parse(b: ByteArray): Reply {
    val rcode = b[3].toInt() and 15
    val qd = u16(b, 4)
    val an = u16(b, 6)
    var o = 12
    repeat(qd) { o = readName(b, o).second + 4 }
    val out = mutableListOf<Rec>()
    repeat(an) {
        o = readName(b, o).second
        val t = u16(b, o)
        val ttl = u32(b, o + 4)
        val len = u16(b, o + 8)
        val d = o + 10
        o = d + len
        val tn = TYPES.entries.firstOrNull { it.value == t }?.key ?: "TYPE$t"
        out += Rec(tn, ttl, rdata(b, t, d, len))
    }
    return Reply(rcode, out)
}

private val V4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

private fun reverseName(s: String): String? {
    V4.matchEntire(s)?.let { m -> return m.groupValues.drop(1).reversed().joinToString(".") + ".in-addr.arpa" }
    if (s.contains(':')) {
        val b = runCatching { InetAddress.getByName(s).address }.getOrNull() ?: return null
        if (b.size == 16) {
            return b.reversed().joinToString(".") { "%x.%x".format(it.toInt() and 15, (it.toInt() shr 4) and 15) } + ".ip6.arpa"
        }
    }
    return null
}

suspend fun dnsLookup(input: String, type: String): String {
    val name = input.trim()
    val reverse = reverseName(name)
    val plan: List<Pair<String, String>> = when {
        reverse != null -> listOf("PTR" to reverse)
        type == "ALL" -> listOf("A", "AAAA", "CNAME", "MX", "NS", "TXT", "SOA", "CAA").map { it to name }
        else -> listOf(type to name)
    }
    val records = mutableListOf<Rec>()
    var rcode = 0
    var firstError: Exception? = null
    var ok = 0
    for ((t, n) in plan) {
        try {
            val r = parse(exchange(buildQuery(n, TYPES.getValue(t))))
            ok++
            if (r.rcode != 0) rcode = r.rcode
            records += r.answers
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (firstError == null) firstError = e }
    }
    if (ok == 0) throw Exception(firstError?.message ?: str(R.string.the_dns_query_failed))
    if (rcode == 3) return str(R.string.domain_does_not_exist_nxdomain)
    if (records.isEmpty()) return if (rcode == 2) str(R.string.dns_server_returned_an_error_servfail) else str(R.string.no_records)
    val head = if (reverse != null) str(R.string.reverse_query_ptr_for, name) else ""
    return head + records.distinctBy { it.type + it.data }
        .sortedBy { TYPES[it.type] ?: 999 }
        .joinToString("\n") { "${it.type.padEnd(6)} ${it.data}   TTL ${it.ttl}" }
}

// Surové dáta záznamov daného typu (TXT bez úvodzoviek). Prázdny zoznam, ak záznam neexistuje.
suspend fun dnsData(name: String, type: String): List<String> {
    val r = parse(exchange(buildQuery(name, TYPES.getValue(type))))
    return r.answers.filter { it.type == type }.map { if (type == "TXT") it.data.removeSurrounding("\"") else it.data }
}
