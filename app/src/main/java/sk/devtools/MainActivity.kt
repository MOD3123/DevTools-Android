// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sk.devtools

import android.os.Build
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.verticalScroll
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import android.net.Uri
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Text zdieľaný z inej aplikácie (Zdieľať -> DevTools).
    private var shared by mutableStateOf<String?>(null)

    private fun readShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { shared = it.take(100_000) }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val c = Lang.wrap(newBase)
        L.ctx = c
        super.attachBaseContext(c)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.app = applicationContext
        enableEdgeToEdge()
        if (savedInstanceState == null) readShare(intent)
        setContent { DevToolsTheme { Surface(color = MaterialTheme.colorScheme.surface) { App(shared) { shared = null } } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShare(intent)
    }

    // Cookies sa na disk zapisujú asynchrónne; pri odchode z aplikácie ich uložíme hneď, aby sa nestratili.
    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        super.onStop()
    }
}

// Material 3 Expressive: expresívny pohyb (pružinové animácie) + dynamické farby (Android 12+).
// Motív sa vždy riadi systémom (svetlý / tmavý), bez prepínača.
@Composable
fun DevToolsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val cs = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(
            primary = Color(0xFFB4C5FF), onPrimary = Color(0xFF00297A), primaryContainer = Color(0xFF2B3F8F),
            onPrimaryContainer = Color(0xFFDCE1FF), secondary = Color(0xFFC1C5DD), tertiary = Color(0xFFE5B9DE)
        )
        else -> lightColorScheme(
            primary = Color(0xFF4358A9), onPrimary = Color.White, primaryContainer = Color(0xFFDCE1FF),
            onPrimaryContainer = Color(0xFF00164E), secondary = Color(0xFF595D72), tertiary = Color(0xFF75546F)
        )
    }
    MaterialExpressiveTheme(colorScheme = cs, motionScheme = MotionScheme.expressive(), content = content)
}

