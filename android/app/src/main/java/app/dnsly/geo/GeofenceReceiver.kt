package app.dnsly.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.dnsly.core.EngineDriver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/** Receives ENTER/EXIT transitions (and the initial trigger) and feeds them to the engine. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "geofence broadcast received extras=${intent.extras?.keySet()}")
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            Log.w(TAG, "geofence event null extras=${intent.extras}")
            return
        }
        if (event.hasError()) {
            Log.w(TAG, "geofence event error=${event.errorCode}")
            return
        }
        val inside = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> {
                // Unknown transition types must not toggle the area state.
                Log.w(TAG, "geofence transition ignored: ${event.geofenceTransition}")
                return
            }
        }
        val loc = event.triggeringLocation
        Log.d(
            TAG,
            "geofence transition=$inside at=${loc?.latitude},${loc?.longitude} acc=${loc?.accuracy}",
        )
        EngineDriver.onGeofenceInside(context, inside)
    }

    private companion object {
        const val TAG = "Dnsly"
    }
}
