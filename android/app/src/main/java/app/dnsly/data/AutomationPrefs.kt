package app.dnsly.data

import android.content.Context

/** Automation switches (ARCHITECTURE §9.3). Persisted so they survive restarts. */
object AutomationPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("automation", Context.MODE_PRIVATE)

    /** Master switch of the automation (engine `SetEnabled`). */
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun save(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", enabled).apply()
    }

    /**
     * When false, a recovered server does not reactivate the DNS by itself:
     * the engine stays on the network DNS until the conditions reset.
     */
    fun autoReactivate(ctx: Context): Boolean = prefs(ctx).getBoolean("autoReactivate", true)

    fun saveAutoReactivate(ctx: Context, autoReactivate: Boolean) {
        prefs(ctx).edit().putBoolean("autoReactivate", autoReactivate).apply()
    }
}
