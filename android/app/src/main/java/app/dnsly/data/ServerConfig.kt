package app.dnsly.data

import android.content.Context

data class ServerConfig(
    val name: String = "Pi-hole",
    val address: String = "",
    val port: Int = 53,
    val tcp: Boolean = false,
    val domain: String = "example.com",
    val timeoutS: Int = 2,
    val intervalS: Int = 30,
    val failures: Int = 3,
) {
    val isConfigured get() = address.isNotBlank()
}

// shortcut: SharedPreferences instead of DataStore (ARCHITECTURE §4); enough for a handful of
// fields, switch in Etapa 6 if the settings grow or need observing from several places.
object ServerPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("server", Context.MODE_PRIVATE)

    fun load(ctx: Context): ServerConfig {
        val p = prefs(ctx)
        val d = ServerConfig()
        return ServerConfig(
            name = p.getString("name", d.name) ?: d.name,
            address = p.getString("address", d.address) ?: d.address,
            port = p.getInt("port", d.port),
            tcp = p.getBoolean("tcp", d.tcp),
            domain = p.getString("domain", d.domain) ?: d.domain,
            timeoutS = p.getInt("timeoutS", d.timeoutS),
            intervalS = p.getInt("intervalS", d.intervalS),
            failures = p.getInt("failures", d.failures),
        )
    }

    fun save(ctx: Context, c: ServerConfig) {
        prefs(ctx).edit()
            .putString("name", c.name)
            .putString("address", c.address)
            .putInt("port", c.port)
            .putBoolean("tcp", c.tcp)
            .putString("domain", c.domain)
            .putInt("timeoutS", c.timeoutS)
            .putInt("intervalS", c.intervalS)
            .putInt("failures", c.failures)
            .apply()
    }
}
