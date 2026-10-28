// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sk.devtools

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Obsah nástroja. Nadpis zobrazuje veľký skladací horný panel v App(), tu ostáva len popis a obsah.
@Composable
fun Page(@Suppress("UNUSED_PARAMETER") title: String, desc: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(desc, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

// Expressive tvar (MaterialShapes) pre ikonové kontajnery – jednotný pre všetky nástroje.
private val ShapePool get() = listOf(
    MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Flower, MaterialShapes.Cookie6Sided,
    MaterialShapes.SoftBurst, MaterialShapes.Clover8Leaf, MaterialShapes.Cookie12Sided, MaterialShapes.Cookie7Sided,
    MaterialShapes.Cookie4Sided
)

@Composable
fun ShapedIcon(
    icon: ImageVector, index: Int, size: Dp = 52.dp,
    bg: Color = MaterialTheme.colorScheme.primaryContainer,
    fg: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    val shape = ShapePool.let { it[index.mod(it.size)] }.toShape()
    Box(
        Modifier.size(size).clip(shape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, Modifier.size(size * 0.46f), tint = fg)
    }
}

// Do prvého poľa každého nástroja sa vloží zdieľaný text alebo (ak je zapnuté) obsah schránky.
class PasteGate(val incoming: String?, val auto: Boolean) { var used = false }

val LocalPasteGate = compositionLocalOf { PasteGate(null, false).also { it.used = true } }

@Composable
fun Field(v: String, on: (String) -> Unit, label: String, lines: Int = 1, mono: Boolean = true) {
    val gate = LocalPasteGate.current
    val clip = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        if (!gate.used) {
            gate.used = true
            val src = gate.incoming ?: (if (gate.auto) clip.getText()?.text else null)
            if (!src.isNullOrBlank() && v.isEmpty()) on(src.trim().take(50_000))
        }
    }
    OutlinedTextField(
        value = v, onValueChange = on, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), minLines = lines, shape = RoundedCornerShape(20.dp),
        textStyle = LocalTextStyle.current.copy(fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    )
}

@Composable
fun Out(t: String, label: String = str(R.string.result), copy: String = t) {
    val cb = LocalClipboardManager.current
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    // Uloženie výsledku do súboru cez systémový výber umiestnenia.
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(copy.toByteArray()) } }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = { cb.setText(AnnotatedString(copy)); copied = true },
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) {
                        if (copied) { Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                        Text(if (copied) str(R.string.copied) else str(R.string.copy))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, str(R.string.more_options)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(str(R.string.share)) },
                                onClick = {
                                    menu = false
                                    val i = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, if (copy.length > 200_000) copy.take(200_000) else copy)
                                    }
                                    ctx.startActivity(Intent.createChooser(i, null))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(str(R.string.save_to_file)) },
                                onClick = { menu = false; saver.launch(str(R.string.devtools_result_txt)) }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            SelectionContainer { Text(t, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
fun Err(msg: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Text(msg, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

// Skupina prepínacích tlačidiel s tvarom, ktorý sa pri stlačení plynulo mení.
@Composable
fun Chips(opts: List<String>, sel: String, on: (String) -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        opts.forEach { o ->
            ToggleButton(checked = o == sel, onCheckedChange = { on(o) }, shapes = ToggleButtonDefaults.shapesFor(ButtonDefaults.MinHeight)) { Text(o) }
        }
    }

@Composable
fun Chk(label: String, v: Boolean, on: (Boolean) -> Unit) =
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { on(!v) }.padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(v, null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }

@Composable
fun Act(label: String, onClick: () -> Unit) =
    Button(
        onClick = onClick, shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 28.dp)
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }

@Composable
fun Run(label: String, enabled: Boolean = true, block: suspend () -> String) {
    var res by remember { mutableStateOf<Result<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val sc = rememberCoroutineScope()
    Button(
        onClick = {
            busy = true
            sc.launch {
                res = try { Result.success(block()) }
                catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
                catch (e: Exception) { Result.failure(e) }
                busy = false
            }
        },
        enabled = enabled && !busy, shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 28.dp)
    ) {
        if (busy) { LoadingIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)) }
        Text(if (busy) "Pracujem…" else label, style = MaterialTheme.typography.titleMedium)
    }
    res?.let { r ->
        r.onSuccess { Out(it) }
        r.onFailure { Err(it.message ?: str(R.string.an_error_occurred)) }
    }
}
