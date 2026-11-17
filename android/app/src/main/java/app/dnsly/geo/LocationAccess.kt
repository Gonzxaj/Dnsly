package app.dnsly.geo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Location permission checks and a one-shot current-location fix. */
object LocationAccess {
    fun hasFine(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** GeofencingClient needs both: FINE to register, BACKGROUND to wake the app on transitions. */
    fun hasGeofencePermission(ctx: Context): Boolean = hasFine(ctx) && hasBackground(ctx)

    /**
     * Best-effort current fix; calls back on the main thread with null on failure.
     * `lastLocation` on API < 30 can be stale or null; `getCurrentLocation` asks for a fresh fix.
     */
    @SuppressLint("MissingPermission") // callers check hasFine first
    fun fetchCurrent(ctx: Context, onResult: (Location?) -> Unit) {
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val task = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
        } else {
            @Suppress("MissingPermission") // caller checked hasFine
            client.lastLocation
        }
        task.addOnSuccessListener { onResult(it) }
            .addOnFailureListener { onResult(null) }
    }
}
