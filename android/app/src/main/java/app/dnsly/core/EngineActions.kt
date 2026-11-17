package app.dnsly.core

/** Mirrors the core engine's states (core/src/engine.rs, `encode_state` in jni.rs). */
enum class EngineState {
    Disabled,
    OutsideGeofence,
    CheckingServer,
    DnsActive,
    ServerUnavailable,
    VpnError,
}

sealed interface EngineAction {
    data object StartVpn : EngineAction
    data object StopVpn : EngineAction
    data object CancelProbe : EngineAction
    data class ScheduleProbe(val delayMs: Long, val generation: Long) : EngineAction
}

/**
 * Parses what `NativeCore.engineHandle` returns: `;`-separated action tokens
 * (`start_vpn`, `schedule_probe:<delay_ms>:<generation>`, ...), see `encode_actions` in jni.rs.
 * Pure Kotlin on purpose: unit-testable without Android.
 */
object EngineActions {
    fun parse(encoded: String): List<EngineAction> {
        if (encoded.isEmpty()) return emptyList()
        return encoded.split(';').map(::parseAction)
    }

    fun parseState(name: String): EngineState = when (name) {
        "disabled" -> EngineState.Disabled
        "outside_geofence" -> EngineState.OutsideGeofence
        "checking_server" -> EngineState.CheckingServer
        "dns_active" -> EngineState.DnsActive
        "server_unavailable" -> EngineState.ServerUnavailable
        "vpn_error" -> EngineState.VpnError
        else -> error("unexpected engine state: $name")
    }

    private fun parseAction(token: String): EngineAction {
        val parts = token.split(':')
        return when (parts[0]) {
            "start_vpn" -> EngineAction.StartVpn
            "stop_vpn" -> EngineAction.StopVpn
            "cancel_probe" -> EngineAction.CancelProbe
            "schedule_probe" -> {
                require(parts.size == 3) { "bad engine action: $token" }
                EngineAction.ScheduleProbe(parts[1].toLong(), parts[2].toLong())
            }
            else -> error("unexpected engine action: $token")
        }
    }
}
