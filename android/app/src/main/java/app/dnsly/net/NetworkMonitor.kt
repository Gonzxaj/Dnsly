package app.dnsly.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/** Reports default-network availability to the engine driver. */
class NetworkMonitor(context: Context, private val onChange: (Boolean) -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChange(true)
        override fun onLost(network: Network) = onChange(false)
    }

    /** Whether a default network exists right now (before any callback fires). */
    fun isAvailable(): Boolean = cm.activeNetwork != null

    fun start() = cm.registerDefaultNetworkCallback(callback)

    fun stop() = cm.unregisterNetworkCallback(callback)
}
