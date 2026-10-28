// SPDX-License-Identifier: GPL-3.0-or-later
package sk.devtools

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

// Koreňový element DevTools frontendu mal v tomto WebView výšku 0 (html/body bez výšky), preto sa nič nevykreslilo.
private const val DT_LAYOUT_FIX = "(function(){var d=document,h=d.documentElement,b=d.body;if(!b)return -2;function f(e,o){for(var k in o)e.style.setProperty(k,o[k],'important')}function a(){var H=innerHeight+'px',W=innerWidth+'px';f(h,{height:H,width:W});f(b,{height:H,width:W,margin:'0',overflow:'hidden'});var r=b.querySelector(':scope > div');if(r)f(r,{position:'absolute',top:'0',left:'0',width:W,height:H});return r?Math.round(r.getBoundingClientRect().height):-1}if(!window.__fx){window.__fx=1;addEventListener('resize',a)}return a()})()"

// Zisťuje, či sa DevTools frontend už skutočne vykreslil.
private const val DT_PROBE = "(function(){var b=document.body;return 'text='+(b?b.innerText.length:-1)+' tab='+document.querySelectorAll('.tabbed-pane,devtools-tabbed-pane,[role=tab]').length})()"

// Umožní prehliadaču otvoriť bočné menu (App() ho poskytuje).
val LocalOpenDrawer = staticCompositionLocalOf<() -> Unit> { {} }

private fun toUrl(s: String): String {
    val t = s.trim()
    return when {
        t.contains("://") -> t
        !t.contains(' ') && (t.contains('.') || t.contains(':') || t == "localhost") -> normalizeUrl(t)
        else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
    }
}

private fun shortUrl(u: String) = u.removePrefix("https://").removePrefix("http://").removePrefix("www.").removeSuffix("/")