@Composable
fun App(shared: String?, onSharedHandled: () -> Unit) {
    var cur by rememberSaveable { mutableStateOf("home") }
    var q by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var favs by remember { mutableStateOf(Store.ids(ctx, "fav")) }
    var recent by remember { mutableStateOf(Store.ids(ctx, "recent")) }
    val toggleFav: (String) -> Unit = { id ->
        favs = if (id in favs) favs - id else favs + id
        Store.saveIds(ctx, "fav", favs)
    }
    var pending by remember { mutableStateOf<String?>(null) }
    var autoPaste by remember { mutableStateOf(Store.get(ctx, "autopaste") != "0") }
    val go: (String) -> Unit = { id ->
        pending = null
        cur = id; q = ""; searching = false; scope.launch { drawer.close() }
        if (id != "lic" && TOOLS.any { it.id == id }) {
            recent = (listOf(id) + recent.filter { it != id }).take(8)
            Store.saveIds(ctx, "recent", recent)
        }
    }
    val goShared: (String) -> Unit = { id ->
        val txt = shared
        go(id)
        pending = txt
        onSharedHandled()
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(Backup.export(ctx).toByteArray()) } }.isSuccess
            Toast.makeText(ctx, if (ok) str(R.string.backup_saved) else str(R.string.could_not_save_the_backup), Toast.LENGTH_SHORT).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val r = runCatching {
                val txt = ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
                Backup.restore(ctx, txt)
            }
            r.onSuccess {
                favs = Store.ids(ctx, "fav")
                recent = Store.ids(ctx, "recent")
                autoPaste = Store.get(ctx, "autopaste") != "0"
                cur = "home"
                Toast.makeText(ctx, str(R.string.backup_loaded, it), Toast.LENGTH_LONG).show()
            }
            r.onFailure { Toast.makeText(ctx, str(R.string.invalid_backup, it.message), Toast.LENGTH_LONG).show() }
        }
    }
    val gate = remember(cur, pending, autoPaste) { PasteGate(pending, autoPaste) }
    // Stav posúvania Domova žije v App, takže prežije otvorenie nástroja a návrat späť.
    val homeGrid = rememberLazyGridState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val tool = TOOLS.find { it.id == cur }

    BackHandler(enabled = cur != "home" || searching) {
        if (searching) { searching = false; q = "" } else cur = "home"
    }
    if (shared != null) {
        val sug = remember(shared) { suggestFor(shared).mapNotNull { id -> TOOLS.find { it.id == id } } }
        AlertDialog(
            onDismissRequest = onSharedHandled,
            title = { Text(str(R.string.shared_text)) },
            text = {
                Column {
                    Text(
                        shared.take(160) + if (shared.length > 160) "…" else "",
                        style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 4,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(str(R.string.open_in_tool), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        sug.forEach { t ->
                            ListItem(
                                headlineContent = { Text(t.title) },
                                leadingContent = { Icon(t.icon, null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable { goShared(t.id) }
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onSharedHandled) { Text(str(R.string.cancel)) } }
        )
    }
    ModalNavigationDrawer(
        drawerState = drawer, gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    cur, go, autoPaste,
                    { v -> autoPaste = v; Store.put(ctx, "autopaste", if (v) "1" else "0") },
                    { exporter.launch(str(R.string.devtools_backup_json)) },
                    { importer.launch(arrayOf("*/*")) }
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                val nav: @Composable () -> Unit = {
                    IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") }
                }
                val act: @Composable RowScope.() -> Unit = {
                    if (tool != null) IconButton({ toggleFav(tool.id) }) {
                        Icon(
                            Icons.Default.Star, str(R.string.favorites),
                            tint = if (tool.id in favs) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalIconButton({ searching = !searching; if (!searching) q = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, str(R.string.search))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (cur == "web" && !searching) {
                    // Prehliadač má vlastný panel ako klasický prehliadač.
                } else if (searching) {
                    TopAppBar(title = { SearchField(q) { q = it } }, navigationIcon = nav, actions = act)
                } else {
                    LargeFlexibleTopAppBar(
                        title = { Text(tool?.title ?: "DevTools", fontWeight = FontWeight.Bold) },
                        navigationIcon = nav, actions = act, scrollBehavior = scroll
                    )
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                CompositionLocalProvider(
                    LocalOpenDrawer provides { scope.launch { drawer.open() } },
                    LocalPasteGate provides gate
                ) {
                    key(gate) {
                        when {
                            q.isNotBlank() -> Results(q, go)
                            tool == null -> HomeScreen(go, favs, recent, toggleFav, homeGrid)
                            else -> tool.ui()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchField(q: String, on: (String) -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    TextField(
        value = q, onValueChange = on, singleLine = true,
        placeholder = { Text(str(R.string.search_tools)) },
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(fr)
    )
}

@Composable
fun DrawerContent(
    cur: String, go: (String) -> Unit,
    autoPaste: Boolean, onAutoPaste: (Boolean) -> Unit,
    onExport: () -> Unit, onImport: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item {
            Text("DevTools", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
        }
        GROUPS.forEach { (g, ids) ->
            item {
                Text(g, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp))
            }
            items(ids) { id ->
                val t = TOOLS.find { it.id == id }
                NavigationDrawerItem(
                    label = { Text(t?.title ?: str(R.string.home)) },
                    icon = { Icon(t?.icon ?: Icons.Default.Home, null) },
                    selected = cur == id, onClick = { go(id) }
                )
            }
        }
        item {
            Text(str(R.string.settings), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp))
        }
        item {
            ListItem(
                headlineContent = { Text(str(R.string.auto_paste_from_clipboard)) },
                supportingContent = { Text(str(R.string.pasted_into_the_tool_s_first_field)) },
                trailingContent = { Switch(checked = autoPaste, onCheckedChange = onAutoPaste) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onAutoPaste(!autoPaste) }
            )
        }
        item { LanguageItem() }
        item { NavigationDrawerItem(label = { Text(str(R.string.export_backup)) }, selected = false, onClick = onExport) }
        item { NavigationDrawerItem(label = { Text(str(R.string.import_backup)) }, selected = false, onClick = onImport) }
        item {
            NavigationDrawerItem(
                label = { Text(str(R.string.open_source_licenses)) },
                icon = { Icon(Icons.Default.Info, null) },
                selected = cur == "lic", onClick = { go("lic") }
            )
        }
    }
}

@Composable
fun ToolCard(t: Tool, go: (String) -> Unit, fav: Boolean, onFav: () -> Unit) {
    val on = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        onClick = { go(t.id) },
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().height(208.dp).padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                ShapedIcon(t.icon, TOOLS.indexOf(t), bg = on, fg = MaterialTheme.colorScheme.primaryContainer)
                IconButton(onClick = onFav, modifier = Modifier.offset(x = 8.dp, y = (-8).dp)) {
                    Icon(Icons.Default.Star, if (fav) str(R.string.remove_from_favorites) else str(R.string.add_to_favorites), tint = if (fav) on else on.copy(alpha = 0.35f))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = on, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t.desc, style = MaterialTheme.typography.bodyMedium,
                    color = on.copy(alpha = 0.8f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun HomeScreen(go: (String) -> Unit, favs: List<String>, recent: List<String>, toggleFav: (String) -> Unit, state: LazyGridState) {
    val favTools = favs.mapNotNull { id -> TOOLS.find { it.id == id } }
    val recentTools = recent.mapNotNull { id -> TOOLS.find { it.id == id } }
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(str(R.string.tools_for_developers), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(str(R.string.tools_most_run_locally, TOOLS.count { t -> GROUPS.any { g -> t.id in g.second } }), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        if (recentTools.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(str(R.string.recently_used), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentTools.forEach { t ->
                        AssistChip(
                            onClick = { go(t.id) },
                            label = { Text(t.title) },
                            leadingIcon = { Icon(t.icon, null, Modifier.size(18.dp)) }
                        )
                    }
                }
            }
        }
        if (favTools.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(str(R.string.favorites), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
            }
            items(favTools) { t -> ToolCard(t, go, true) { toggleFav(t.id) } }
        }
        GROUPS.filter { it.first != str(R.string.overview) }.forEach { (g, ids) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(g, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
            }
            items(ids.mapNotNull { id -> TOOLS.find { it.id == id } }) { t ->
                ToolCard(t, go, t.id in favs) { toggleFav(t.id) }
            }
        }
    }
}

@Composable
fun Results(q: String, go: (String) -> Unit) {
    val r = TOOLS.filter { it.title.contains(q, true) || it.desc.contains(q, true) }
    if (r.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(str(R.string.nothing_found), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(r) { t ->
            ListItem(
                headlineContent = { Text(t.title, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text(t.desc) },
                leadingContent = { ShapedIcon(t.icon, TOOLS.indexOf(t), 44.dp) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.clip(RoundedCornerShape(24.dp)).clickable { go(t.id) }
            )
        }
    }
}

@Composable
fun LanguageItem() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val cur = Lang.pref(ctx)
    ListItem(
        headlineContent = { Text(str(R.string.settings_language)) },
        supportingContent = { Text(Lang.label(cur)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { open = true }
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(str(R.string.settings_language)) },
            text = {
                Column {
                    for (code in Lang.CODES) {
                        Row(
                            Modifier.fillMaxWidth().clickable { open = false; if (code != cur) Lang.set(ctx, code) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = code == cur, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(Lang.label(code))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(str(R.string.cancel)) } }
        )
    }
}
