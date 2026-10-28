// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sk.devtools

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ───────────────────────── Výber farby ─────────────────────────

@Composable
fun ColTool() = Page(str(R.string.color_picker), str(R.string.set_a_color_with_the_sliders_or_paste_he)) {
    var r by rememberSaveable { mutableIntStateOf(11) }
    var g by rememberSaveable { mutableIntStateOf(107) }
    var b by rememberSaveable { mutableIntStateOf(203) }
    var txt by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(28.dp)).background(Color(r, g, b)))
    Field(txt, { txt = it; parseColor(it)?.let { c -> r = c.first; g = c.second; b = c.third } }, "#0b6bcb, rgb(11,107,203), hsl(210,90%,42%)")
    ColorPanel(hexOf(r, g, b)) { hx ->
        parseColor(hx)?.let { c -> r = c.first; g = c.second; b = c.third; txt = hx }
    }
    Sld("R: $r", r.toFloat(), 0f..255f) { r = it.toInt() }
    Sld("G: $g", g.toFloat(), 0f..255f) { g = it.toInt() }
    Sld("B: $b", b.toFloat(), 0f..255f) { b = it.toInt() }
    val (h, s, l) = rgb2hsl(r, g, b)
    Out(hexOf(r, g, b), "HEX")
    Out("rgb($r, $g, $b)", "RGB")
    Out("hsl($h, $s%, $l%)", "HSL")
}

@Composable
fun CtrTool() = Page(str(R.string.color_contrast), str(R.string.wcag_contrast_ratios_for_text_and_backgr)) {
    var fg by rememberSaveable { mutableStateOf("#1b1b21") }
    var bg by rememberSaveable { mutableStateOf("#ffffff") }
    ColorField(fg, { fg = it }, "Text (HEX)")
    ColorField(bg, { bg = it }, str(R.string.background_hex))
    val f = parseColor(fg); val b = parseColor(bg)
    if (f != null && b != null) {
        val ratio = contrastRatio(f, b)
        Surface(color = Color(b.first, b.second, b.third), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 28.dp, vertical = 24.dp)) {
                Text(str(R.string.sample_text), style = MaterialTheme.typography.headlineSmall, color = Color(f.first, f.second, f.third))
                Text(str(R.string.this_is_what_normal_text_looks_like_on_t), color = Color(f.first, f.second, f.third))
            }
        }
        Big("%.2f:1".format(java.util.Locale.ROOT, ratio))
        Out(contrastReport(ratio), str(R.string.result))
    } else Err("Zadaj farby ako HEX (napr. #1b1b21).")
}

@Composable
fun GradTool() = Page("CSS gradient", str(R.string.linear_or_radial_gradient_design_with_re)) {
    var c1 by rememberSaveable { mutableStateOf("#3f56c9") }
    var c2 by rememberSaveable { mutableStateOf("#8e6bd8") }
    var c3 by rememberSaveable { mutableStateOf("#ff7aa8") }
    var mid by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf(str(R.string.linear)) }
    var ang by remember { mutableFloatStateOf(135f) }
    val cols = listOfNotNull(hexColor(c1), if (mid) hexColor(c2) else null, hexColor(c3))
    val names = listOf(c1) + (if (mid) listOf(c2) else emptyList()) + listOf(c3)
    val linear = type == str(R.string.linear)
    if (cols.size == names.size) {
        Box(
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(32.dp)).drawBehind {
                val brush = if (linear) {
                    val th = Math.toRadians(ang.toDouble())
                    val dx = Math.sin(th).toFloat(); val dy = -Math.cos(th).toFloat()
                    val len = Math.abs(size.width * dx) + Math.abs(size.height * dy)
                    val c = Offset(size.width / 2, size.height / 2)
                    Brush.linearGradient(cols, Offset(c.x - dx * len / 2, c.y - dy * len / 2), Offset(c.x + dx * len / 2, c.y + dy * len / 2))
                } else Brush.radialGradient(cols, Offset(size.width / 2, size.height / 2), Math.hypot(size.width / 2.0, size.height / 2.0).toFloat())
                drawRect(brush)
            }
        )
    }
    ColorField(c1, { c1 = it }, str(R.string.color_1_hex))
    Chk(str(R.string.middle_color), mid) { mid = it }
    if (mid) ColorField(c2, { c2 = it }, str(R.string.middle_hex))
    ColorField(c3, { c3 = it }, str(R.string.color_2_hex))
    Chips(listOf(str(R.string.linear), str(R.string.radial)), type) { type = it }
    if (linear) Sld(str(R.string.angle, ang.toInt()), ang, 0f..360f) { ang = it }
    if (cols.size == names.size) {
        val g = if (linear) "linear-gradient(${ang.toInt()}deg, ${names.joinToString(", ")})" else "radial-gradient(circle, ${names.joinToString(", ")})"
        Out("background: $g;", "CSS")
    } else Err(str(R.string.invalid_color_enter_hex_e_g_3f56c9))
}

