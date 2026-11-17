package app.dnsly.data

import android.content.Context
import app.dnsly.debugLog

data class LocationConfig(
    val hasLocation: Boolean,
    val lat: Double,
    val lng: Double,
    val radiusM: Int,
    val geofenceEnabled: Boolean,
    /** Last ENTER/EXIT transition reported by the geofence (true = inside). */
    val lastInside: Boolean,
    /** currentTimeMillis of the last transition; 0 if none yet. */
    val insideAtMs: Long,
)

object LocationPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("location", Context.MODE_PRIVATE)

    fun load(ctx: Context): LocationConfig {
        val p = prefs(ctx)
        return LocationConfig(
            hasLocation = p.contains("lat"),
            lat = p.getString("lat", "0")!!.toDouble(),
            lng = p.getString("lng", "0")!!.toDouble(),
            radiusM = p.getInt("radiusM", 50),
            geofenceEnabled = p.getBoolean("geofence", false),
            lastInside = p.getBoolean("lastInside", true),
            insideAtMs = p.getLong("insideAtMs", 0),
        )
    }

    fun saveLocation(ctx: Context, lat: Double, lng: Double) {
        prefs(ctx).edit().putString("lat", lat.toString()).putString("lng", lng.toString()).apply()
    }

    fun saveRadius(ctx: Context, radiusM: Int) {
        prefs(ctx).edit().putInt("radiusM", radiusM).apply()
    }

    fun saveGeofence(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("geofence", enabled).apply()
    }

    fun saveInside(ctx: Context, inside: Boolean) {
        debugLog("Dnsly", "saveInside=$inside")
        prefs(ctx).edit()
            .putBoolean("lastInside", inside)
            .putLong("insideAtMs", System.currentTimeMillis())
            .apply()
    }
}

/**
 * The geofence condition off means "inside"; on means the last known transition.
 * A missing location keeps the condition satisfied: there is no fence to leave.
 */
fun effectiveInside(cfg: LocationConfig): Boolean = !cfg.geofenceEnabled || cfg.lastInside
