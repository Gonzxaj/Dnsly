package app.dnsly.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.util.Log
import app.dnsly.core.EngineDriver
import app.dnsly.core.NativeCore
import app.dnsly.data.ServerPrefs
import app.dnsly.debugLog
import app.dnsly.notify.Notifier
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface VpnStatus {
    data object Stopped : VpnStatus
    data object Starting : VpnStatus
    data object Running : VpnStatus
    data class Error(val message: String) : VpnStatus
}

/**
 * DNS-only local VPN (ARCHITECTURE §1): routes only the virtual DNS addresses into the TUN and
 * hands the fd to the Rust forwarder. All other traffic keeps using the normal network.
 *
 * Etapa 4: runs as a foreground service (`specialUse`/`vpn`); started and stopped exclusively
 * by [EngineDriver] actions. `START_STICKY` + the `vpnWanted` guard make a system restart
 * re-derive safely: if the engine does not want the VPN, the service shuts itself down.
 */
class DnslyVpnService : VpnService() {
    companion object {
        private const val TAG = "Dnsly"
        private const val NOTIFICATION_ID = Notifier.VPN_NOTIFICATION_ID

        /**
         * Sent by EngineDriver to tear the VPN down. A bound VpnService is not destroyed by
         * stopService() alone, so the engine cannot rely on onDestroy to close the TUN fd:
         * this action runs the shutdown on the worker, which closes the fd and lets the
         * system unbind and destroy the service.
         */
        const val ACTION_STOP = "app.dnsly.action.STOP_VPN"

        // Virtual addresses; the DNS ones must match DNS_V4 / DNS_V6 in core/src/forwarder.rs.
        private const val CLIENT_V4 = "10.111.222.2"
        private const val DNS_V4 = "10.111.222.1"
        private const val CLIENT_V6 = "fd00:111:222::2"
        private const val DNS_V6 = "fd00:111:222::1"

        private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Stopped)
        val status: StateFlow<VpnStatus> = _status
    }

    // Start/stop run in order on one thread; `forwarder` is only touched there.
    private val worker = Executors.newSingleThreadExecutor()
    private var forwarder = 0L

    override fun onCreate() {
        super.onCreate()
        debugLog(TAG, "service onCreate")
        EngineDriver.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            debugLog(TAG, "service ACTION_STOP")
            worker.execute { shutdown(VpnStatus.Stopped) }
            return START_STICKY
        }
        debugLog(TAG, "service onStartCommand wanted=${EngineDriver.vpnWanted()}")
        // Must happen promptly after startForegroundService; the system kills the app otherwise.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                Notifier.vpnNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, Notifier.vpnNotification(this))
        }
        worker.execute {
            when {
                forwarder != 0L -> Unit // already running: a repeated StartVpn is a no-op
                EngineDriver.vpnWanted() -> startVpn()
                else -> shutdown(VpnStatus.Stopped)
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "service onRevoke")
        worker.execute {
            // The system took the VPN away (another VPN, revoked consent): the engine must know.
            EngineDriver.onVpnFailed()
            shutdown(VpnStatus.Error("Otra VPN tomó el control o se quitó el permiso de VPN"))
        }
    }

    override fun onDestroy() {
        debugLog(TAG, "service onDestroy forwarder=$forwarder")
        // Only if still running: after shutdown() the status is already final, and a newer
        // instance may have started meanwhile.
        worker.execute { if (forwarder != 0L) shutdown(VpnStatus.Stopped) }
        worker.shutdown()
        super.onDestroy()
    }

    private fun startVpn() {
        _status.value = VpnStatus.Starting
        val cfg = ServerPrefs.load(this)
        if (!cfg.isConfigured) {
            EngineDriver.onVpnFailed()
            return shutdown(VpnStatus.Error("Configurá el servidor DNS"))
        }

        val pfd = try {
            Builder()
                .setSession("Dnsly")
                .setMtu(1500)
                .addAddress(CLIENT_V4, 32)
                .addRoute(DNS_V4, 32)
                .addDnsServer(DNS_V4)
                // IPv6 too, so dual-stack and IPv6-only networks do not resolve outside the tunnel.
                .addAddress(CLIENT_V6, 128)
                .addRoute(DNS_V6, 128)
                .addDnsServer(DNS_V6)
                // Our own sockets (forwarder, probes) bypass the VPN: no loop, no protect() calls.
                .addDisallowedApplication(packageName)
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false) }
                .establish()
        } catch (e: Exception) {
            Log.w(TAG, "establish() threw", e)
            null
        } ?: run {
            EngineDriver.onVpnFailed()
            return shutdown(VpnStatus.Error("No se pudo iniciar la VPN. ¿Hay otra VPN siempre activa?"))
        }

        val handle = NativeCore.forwarderStart(
            pfd.detachFd(),
            cfg.address,
            cfg.port,
            cfg.timeoutS * 1000,
            EngineDriver.forwarderListener,
        )
        if (handle == 0L) {
            EngineDriver.onVpnFailed()
            return shutdown(VpnStatus.Error("Dirección del servidor inválida: ${cfg.address}"))
        }
        forwarder = handle
        _status.value = VpnStatus.Running
        debugLog(TAG, "vpn running forwarder=$handle")
    }

    private fun stopForwarder() {
        if (forwarder != 0L) {
            debugLog(TAG, "stopForwarder $forwarder")
            NativeCore.forwarderStop(forwarder) // closes the TUN fd: Android drops the VPN
            forwarder = 0L
        }
    }

    private fun shutdown(final: VpnStatus) {
        stopForwarder()
        _status.value = final
        stopSelf()
    }
}