@Composable
fun ShadowTool() = Page("Box shadow", str(R.string.shadow_design_with_ready_made_css_the_pr)) {
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(14f) }
    var bl by remember { mutableFloatStateOf(36f) }
    var sp by remember { mutableFloatStateOf(0f) }
    var op by remember { mutableFloatStateOf(25f) }
    var col by rememberSaveable { mutableStateOf("#000000") }
    var inset by rememberSaveable { mutableStateOf(false) }
    val c = parseColor(col)
    val tint = c?.let { Color(it.first, it.second, it.third, (op / 100f * 255).toInt()) } ?: Color.Black
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.offset(x.dp, y.dp / 2).size(180.dp, 110.dp)
                    .shadow((bl / 3 + sp / 3).coerceAtLeast(0f).dp, RoundedCornerShape(24.dp), ambientColor = tint, spotColor = tint)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
            )
        }
    }
    Sld(str(R.string.offset_x, x.toInt()), x, -60f..60f) { x = it }
    Sld(str(R.string.offset_y, y.toInt()), y, -60f..60f) { y = it }
    Sld(str(R.string.blur, bl.toInt()), bl, 0f..120f) { bl = it }
    Sld(str(R.string.spread, sp.toInt()), sp, -40f..60f) { sp = it }
    Sld(str(R.string.opacity, op.toInt()), op, 0f..100f) { op = it }
    ColorField(col, { col = it }, str(R.string.color_hex))
    Chk(str(R.string.inner_shadow_inset), inset) { inset = it }
    if (c != null) {
        val rgba = "rgba(${c.first},${c.second},${c.third},${Js.fmt(op / 100.0)})"
        Out("box-shadow: ${if (inset) "inset " else ""}${x.toInt()}px ${y.toInt()}px ${bl.toInt()}px ${sp.toInt()}px $rgba;", "CSS")
    } else Err(str(R.string.invalid_color))
}

@Composable
fun BradTool() = Page("Border radius", str(R.string.corner_radius_design_with_a_live_preview)) {
    var a by remember { mutableFloatStateOf(48f) }
    var b by remember { mutableFloatStateOf(12f) }
    var c by remember { mutableFloatStateOf(48f) }
    var d by remember { mutableFloatStateOf(12f) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(180.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(a.dp, b.dp, c.dp, d.dp)))
        }
    }
    Sld(str(R.string.top_left_px, a.toInt()), a, 0f..100f) { a = it }
    Sld(str(R.string.top_right_px, b.toInt()), b, 0f..100f) { b = it }
    Sld(str(R.string.bottom_right_px, c.toInt()), c, 0f..100f) { c = it }
    Sld(str(R.string.bottom_left_px, d.toInt()), d, 0f..100f) { d = it }
    val v = listOf(a, b, c, d).map { it.toInt() }
    Out("border-radius: " + (if (v.all { it == v[0] }) "${v[0]}px" else v.joinToString(" ") { "${it}px" }) + ";", "CSS")
}

// ───────────────────────── Stopky a odpočet ─────────────────────────

