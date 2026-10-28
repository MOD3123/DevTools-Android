// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sk.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun Rw(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically, content = content)

@Composable
fun Lbl(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

@Composable
fun Big(t: String) = Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
    Text(
        t, Modifier.padding(24.dp), style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center
    )
}

@Composable
fun Sld(label: String, v: Float, range: ClosedFloatingPointRange<Float>, on: (Float) -> Unit) =
    Column { Text(label, style = MaterialTheme.typography.bodyLarge); Slider(v, on, valueRange = range) }

@Composable
fun Btn(label: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) =
    FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier.heightIn(min = 48.dp)) { Text(label) }

// Zobrazí výsledok (alebo chybu) z vypočítaného Result.
@Composable
fun Res(r: Result<String>?, label: String = str(R.string.result)) {
    r?.onSuccess { if (it.isNotEmpty()) Out(it, label) }
    r?.onFailure { Err(it.message ?: str(R.string.an_error_occurred)) }
}

// Výstup so zvýraznením (AnnotatedString) – napr. zhody regexu alebo diff.
@Composable
fun RichOut(a: AnnotatedString, label: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 18.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            SelectionContainer { Text(a, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace) }
        }
    }
}

fun hexColor(s: String): Color? = parseColor(s)?.let { Color(it.first, it.second, it.third) }

// ───────────────────────── Markdown ─────────────────────────

private sealed class MdBlock {
    class H(val level: Int, val text: String) : MdBlock()
    class P(val text: String) : MdBlock()
    class Li(val marker: String, val text: String) : MdBlock()
    class Quote(val text: String) : MdBlock()
    class Code(val text: String) : MdBlock()
    object Hr : MdBlock()
}

private fun mdBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = mutableListOf<String>()
    fun fp() { if (para.isNotEmpty()) { out.add(MdBlock.P(para.joinToString("\n"))); para.clear() } }
    val lines = src.replace("\r", "").split("\n")
    var i = 0
    var n = 0
    while (i < lines.size) {
        val l = lines[i].trimEnd()
        when {
            l.trimStart().startsWith("```") -> {
                fp(); i++
                val code = mutableListOf<String>()
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.add(lines[i]); i++ }
                out.add(MdBlock.Code(code.joinToString("\n")))
            }
            l.isBlank() -> fp()
            Regex("^(#{1,4})\\s+(.*)$").matchEntire(l) != null -> {
                fp(); val m = Regex("^(#{1,4})\\s+(.*)$").matchEntire(l)!!
                out.add(MdBlock.H(m.groupValues[1].length, m.groupValues[2]))
            }
            Regex("^(-{3,}|\\*{3,})$").matches(l.trim()) -> { fp(); out.add(MdBlock.Hr) }
            Regex("^\\s*([-*]|\\d+\\.)\\s+(.*)$").matchEntire(l) != null -> {
                fp(); val m = Regex("^\\s*([-*]|\\d+\\.)\\s+(.*)$").matchEntire(l)!!
                val ordered = m.groupValues[1].last() == '.'
                n = if (ordered && out.lastOrNull() is MdBlock.Li && (out.last() as MdBlock.Li).marker.endsWith(".")) n + 1 else 1
                out.add(MdBlock.Li(if (ordered) "$n." else "•", m.groupValues[2]))
            }
            Regex("^>\\s?(.*)$").matchEntire(l) != null -> { fp(); out.add(MdBlock.Quote(Regex("^>\\s?(.*)$").matchEntire(l)!!.groupValues[1])) }
            else -> para.add(l)
        }
        i++
    }
    fp()
    return out
}

private val INLINE = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")

@Composable
private fun mdInline(t: String): AnnotatedString {
    val cs = MaterialTheme.colorScheme
    return buildAnnotatedString {
        var last = 0
        for (m in INLINE.findAll(t)) {
            append(t.substring(last, m.range.first))
            val g = m.groups
            when {
                g[1] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = cs.surfaceContainerHighest)) { append(g[1]!!.value) }
                g[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]!!.value) }
                g[3] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[3]!!.value) }
                else -> withStyle(SpanStyle(color = cs.primary, textDecoration = TextDecoration.Underline)) { append(g[4]!!.value) }
            }
            last = m.range.last + 1
        }
        append(t.substring(last))
    }
}

@Composable
fun MarkdownView(src: String) {
    val blocks = remember(src) { mdBlocks(src) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in blocks) when (b) {
            is MdBlock.H -> Text(
                mdInline(b.text), fontWeight = FontWeight.Bold,
                style = when (b.level) {
                    1 -> MaterialTheme.typography.headlineMedium
                    2 -> MaterialTheme.typography.titleLarge
                    3 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                }
            )
            is MdBlock.P -> Text(mdInline(b.text), style = MaterialTheme.typography.bodyLarge)
            is MdBlock.Li -> Row(Modifier.padding(start = 8.dp)) {
                Text(b.marker, Modifier.width(28.dp), style = MaterialTheme.typography.bodyLarge)
                Text(mdInline(b.text), style = MaterialTheme.typography.bodyLarge)
            }
            is MdBlock.Quote -> Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(cs.primary))
                    Text(mdInline(b.text), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
                }
            }
            is MdBlock.Code -> Surface(color = cs.surfaceContainerHighest, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Text(b.text, Modifier.horizontalScroll(rememberScrollState()).padding(14.dp), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
            MdBlock.Hr -> Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
        }
    }
}
