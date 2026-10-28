// SPDX-License-Identifier: GPL-3.0-or-later
package sk.devtools

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

// Lokálny most medzi DevTools frontendom a debug socketom vlastného WebView.
//  - cesty /json a /devtools/page: preposielajú sa na abstraktný unix socket "webview_devtools_remote_<pid>"
//    (hlavička Origin sa odstráni, inak ju Chromium pri websockete odmietne)
//  - cesta /front_end/: servíruje sa z assets/devtools/ (frontend je súčasťou aplikácie, nič sa nesťahuje).
//    Dôvod servírovania z 127.0.0.1: frontend z https origin by nemohol otvoriť websocket na 127.0.0.1
//    (Private/Local Network Access v Chromiu to blokuje). Z 127.0.0.1 ide o same-origin, takže to prejde.
private const val FIXED_PORT = 39517

class DevToolsServer(private val ctx: Context) {
    // Pevný port: nastavenia DevTools sú v localStorage, ktorý je viazaný na origin (vrátane portu).
    // Náhodný port by pri každom spustení znamenal nový origin, a teda stratené nastavenia.
    private val server = bind()
    @Volatile private var running = true
    @Volatile var lastError: String? = null
    val port: Int get() = server.localPort

    // Existuje v assets/devtools/ zbalený frontend (inspector.html)?
    val hasLocalFrontend: Boolean =
        runCatching { ctx.assets.list("devtools")?.contains("inspector.html") == true }.getOrDefault(false)

    init {
        thread(isDaemon = true, name = "dt-accept") {
            while (running) {
                try {
                    val c = server.accept()
                    thread(isDaemon = true, name = "dt-conn") { handle(c) }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }
    }

    private fun bind(): ServerSocket {
        val lo = InetAddress.getByName("127.0.0.1")
        // Po zatvorení (napr. otočenie obrazovky) môže byť port chvíľu obsadený – skús to znova.
        repeat(10) {
            try {
                return ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(lo, FIXED_PORT), 50) }
            } catch (_: Exception) {
                Thread.sleep(150)
            }
        }
        return ServerSocket(0, 50, lo) // záloha: náhodný port (nastavenia DevTools sa potom neuložia)
    }

    fun stop() {
        running = false
        runCatching { server.close() }
    }

    private fun handle(client: Socket) {
        try {
            val input = client.getInputStream().buffered()
            val head = readHead(input) ?: return
            val path = head.substringBefore("\r\n").split(' ').getOrNull(1) ?: "/"
            if (path.startsWith("/json") || path.startsWith("/devtools/page") || path.startsWith("/devtools/browser")) {
                proxy(head, input, client)
            } else {
                serveAsset(path, client.getOutputStream())
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readHead(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        var w = 0
        while (b.size() < 16384) {
            val x = i.read()
            if (x < 0) return null
            b.write(x)
            w = (w shl 8) or x
            if (w == 0x0D0A0D0A) return String(b.toByteArray(), Charsets.ISO_8859_1)
        }
        return null
    }

    private fun proxy(head: String, clientIn: InputStream, client: Socket) {
        val ls = LocalSocket()
        try {
            ls.connect(LocalSocketAddress("webview_devtools_remote_${Process.myPid()}", LocalSocketAddress.Namespace.ABSTRACT))
            val lines = head.trimEnd().split("\r\n")
            val upgrade = lines.any { it.startsWith("Upgrade:", ignoreCase = true) }
            val out = StringBuilder(lines[0]).append("\r\n")
            for (l in lines.drop(1)) {
                if (l.isBlank()) continue
                val k = l.substringBefore(':').trim().lowercase()
                if (k == "origin") continue
                if (!upgrade && k == "connection") continue
                out.append(l).append("\r\n")
            }
            if (!upgrade) out.append("Connection: close\r\n")
            out.append("\r\n")
            val so = ls.outputStream
            so.write(out.toString().toByteArray(Charsets.ISO_8859_1))
            so.flush()
            thread(isDaemon = true, name = "dt-down") {
                pipe(ls.inputStream, client.getOutputStream())
                runCatching { client.close() }
                runCatching { ls.close() }
            }
            pipe(clientIn, so)
        } finally {
            runCatching { ls.close() }
        }
    }

    private fun pipe(i: InputStream, o: OutputStream) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                o.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun serveAsset(path: String, o: OutputStream) {
        val rel = path.substringBefore('?').substringBefore('#').removePrefix("/front_end/").ifEmpty { "inspector.html" }
        if (!path.startsWith("/front_end/") || rel.contains("..") || rel.endsWith("/")) { respond(o, 404, "text/plain", "Not found".toByteArray()); return }
        val bytes = try {
            ctx.assets.open("devtools/$rel").use { it.readBytes() }
        } catch (e: Exception) {
            lastError = str(R.string.missing_2, rel, e.message ?: e.javaClass.simpleName)
            runCatching { respond(o, 404, "text/plain", "Not found".toByteArray()) }
            return
        }
        // Zápis môže zlyhať, ak WebView požiadavku medzitým zrušil (napr. pri opätovnom načítaní) – to nie je chyba súboru.
        runCatching { respond(o, 200, mime(rel), bytes) }
    }

    private fun mime(n: String) = when (n.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "wasm" -> "application/wasm"
        "avif" -> "image/avif"
        "ico" -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private fun respond(o: OutputStream, code: Int, type: String, body: ByteArray) {
        val h = "HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        o.write(h.toByteArray(Charsets.ISO_8859_1))
        o.write(body)
        o.flush()
    }
}
