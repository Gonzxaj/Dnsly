package app.dnsly.core

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.dnsly.data.AutomationPrefs
import app.dnsly.data.LocationPrefs
import app.dnsly.data.ServerConfig
import app.dnsly.data.ServerPrefs
import app.dnsly.data.effectiveInside
import app.dnsly.data.history.HistoryStore
import app.dnsly.data.history.historyForTransition
import app.dnsly.geo.GeofenceManager
import app.dnsly.geo.LocationAccess
import app.dnsly.net.NetworkMonitor
import app.dnsly.notify.Notifier
import app.dnsly.service.DnslyVpnService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the UI needs to render the Ubicación screen. */
data class GeoState(
    val hasLocation: Boolean = false,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val radiusM: Int = 50,
    val geofenceEnabled: Boolean = false,
    val inside: Boolean = true,
    val insideAtMs: Long = 0,
    val fineGranted: Boolean = false,
    val backgroundGranted: Boolean = false,
)

/** Probe timing for the dashboard: epoch millis of the last run and the next scheduled one. */
data class ProbeStats(
    val lastCheckAtMs: Long = 0,
    val nextCheckAtMs: Long = 0,
    /** Exponential moving average of successful probe RTTs; null until the first success. */
    val rttMs: Double? = null,
)

/**
 * Owns the Rust engine on Android (ARCHITECTURE §4): feeds it events (enable, geofence,
 * network, probe results, VPN failures), executes the returned actions (VPN start/stop,
 * probe timers) and exposes the state to the UI.
 *
 * Every engine call is serialized on [engineExecutor]; probe results are posted back to it,
 * so the engine is only ever touched from that one thread.
 */
object EngineDriver {
    private const val TAG = "Dnsly"

    // Event names understood by `decode_event` in core/src/jni.rs.
    private const val EV_SET_ENABLED = "set_enabled"
    private const val EV_GEOFENCE = "geofence"
    private const val EV_NETWORK = "network"
    private const val EV_PROBE_DONE = "probe_done"
    private const val EV_QUERY_TIMEOUT = "query_timeout"
    private const val EV_VPN_FAILED = "vpn_failed"
    private const val EV_RETRY_VPN = "retry_vpn"

    // Engine timings with no user-facing setting; same defaults as core/src/engine.rs.
    private const val RETRY_DELAY_MS = 2_000L
    private const val BACKOFF_BASE_MS = 15_000L
    private const val BACKOFF_MAX_MS = 15 * 60_000L
    private const val QUERY_TIMEOUT_MIN_MS = 1_000L

