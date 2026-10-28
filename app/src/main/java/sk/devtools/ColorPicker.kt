// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sk.devtools

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

private val SWATCHES = listOf(
    "#f44336", "#e91e63", "#9c27b0", "#673ab7", "#3f51b5", "#2196f3", "#00bcd4", "#009688",
    "#4caf50", "#cddc39", "#ffeb3b", "#ff9800", "#795548", "#607d8b", "#000000", "#ffffff"
)

// Pole farby: náhľad + HEX + rozbaliteľný výber (ťuknutím na náhľad).
@Composable
fun ColorField(value: String, onChange: (String) -> Unit, label: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    val rgb = parseColor(value)
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (rgb != null) Color(rgb.first, rgb.second, rgb.third) else MaterialTheme.colorScheme.errorContainer)
                    .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                    .clickable { open = !open }
            )
            OutlinedTextField(
                value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, isError = rgb == null,
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
            )
        }
        if (open) ColorPanel(value, onChange)
    }
}

@Composable
fun ColorPanel(value: String, onChange: (String) -> Unit) {
    var h by remember { mutableFloatStateOf(0f) }
    var s by remember { mutableFloatStateOf(0f) }
    var v by remember { mutableFloatStateOf(1f) }
    var svSize by remember { mutableStateOf(IntSize.Zero) }
    var hueSize by remember { mutableStateOf(IntSize.Zero) }
    val push by rememberUpdatedState(onChange)

    fun hex(): String {
        val c = android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 359.9f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
        return hexOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    }

    // Keď sa hodnota zmení zvonku (písaním HEX, vzorka), prenesie sa do výberu.
    LaunchedEffect(value) {
        val c = parseColor(value) ?: return@LaunchedEffect
        if (hexOf(c.first, c.second, c.third) != hex()) {
            val f = FloatArray(3)
            android.graphics.Color.RGBToHSV(c.first, c.second, c.third, f)
            if (f[1] > 0.001f && f[2] > 0.001f) h = f[0]
            s = f[1]
            v = f[2]
        }
    }

    fun updSv(o: Offset) {
        if (svSize.width > 0 && svSize.height > 0) {
            s = (o.x / svSize.width).coerceIn(0f, 1f)
            v = 1f - (o.y / svSize.height).coerceIn(0f, 1f)
            push(hex())
        }
    }
    fun updHue(o: Offset) {
        if (hueSize.width > 0) {
            h = (o.x / hueSize.width * 360f).coerceIn(0f, 359.9f)
            push(hex())
        }
    }

    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 359.9f), 1f, 1f)))

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Sýtosť (vodorovne) a jas (zvisle)
        Box(
            Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(20.dp))
                .onSizeChanged { svSize = it }
                .drawBehind {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val c = Offset(s * size.width, (1f - v) * size.height)
                    drawCircle(Color.Black.copy(alpha = 0.4f), 13.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                    drawCircle(Color.White, 11.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                }
                .pointerInput(Unit) { detectTapGestures { o -> updSv(o) } }
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { o -> updSv(o) }) { change, _ ->
                        change.consume()
                        updSv(change.position)
                    }
                }
        )
        // Odtieň
        Box(
            Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(16.dp))
                .onSizeChanged { hueSize = it }
                .drawBehind {
                    val stops = (0..6).map { i -> Color(android.graphics.Color.HSVToColor(floatArrayOf(if (i == 6) 0f else i * 60f, 1f, 1f))) }
                    drawRect(Brush.horizontalGradient(stops))
                    val c = Offset(h / 360f * size.width, size.height / 2)
                    drawCircle(Color.Black.copy(alpha = 0.4f), 14.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                    drawCircle(Color.White, 12.dp.toPx(), c, style = Stroke(4.dp.toPx()))
                }
                .pointerInput(Unit) { detectTapGestures { o -> updHue(o) } }
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { o -> updHue(o) }) { change, _ ->
                        change.consume()
                        updHue(change.position)
                    }
                }
        )
        // Rýchle vzorky
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SWATCHES.forEach { hx ->
                val c = parseColor(hx)!!
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(Color(c.first, c.second, c.third))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable { onChange(hx) }
                )
            }
        }
    }
}
