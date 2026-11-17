package app.dnsly.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/** Reports default-network availability to the engine driver. */
class NetworkMonitor(context: Context, private val onChange: (Boolean) -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    // Reports the current state instead of the event's direction: on a network switch the
    // onLost/onAvailable pair can arrive out of order, and a stale "lost" would leave the
    // engine believing there is no network.
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = report()
        override fun onLost(network: Network) = report()
    }

    /** Whether a default network exists right now (before any callback fires). */
    fun isAvailable(): Boolean = cm.activeNetwork != null

    private fun report() = onChange(isAvailable())

    fun start() = cm.registerDefaultNetworkCallback(callback)

    fun stop() = cm.unregisterNetworkCallback(callback)
}