private fun swF(ms: Long): String { val t = ms / 10; return "%02d:%02d.%02d".format(t / 6000, t / 100 % 60, t % 100) }
private fun cdF(ms: Long): String { val s = Math.ceil(ms / 1000.0).toLong(); return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) }

private fun beep() {
    try { ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700) } catch (_: Exception) { }
}

@Composable
fun TimerTool() = Page(str(R.string.stopwatch_and_countdown), str(R.string.stopwatch_with_lap_times_and_a_countdown_2)) {
    // Stopky
    var swBase by remember { mutableLongStateOf(0L) }
    var swStart by remember { mutableLongStateOf(0L) }
    var swNow by remember { mutableLongStateOf(0L) }
    var swRun by remember { mutableStateOf(false) }
    var laps by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(swRun) { while (swRun) { swNow = SystemClock.elapsedRealtime(); delay(30) } }
    val swVal = if (swRun) swBase + (swNow - swStart).coerceAtLeast(0) else swBase
    Lbl(str(R.string.stopwatch))
    Big(swF(swVal))
    Rw {
        Act2(if (swRun) str(R.string.stop) else if (swBase > 0) str(R.string.resume) else str(R.string.start), Modifier.weight(1f)) {
            if (swRun) { swBase += SystemClock.elapsedRealtime() - swStart; swRun = false }
            else { swStart = SystemClock.elapsedRealtime(); swNow = swStart; swRun = true }
        }
        Btn(str(R.string.lap), Modifier.weight(1f)) { if (swRun || swBase > 0) laps = listOf(swF(swVal)) + laps }
        Btn(str(R.string.zero), Modifier.weight(1f)) { swRun = false; swBase = 0; laps = emptyList() }
    }
    if (laps.isNotEmpty()) Out(laps.mapIndexed { i, l -> "#${laps.size - i}  $l" }.joinToString("\n"), str(R.string.laps))

    // Odpočet
    var h by rememberSaveable { mutableStateOf("0") }
    var m by rememberSaveable { mutableStateOf("5") }
    var s by rememberSaveable { mutableStateOf("0") }
    var cdRun by remember { mutableStateOf(false) }
    var cdEnd by remember { mutableLongStateOf(0L) }
    var cdRem by remember { mutableLongStateOf(0L) }
    val setMs = (((h.trim().toLongOrNull() ?: 0) * 3600 + (m.trim().toLongOrNull() ?: 0) * 60 + (s.trim().toLongOrNull() ?: 0)) * 1000)
    LaunchedEffect(cdRun) {
        while (cdRun) {
            val left = cdEnd - SystemClock.elapsedRealtime()
            if (left <= 0) { cdRun = false; cdRem = 0; beep(); break }
            cdRem = left
            delay(100)
        }
    }
    Spacer(Modifier.height(8.dp))
    Lbl(str(R.string.countdown))
    Big(cdF(if (cdRun || cdRem > 0) cdRem else setMs))
    Rw {
        Box(Modifier.weight(1f)) { Field(h, { h = it }, "h") }
        Box(Modifier.weight(1f)) { Field(m, { m = it }, "min") }
        Box(Modifier.weight(1f)) { Field(s, { s = it }, "s") }
    }
    Rw {
        Act2(if (cdRun) str(R.string.pause) else if (cdRem > 0) str(R.string.resume) else str(R.string.start), Modifier.weight(1f)) {
            if (cdRun) { cdRem = cdEnd - SystemClock.elapsedRealtime(); cdRun = false }
            else {
                if (cdRem <= 0) cdRem = setMs
                if (cdRem > 0) { cdEnd = SystemClock.elapsedRealtime() + cdRem; cdRun = true }
            }
        }
        Btn(str(R.string.reset), Modifier.weight(1f)) { cdRun = false; cdRem = 0 }
    }
}

// Plné tlačidlo, ktoré sa dá vložiť do riadku.
@Composable
private fun Act2(label: String, modifier: Modifier, onClick: () -> Unit) =
    Button(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier.heightIn(min = 48.dp)) { Text(label) }