// Nájde ladený cieľ (stránku v prehliadači) a zloží adresu DevTools frontendu: lokálny z assets/devtools.
private suspend fun frontendUrl(server: DevToolsServer, pageUrl: String): String = withContext(Dispatchers.IO) {
    val base = "http://127.0.0.1:${server.port}"
    val ver = try {
        JSONObject(http("$base/json/version"))
    } catch (e: Exception) {
        throw Exception(str(R.string.webview_debug_socket_is_not_responding, server.lastError ?: e.message ?: e.javaClass.simpleName))
    }
    val arr = JSONArray(http("$base/json/list"))
    val pages = (0 until arr.length()).map { arr.getJSONObject(it) }.filter {
        val u = it.optString("url")
        it.optString("type") == "page" && u != "about:blank" && !u.contains("127.0.0.1:${server.port}") && !u.contains("chrome-devtools-frontend")
    }
    val t = pages.firstOrNull { it.optString("url") == pageUrl } ?: pages.firstOrNull()
    ?: error(str(R.string.the_page_to_debug_was_not_found_in_the_t))
    val id = t.getString("id")
    if (!server.hasLocalFrontend) error(str(R.string.devtools_frontend_is_missing_in_assets_d))
    "$base/front_end/inspector.html?ws=127.0.0.1:${server.port}/devtools/page/$id"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserTool() {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val openDrawer = LocalOpenDrawer.current
    val density = LocalDensity.current

    var addr by rememberSaveable { mutableStateOf("") }
    var started by rememberSaveable { mutableStateOf(false) }
    var dtOpen by rememberSaveable { mutableStateOf(false) }
    var dtError by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var tfv by remember { mutableStateOf(TextFieldValue(shortUrl(addr))) }
    val loading = started && progress in 1..99

    var server by remember { mutableStateOf<DevToolsServer?>(null) }
    var dtReady by remember { mutableStateOf(false) }
    var dtRetry by remember { mutableIntStateOf(0) }
    val dtGen = remember { intArrayOf(0) }

    // Podiel výšky, ktorý patrí stránke (zvyšok patrí DevTools); ťahá sa úchytom medzi nimi.
    var split by rememberSaveable { mutableStateOf(0.5f) }
    var areaH by remember { mutableIntStateOf(0) }
    val handleH = 28.dp
    val handlePx = with(density) { handleH.toPx() }

    // Server sa vytvára mimo hlavného vlákna.
    LaunchedEffect(Unit) {
        server = withContext(Dispatchers.IO) { DevToolsServer(ctx) }
    }

    val wv = remember {
        WebView.setWebContentsDebuggingEnabled(true)
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // Cookies: prijímať (aj od tretích strán) a pravidelne ukladať na disk, aby prežili reštart aplikácie.
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (url != null) addr = url
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                }
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    canBack = view?.canGoBack() == true
                    canForward = view?.canGoForward() == true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
            }
        }
    }

    // Druhý WebView so skutočným Chrome DevTools frontendom, pripojeným cez lokálny most na debug socket prvého.
    val dtv = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // tu sa ukladajú nastavenia DevTools (localStorage na pevnom origine 127.0.0.1:port)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // Bez WebViewClient by presmerovanie (napr. serve_rev -> serve_file) otvorilo externý prehliadač.
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (view == null || url == null || !url.startsWith("http")) return
                    // inspector.html sa načíta hneď, ale frontend sa dosťuje ďalších stovky modulov,
                    // preto čakáme, kým sa skutočne vykreslí (alebo neuplynie limit).
                    val g = ++dtGen[0]
                    view.evaluateJavascript(DT_LAYOUT_FIX, null)
                    var tick = 0
                    fun poll() {
                        view.postDelayed({
                            if (g != dtGen[0]) return@postDelayed
                            view.evaluateJavascript(DT_LAYOUT_FIX, null)
                            view.evaluateJavascript(DT_PROBE) { r ->
                                if (g != dtGen[0]) return@evaluateJavascript
                                tick++
                                val tabs = Regex("tab=(\\d+)").find(r ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                val text = Regex("text=(-?\\d+)").find(r ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                when {
                                    tabs > 0 || text > 20 -> {
                                        dtReady = true
                                        view.evaluateJavascript(DT_LAYOUT_FIX, null)
                                    }
                                    tick >= 60 -> dtError = str(R.string.the_frontend_did_not_render_even_after_3)
                                    else -> poll()
                                }
                            }
                        }, 500)
                    }
                    poll()
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) dtError = str(R.string.load_error, error?.description)
                }
            }
            webChromeClient = WebChromeClient()
        }
    }

    // DevTools zostanú pripojené aj pri reloade a prechode na inú stránku (cieľ je ten istý WebView).
    LaunchedEffect(dtOpen, started, server, dtRetry) {
        val srv = server
        if (dtOpen && started && srv != null) {
            dtError = null
            dtReady = false
            dtGen[0]++
            try {
                delay(250)
                dtv.loadUrl(frontendUrl(srv, addr))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dtError = e.message ?: e.javaClass.simpleName
            }
        } else if (!dtOpen) {
            dtGen[0]++
            dtv.loadUrl("about:blank")
        }
    }

    // Po otočení obrazovky sa stránka načíta znova.
    LaunchedEffect(Unit) { if (started && addr.isNotBlank()) wv.loadUrl(addr) }

    // Adresa v poli sa obnovuje podľa stránky, kým do neho nepíšeš.
    LaunchedEffect(addr, focused) { if (!focused) tfv = TextFieldValue(shortUrl(addr)) }

    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            server?.stop()
            for (w in listOf(wv, dtv)) {
                w.stopLoading()
                (w.parent as? ViewGroup)?.removeView(w)
                w.destroy()
            }
        }
    }

    BackHandler(enabled = started && canBack) { wv.goBack() }

    fun open(raw: String) {
        if (raw.isBlank()) return
        val u = toUrl(raw)
        addr = u
        started = true
        wv.loadUrl(u)
        focus.clearFocus()
    }

    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().imePadding()) {
        // Horný panel: adresa a obnoviť / zastaviť.
        Row(
            Modifier.fillMaxWidth().background(cs.surface).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = tfv, onValueChange = { tfv = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { open(tfv.text) }),
                modifier = Modifier.weight(1f).onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) tfv = TextFieldValue(addr, TextRange(0, addr.length))
                },
                decorationBox = { inner ->
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).background(cs.surfaceContainerHigh, RoundedCornerShape(50)).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (started && addr.isNotBlank()) {
                            val secure = addr.startsWith("https://")
                            Icon(
                                if (secure) Icons.Default.Lock else Icons.Default.Warning,
                                if (secure) str(R.string.secure) else str(R.string.not_secure),
                                Modifier.size(16.dp), tint = if (secure) cs.onSurfaceVariant else cs.error
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (tfv.text.isEmpty()) Text(str(R.string.search_or_enter_address), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                            inner()
                        }
                    }
                }
            )
            IconButton(onClick = {
                if (loading) wv.stopLoading() else if (started) wv.reload() else open(tfv.text)
            }) {
                Icon(if (loading) Icons.Default.Close else Icons.Default.Refresh, if (loading) str(R.string.stop) else str(R.string.reload))
            }
        }
        if (loading) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        } else HorizontalDivider(color = cs.outlineVariant)

        // Stránka + (voliteľne) DevTools, medzi nimi ťahateľný úchyt na zmenu veľkosti.
        Column(Modifier.weight(1f).fillMaxWidth().onSizeChanged { areaH = it.height }) {
            Box(Modifier.weight(if (dtOpen) split.coerceIn(0.12f, 0.88f) else 1f).fillMaxWidth()) {
                if (started) {
                    AndroidView(factory = { wv }, modifier = Modifier.fillMaxSize())
                } else {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        Text(str(R.string.new_tab), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("duckduckgo.com", "github.com", "wikipedia.org").forEach { site ->
                                AssistChip(onClick = { open(site) }, label = { Text(site) })
                            }
                        }
                    }
                }
            }

            if (dtOpen) {
                // Úchyt: ťahaním hore/dole sa mení pomer medzi stránkou a DevTools.
                val drag = rememberDraggableState { d ->
                    val avail = areaH - handlePx
                    if (avail > 0f) split = (split + d / avail).coerceIn(0.12f, 0.88f)
                }
                Box(
                    Modifier.fillMaxWidth().height(handleH).background(cs.surfaceContainer)
                        .draggable(state = drag, orientation = Orientation.Vertical),
                    contentAlignment = Alignment.Center
                ) {
                    HorizontalDivider(thickness = 2.dp, color = cs.primary)
                    Box(Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(50)).background(cs.primary))
                }

                Box(Modifier.weight(1f - split.coerceIn(0.12f, 0.88f)).fillMaxWidth()) {
                    if (!started) {
                        Text(str(R.string.open_a_page_first), Modifier.padding(16.dp), color = cs.onSurfaceVariant)
                    } else {
                        AndroidView(factory = { dtv }, modifier = Modifier.fillMaxSize())
                        val err = dtError
                        if (err != null) {
                            Column(
                                Modifier.fillMaxSize().background(cs.surface).verticalScroll(rememberScrollState()).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Err(str(R.string.could_not_connect_devtools, err))
                                TextButton(onClick = { dtRetry++ }) { Text(str(R.string.try_again)) }
                            }
                        } else if (!dtReady) {
                            Box(Modifier.fillMaxSize().background(cs.surface), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }

        // Spodný panel: späť, vpred, DevTools, menu.
        HorizontalDivider(color = cs.outlineVariant)
        Row(
            Modifier.fillMaxWidth().background(cs.surface).padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { wv.goBack() }, enabled = started && canBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.back))
            }
            IconButton(onClick = { wv.goForward() }, enabled = started && canForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, str(R.string.forward))
            }
            IconButton(onClick = { dtOpen = !dtOpen }) {
                Icon(DevToolsIcon, "DevTools", tint = if (dtOpen) cs.primary else cs.onSurfaceVariant)
            }
            IconButton(onClick = openDrawer) { Icon(Icons.Default.Menu, "Menu") }
        }
    }
}
