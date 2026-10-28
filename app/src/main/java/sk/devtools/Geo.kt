// SPDX-License-Identifier: GPL-3.0-or-later
package sk.devtools

import android.content.Context
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.Locale

// Offline GeoIP: databázy „IP to Country Lite“ a „IP to ASN Lite“ od DB-IP.com (CC BY 4.0) sú súčasťou aplikácie
// v assets/geoip/. Žiadna IP adresa neopúšťa zariadenie.
//   country4.bin / country6.bin: záznamy [začiatok rozsahu][kód krajiny, 2 bajty], 00 = bez údaja
//   asn4.bin / asn6.bin:         záznamy [začiatok rozsahu][ASN, 4 bajty], 0 = bez údaja
//   Začiatok je u32 (IPv4) alebo horných 64 bitov (IPv6). Rozsah trvá do začiatku ďalšieho záznamu.
class GeoResult(val countryCode: String?, val asn: Long?, val org: String?) {
    val country: String?
        get() = countryCode?.let { cc -> Locale("", cc).getDisplayCountry(Locale.getDefault()).ifEmpty { cc } }
}

object GeoDb {
    private class Tab(val d: ByteArray, val keyLen: Int, val valLen: Int) {
        private val rec = keyLen + valLen
        private val n = d.size / rec

        private fun key(i: Int): Long {
            var k = 0L
            val o = i * rec
            for (j in 0 until keyLen) k = (k shl 8) or (d[o + j].toLong() and 0xFF)
            return k
        }

        // Index posledného záznamu s kľúčom <= key (bez znamienka), alebo -1.
        fun find(key: Long): Int {
            var lo = 0
            var hi = n - 1
            var res = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (java.lang.Long.compareUnsigned(key(mid), key) <= 0) { res = mid; lo = mid + 1 } else hi = mid - 1
            }
            return res
        }

        fun value(i: Int): Long {
            var v = 0L
            val o = i * rec + keyLen
            for (j in 0 until valLen) v = (v shl 8) or (d[o + j].toLong() and 0xFF)
            return v
        }
    }

    private val tabs = HashMap<String, Tab>()
    private var names: HashMap<Long, String>? = null

    @Synchronized
    private fun tab(ctx: Context, file: String, keyLen: Int, valLen: Int): Tab =
        tabs.getOrPut(file) { Tab(ctx.assets.open("geoip/$file.bin").use { it.readBytes() }, keyLen, valLen) }

    @Synchronized
    private fun orgName(ctx: Context, asn: Long): String? {
        val m = names ?: HashMap<Long, String>().also { map ->
            ctx.assets.open("geoip/asn_names.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (l in lines) {
                    val t = l.indexOf('\t')
                    if (t > 0) l.substring(0, t).toLongOrNull()?.let { map[it] = l.substring(t + 1) }
                }
            }
            names = map
        }
        return m[asn]
    }

    fun lookup(ctx: Context, a: InetAddress): GeoResult {
        val v6 = a !is Inet4Address
        val b = a.address
        val key = if (v6) ByteBuffer.wrap(b, 0, 8).long else ByteBuffer.wrap(b).int.toLong() and 0xFFFFFFFFL
        val kl = if (v6) 8 else 4
        val sfx = if (v6) "6" else "4"

        val c = tab(ctx, "country$sfx", kl, 2)
        val ci = c.find(key)
        val cv = if (ci >= 0) c.value(ci) else 0L
        val cc = if (cv == 0L) null else "" + (cv shr 8).toInt().toChar() + (cv and 255).toInt().toChar()

        val t = tab(ctx, "asn$sfx", kl, 4)
        val ti = t.find(key)
        val asn = if (ti >= 0) t.value(ti).takeIf { it != 0L } else null
        return GeoResult(cc, asn, asn?.let { orgName(ctx, it) })
    }
}
