package app.dnsly.data

import android.content.Context

/** Master switch of the automation (engine `SetEnabled`). Persisted so it survives restarts. */
object AutomationPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("automation", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun save(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", enabled).apply()
    }
}
