// SPDX-License-Identifier: GPL-3.0-or-later
package sk.devtools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.util.Locale

// Lokalizovaný kontext aplikácie. Nastavuje sa v MainActivity.attachBaseContext, ešte pred prvým použitím reťazcov.
object L {
    lateinit var ctx: Context
}

// Reťazec z resources v aktuálnom jazyku aplikácie. Funguje aj mimo Compose (chyby, výstupy nástrojov, zoznamy).
fun str(id: Int, vararg a: Any?): String = if (a.isEmpty()) L.ctx.getString(id) else L.ctx.getString(id, *a)

// Jazyk aplikácie: "system" (podľa zariadenia, predvolene), "sk" alebo "en".
object Lang {
    val CODES = listOf("system", "sk", "en")

    fun pref(c: Context): String =
        c.getSharedPreferences("devtools", Context.MODE_PRIVATE).getString("lang", "system")?.takeIf { it in CODES } ?: "system"

    fun label(code: String): String = when (code) {
        "sk" -> "Slovenčina"
        "en" -> "English"
        else -> str(R.string.language_system)
    }

    fun wrap(base: Context): Context {
        val p = pref(base)
        if (p == "system") return base
        val loc = Locale.forLanguageTag(p)
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        return base.createConfigurationContext(cfg)
    }

    // Uloží jazyk a reštartuje proces: zoznamy nástrojov a texty sa vytvárajú pri štarte, takže sa jazyk
    // uplatní celý naraz.
    fun set(c: Context, code: String) {
        c.getSharedPreferences("devtools", Context.MODE_PRIVATE).edit().putString("lang", code).commit()
        val next = c.packageManager.getLaunchIntentForPackage(c.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(
            Intent(c, RestartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("next", next).putExtra("pid", Process.myPid())
        )
    }
}

// Beží v samostatnom procese (:restart): ukončí hlavný proces a znova spustí aplikáciu.
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("next", Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("next")
        val pid = intent.getIntExtra("pid", -1)
        if (pid > 0) Process.killProcess(pid)
        if (next != null) startActivity(next)
        finish()
        Runtime.getRuntime().exit(0)
    }
}
