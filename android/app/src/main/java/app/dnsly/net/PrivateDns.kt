package app.dnsly.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * True while Private DNS is in strict mode (a hostname is set): Android then sends DNS over TLS
 * to that host and ignores the VPN's DNS, so Dnsly cannot take effect (ARCHITECTURE §2).
 * Uses this app's default network, which is the real one: the app is excluded from its own VPN.
 */
fun privateDnsStrict(ctx: Context): Flow<Boolean> = callbackFlow {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        trySend(false) // Private DNS exists since Android 9
        awaitClose()
        return@callbackFlow
    }
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            trySend(lp.privateDnsServerName != null)
        }

        override fun onLost(network: Network) {
            trySend(false)
        }
    }
    cm.registerDefaultNetworkCallback(cb)
    awaitClose { cm.unregisterNetworkCallback(cb) }
}.distinctUntilChanged()
