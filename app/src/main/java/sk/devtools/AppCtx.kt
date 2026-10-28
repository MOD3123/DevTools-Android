// SPDX-License-Identifier: GPL-3.0-or-later
package sk.devtools

import android.content.Context

// Aplikačný kontext pre kód mimo UI (napr. zisťovanie systémových DNS serverov).
object AppCtx {
    lateinit var app: Context
}
