package app.dnsly.geo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicBoolean

/** Location permission checks and a one-shot current-location fix. */
object LocationAccess {
    /** `lastLocation` younger than this is returned instantly without a fresh fix. */
    private const val FRESH_MS = 2 * 60 * 1000L

    /**
     * A stale `lastLocation` is still better than nothing on failure, but only up to this age:
     * older fixes could be from another city and would silently place the geofence wrong.
     */
    private const val FALLBACK_MS = 30 * 60 * 1000L

    /** Upper bound for `getCurrentLocation`, which otherwise can wait for a fix indefinitely. */
    private const val FIX_TIMEOUT_MS = 10_000L

    fun hasFine(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** GeofencingClient needs both: FINE to register, BACKGROUND to wake the app on transitions. */
    fun hasGeofencePermission(ctx: Context): Boolean = hasFine(ctx) && hasBackground(ctx)

    /**
     * Best-effort current fix; calls back on the main thread with null on failure.
     * Order: fresh `lastLocation` (instant) -> `getCurrentLocation` bounded by [FIX_TIMEOUT_MS]
     * -> stale `lastLocation` as fallback. API < 30 only has `lastLocation`.
     */
    @SuppressLint("MissingPermission") // callers check hasFine first
    fun fetchCurrent(ctx: Context, onResult: (Location?) -> Unit) {
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        client.lastLocation.addOnCompleteListener { lastTask ->
            val last = lastTask.result
            val fresh = last != null && System.currentTimeMillis() - last.time < FRESH_MS
            if (fresh || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                onResult(last)
                return@addOnCompleteListener
            }
            freshFix(client, last, onResult)
        }
    }

    @SuppressLint("MissingPermission") // caller checked hasFine
    private fun freshFix(
        client: FusedLocationProviderClient,
        fallback: Location?,
        onResult: (Location?) -> Unit,
    ) {
        val done = AtomicBoolean(false)
        val fallbackOk = fallback?.takeIf {
            System.currentTimeMillis() - it.time < FALLBACK_MS
        }
        val finish = { loc: Location? -> if (done.compareAndSet(false, true)) onResult(loc) }
        Handler(Looper.getMainLooper()).postDelayed({ finish(fallbackOk) }, FIX_TIMEOUT_MS)
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { finish(it ?: fallbackOk) }
            .addOnFailureListener { finish(fallbackOk) }
    }
}
