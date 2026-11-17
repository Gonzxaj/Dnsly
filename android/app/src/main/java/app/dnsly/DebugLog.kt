package app.dnsly

import android.util.Log

/**
 * Debug diagnostics (engine events, geofence coordinates): compiled out of release builds by
 * R8 since [BuildConfig.DEBUG] is a compile-time constant. Log.w calls stay: rare error paths
 * are useful in release builds.
 */
internal inline fun debugLog(tag: String, message: () -> String) {
    if (BuildConfig.DEBUG) Log.d(tag, message())
}

internal fun debugLog(tag: String, message: String) = debugLog(tag) { message }
