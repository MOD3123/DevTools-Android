// SPDX-License-Identifier: GPL-3.0-or-later
package sk.devtools

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Ikona "</>" (rovnaká myšlienka ako ikona aplikácie) – nezávisí od balíka material-icons-extended.
val DevToolsIcon: ImageVector by lazy {
    ImageVector.Builder("DevTools", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes("M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z"),
            fill = SolidColor(Color.Black)
        )
        .build()
}
