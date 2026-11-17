package app.dnsly.geo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import app.dnsly.data.LocationConfig
import app.dnsly.data.LocationPrefs
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Registers/removes the home-area geofence (ARCHITECTURE §2). Called on every engine start,
 * app resume and boot: geofences do not survive reboots or data clears, so `sync` is the
 * single place that re-derives what should be registered from the saved prefs.
 */
object GeofenceManager {
    private const val TAG = "Dnsly"
    private const val GEOFENCE_ID = "dnsly_home"

    /**
     * Signature of the geofence currently registered by this process. addGeofences() replaces
     * the whole set and restarts its state machine (initial trigger included), so re-registering
     * identical params on every sync would starve ENTER/EXIT delivery.
     */
    @Volatile private var registeredKey: String? = null

    fun sync(ctx: Context) {
        val cfg = LocationPrefs.load(ctx)
        val perm = LocationAccess.hasGeofencePermission(ctx)
        Log.d(
            TAG,
            "sync geofence=${cfg.geofenceEnabled} hasLoc=${cfg.hasLocation} " +
                "perm=$perm lastInside=${cfg.lastInside}",
        )
        val pending = pendingIntent(ctx)
        when {
            !cfg.geofenceEnabled || !cfg.hasLocation -> remove(ctx, pending)
            !perm -> {
                // Permission revoked: transitions can no longer fire, so the area counts as
                // outside (fail-open to the network DNS, ARCHITECTURE §6).
                registeredKey = null
                LocationPrefs.saveInside(ctx, false)
            }
            else -> register(ctx, cfg, pending)
        }
    }

    private fun register(ctx: Context, cfg: LocationConfig, pending: PendingIntent) {
        val key = "${cfg.lat}|${cfg.lng}|${cfg.radiusM}"
        if (registeredKey == key) {
            Log.d(TAG, "geofence unchanged, skip re-register")
            return
        }
        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(cfg.lat, cfg.lng, cfg.radiusM.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            // Both triggers: whichever matches the current position is delivered on registration,
            // which is how the engine learns the state after boot or app start.
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofence(geofence)
            .build()
        registeredKey = key
        try {
            LocationServices.getGeofencingClient(ctx).addGeofences(request, pending)
                .addOnSuccessListener {
                    Log.d(TAG, "geofence registered r=${cfg.radiusM}m home=${cfg.lat},${cfg.lng}")
                }
                .addOnFailureListener {
                    registeredKey = null // let the next sync retry
                    Log.w(TAG, "addGeofences failed", it)
                }
        } catch (e: SecurityException) {
            registeredKey = null
            Log.w(TAG, "addGeofences SecurityException", e)
            LocationPrefs.saveInside(ctx, false)
        }
        logDistanceToHome(ctx, cfg)
    }

    /** Diagnostics: how far Play Services thinks the device is from the saved home point. */
    @android.annotation.SuppressLint("MissingPermission") // register() only runs with geofence permission
    private fun logDistanceToHome(ctx: Context, cfg: LocationConfig) {
        LocationServices.getFusedLocationProviderClient(ctx).lastLocation
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    Log.d(TAG, "geofence: no last location")
                    return@addOnSuccessListener
                }
                val home = Location("dnsly").apply {
                    latitude = cfg.lat
                    longitude = cfg.lng
                }
                Log.d(
                    TAG,
                    "geofence distance=${loc.distanceTo(home).toInt()}m " +
                        "device=${loc.latitude},${loc.longitude} acc=${loc.accuracy}",
                )
            }
    }

    private fun remove(ctx: Context, pending: PendingIntent) {
        registeredKey = null
        try {
            LocationServices.getGeofencingClient(ctx).removeGeofences(pending)
        } catch (_: SecurityException) {
            // Nothing registered or permission gone: either way there is nothing to remove.
        }
    }

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        0,
        Intent(ctx, GeofenceReceiver::class.java),
        // Play Services fills in the transition extras when it sends this: it must be mutable.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}