    private val engineExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "dnsly-engine") }
    private val probeExecutor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dnsly-probe") }

    private var app: Context? = null
    private var engine: Long = 0
    private var monitor: NetworkMonitor? = null

    @Volatile private var serverConfig = ServerConfig()
    @Volatile private var networkAvailable = true

    // Touched only on engineExecutor.
    private var probe: ScheduledFuture<*>? = null

    @Volatile private var currentState = EngineState.Disabled
    private val _state = MutableStateFlow(currentState)
    val state: StateFlow<EngineState> = _state
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled
    private val _inside = MutableStateFlow(true)
    val insideArea: StateFlow<Boolean> = _inside
    private val _geo = MutableStateFlow(GeoState())
    val geo: StateFlow<GeoState> = _geo
    private val _probeStats = MutableStateFlow(ProbeStats())
    val probeStats: StateFlow<ProbeStats> = _probeStats
    private val _autoReactivate = MutableStateFlow(true)
    val autoReactivate: StateFlow<Boolean> = _autoReactivate

    private val lastQueryTimeout = AtomicLong(0)

    /** Passed to the VPN service so expired user queries reach the engine (QueryTimeout). */
    val forwarderListener = ForwarderListener { onQueryTimeout() }

    /** Idempotent: creates the engine, the network monitor and replays the current facts. */
    @Synchronized
    fun ensure(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        Notifier.ensureChannels(ctx)
        HistoryStore.ensure(ctx)
        serverConfig = ServerPrefs.load(ctx)
        _autoReactivate.value = AutomationPrefs.autoReactivate(ctx)
        val on = AutomationPrefs.enabled(ctx)
        _enabled.value = on

        engine = newEngine(serverConfig)
        monitor = NetworkMonitor(ctx) { available ->
            networkAvailable = available
            postEvent(EV_NETWORK, if (available) 1 else 0)
        }.also { it.start() }

        // Replay the facts so the engine re-derives its state (ARCHITECTURE §5).
        postEvent(EV_NETWORK, if (monitor?.isAvailable() == true) 1 else 0)
        syncGeo(ctx)
        postEvent(EV_SET_ENABLED, if (on) 1 else 0)
    }

    /** Power button / automation switch. Persists the choice and feeds the engine. */
    fun setEnabled(on: Boolean) {
        val ctx = app ?: return
        AutomationPrefs.save(ctx, on)
        _enabled.value = on
        postEvent(EV_SET_ENABLED, if (on) 1 else 0)
    }

    /** Re-reads the location prefs, updates the geo flows and feeds the engine the effective fact. */
    fun refreshGeo(context: Context) {
        ensure(context)
        val ctx = app ?: return
        val cfg = LocationPrefs.load(ctx)
        val inside = effectiveInside(cfg)
        Log.d(
            TAG,
            "refreshGeo geofence=${cfg.geofenceEnabled} hasLoc=${cfg.hasLocation} " +
                "lastInside=${cfg.lastInside} inside=$inside",
        )
        _geo.value = GeoState(
            hasLocation = cfg.hasLocation,
            lat = cfg.lat,
            lng = cfg.lng,
            radiusM = cfg.radiusM,
            geofenceEnabled = cfg.geofenceEnabled,
            inside = inside,
            insideAtMs = cfg.insideAtMs,
            fineGranted = LocationAccess.hasFine(ctx),
            backgroundGranted = LocationAccess.hasBackground(ctx),
        )
        _inside.value = inside
        postEvent(EV_GEOFENCE, if (inside) 1 else 0)
    }

    /** Re-registers the geofence from the prefs and refreshes. Call on resume and permission changes. */
    fun syncGeo(context: Context) {
        ensure(context)
        val ctx = app ?: return
        GeofenceManager.sync(ctx)
        refreshGeo(ctx)
    }

    /** "Usar mi ubicación actual": saves the home point and (re)registers the geofence. */
    fun setHomeLocation(context: Context, lat: Double, lng: Double) {
        ensure(context)
        val ctx = app ?: return
        LocationPrefs.saveLocation(ctx, lat, lng)
        GeofenceManager.sync(ctx)
        refreshGeo(ctx)
    }

    fun setRadius(context: Context, radiusM: Int) {
        ensure(context)
        val ctx = app ?: return
        LocationPrefs.saveRadius(ctx, radiusM)
        GeofenceManager.sync(ctx)
        refreshGeo(ctx)
    }

    fun setGeofenceEnabled(context: Context, enabled: Boolean) {
        ensure(context)
        val ctx = app ?: return
        LocationPrefs.saveGeofence(ctx, enabled)
        GeofenceManager.sync(ctx)
        refreshGeo(ctx)
    }

    /** "Reactivación automática" switch: persists and rebuilds the engine with the new flag. */
    fun setAutoReactivate(on: Boolean) {
        val ctx = app ?: return
        AutomationPrefs.saveAutoReactivate(ctx, on)
        _autoReactivate.value = on
        reloadConfig(ctx)
    }

    /** ENTER/EXIT transition reported by GeofenceReceiver. */
    fun onGeofenceInside(context: Context, inside: Boolean) {
        ensure(context)
        val ctx = app ?: return
        LocationPrefs.saveInside(ctx, inside)
        refreshGeo(ctx)
    }

    /** User retry after VPN_ERROR. */
    fun retryVpn() = postEvent(EV_RETRY_VPN)

    /** Called by the VPN service when establish fails or the VPN is revoked. */
    fun onVpnFailed() = postEvent(EV_VPN_FAILED)

    /** Passive signal from the forwarder; throttled, the engine ignores it unless DNS is active. */
    fun onQueryTimeout() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastQueryTimeout.getAndSet(now) < QUERY_TIMEOUT_MIN_MS) return
        postEvent(EV_QUERY_TIMEOUT)
    }

    /** Whether the engine currently wants the VPN up (used by the service on (re)start). */
    fun vpnWanted(): Boolean = currentState == EngineState.DnsActive

    /** Rebuilds the engine with the current server settings and re-derives the state. */
    fun reloadConfig(context: Context) {
        ensure(context)
        engineExecutor.execute {
            val ctx = app ?: return@execute
            serverConfig = ServerPrefs.load(ctx)
            cancelProbe()
            NativeCore.engineFree(engine)
            engine = newEngine(serverConfig)
            postEvent(EV_NETWORK, if (networkAvailable) 1 else 0)
            syncGeo(ctx)
            postEvent(EV_SET_ENABLED, if (_enabled.value) 1 else 0)
        }
    }

    private fun newEngine(cfg: ServerConfig): Long {
        val threshold = cfg.failures.coerceIn(1, 100)
        return NativeCore.engineNew(
            failThreshold = threshold,
            recoverThreshold = threshold,
            checkIntervalMs = cfg.intervalS.coerceIn(5, 3600) * 1000L,
            retryDelayMs = RETRY_DELAY_MS,
            backoffBaseMs = BACKOFF_BASE_MS,
            backoffMaxMs = BACKOFF_MAX_MS,
            autoRecover = app?.let { AutomationPrefs.autoReactivate(it) } ?: true,
        )
    }

    private fun postEvent(event: String, p1: Long = 0, p2: Long = 0) {
        engineExecutor.execute { dispatch(event, p1, p2) }
    }

    private fun dispatch(event: String, p1: Long, p2: Long) {
        val h = engine
        if (h == 0L) return
        val actions = EngineActions.parse(NativeCore.engineHandle(h, event, p1, p2))
        val previous = currentState
        currentState = EngineActions.parseState(NativeCore.engineState(h))
        _state.value = currentState
        Log.d(TAG, "event=$event state=$previous->$currentState actions=$actions")
        if (currentState != previous) onStateChanged(previous, currentState)
        actions.forEach(::execute)
    }

    private fun execute(action: EngineAction) {
        Log.d(TAG, "execute $action")
        when (action) {
            EngineAction.StartVpn -> startVpn()
            EngineAction.StopVpn -> stopVpn()
            EngineAction.CancelProbe -> {
                cancelProbe()
                _probeStats.update { it.copy(nextCheckAtMs = 0) }
            }
            is EngineAction.ScheduleProbe -> scheduleProbe(action)
        }
    }

    private fun startVpn() {
        val ctx = app ?: return
        try {
            ctx.startForegroundService(Intent(ctx, DnslyVpnService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService failed", e)
            // Background-start restriction or missing consent: the engine decides (VPN_ERROR).
            postEvent(EV_VPN_FAILED)
        }
    }

    private fun stopVpn() {
        val ctx = app ?: return
        try {
            // A bound VpnService ignores stopService() until the system unbinds, which only
            // happens after the TUN fd closes: the service must close it itself (ACTION_STOP).
            ctx.startService(Intent(ctx, DnslyVpnService::class.java).setAction(DnslyVpnService.ACTION_STOP))
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_STOP startService failed, falling back to stopService", e)
            ctx.stopService(Intent(ctx, DnslyVpnService::class.java))
        }
    }

    private fun scheduleProbe(action: EngineAction.ScheduleProbe) {
        probe?.cancel(false)
        probe = probeExecutor.schedule(
            { runProbe(action.generation) },
            action.delayMs,
            TimeUnit.MILLISECONDS,
        )
        _probeStats.update {
            it.copy(nextCheckAtMs = System.currentTimeMillis() + action.delayMs)
        }
    }

    private fun cancelProbe() {
        probe?.cancel(false)
        probe = null
    }

    private fun runProbe(generation: Long) {
        val cfg = serverConfig
        val result = try {
            NativeCore.probe(cfg.address, cfg.port, cfg.tcp, cfg.domain, cfg.timeoutS * 1000)
        } catch (e: Exception) {
            ProbeResult.Failed(e.message ?: "probe failed")
        }
        _probeStats.update { s ->
            s.copy(
                lastCheckAtMs = System.currentTimeMillis(),
                rttMs = when (result) {
                    is ProbeResult.Ok -> s.rttMs?.let { it * 0.7 + result.rttMs * 0.3 } ?: result.rttMs
                    is ProbeResult.Failed -> s.rttMs
                },
            )
        }
        postEvent(EV_PROBE_DONE, generation, if (result is ProbeResult.Ok) 1 else 0)
    }

    private fun onStateChanged(previous: EngineState, now: EngineState) {
        val ctx = app ?: return
        historyForTransition(previous, now)?.let { HistoryStore.record(it.kind, it.detail) }
        when (now) {
            EngineState.ServerUnavailable ->
                Notifier.postEvent(ctx, "Servidor DNS no disponible", "Dnsly usa el DNS de la red y sigue reintentando.")
            EngineState.VpnError ->
                Notifier.postEvent(ctx, "Error de VPN", "No se pudo mantener la VPN local de DNS. Abrí Dnsly para reintentar.")
            else -> Unit
        }
    }
}
