package app.dnsly.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.dnsly.data.ServerConfig

/** Mirrors the core engine's states (core/src/engine.rs). */
enum class DnsState(val title: String, val subtitle: String, val color: Color, val icon: ImageVector) {
    DnsActive("DNS ACTIVO", "Todo funcionando correctamente", Palette.green, Ic.ShieldCheck),
    CheckingServer("VERIFICANDO", "Probando el servidor DNS", Palette.blue, Ic.Refresh),
    ServerUnavailable("SERVIDOR CAÍDO", "Usando el DNS de la red · reintentando", Palette.orange, Ic.Alert),
    OutsideGeofence("FUERA DEL ÁREA", "Usando el DNS de la red", Palette.gray, Ic.MapPin),
    VpnError("ERROR DE VPN", "Otra VPN tomó el control. Tocá para reintentar", Palette.red, Ic.Alert),
    Disabled("DESACTIVADO", "La automatización está apagada", Palette.gray, Ic.Power),
}

data class DashboardState(
    val state: DnsState,
    val subtitle: String,
    val privateDnsStrict: Boolean,
    val insideArea: Boolean,
    val areaName: String,
    val serverName: String,
    val serverAddress: String,
    val port: Int,
    val protocol: String,
    val avgRttMs: Int?,
    val checkIntervalS: Int,
    /** Seconds until the next probe, or -1 when none is scheduled. */
    val nextCheckS: Int,
    /** Seconds since the last probe, or -1 when none ran yet. */
    val lastCheckAgoS: Int,
) {
    val serverUp get() = state == DnsState.DnsActive
    val vpnConnected get() = state == DnsState.DnsActive
}

enum class EventKind(val title: String, val color: Color) {
    ServerDown("Servidor no disponible", Palette.orange),
    ServerBack("Servidor recuperado", Palette.green),
    EnterArea("Dentro del área", Palette.green),
    LeaveArea("Fuera del área", Palette.gray),
    VpnError("Error de VPN", Palette.red),
}

data class HistoryEvent(val time: String, val kind: EventKind, val detail: String)

/** Builds the dashboard from live engine data; all values are real (Etapa 6). */
fun dashboard(
    state: DnsState,
    subtitle: String,
    privateDnsStrict: Boolean,
    insideArea: Boolean,
    areaName: String,
    server: ServerConfig,
    rttMs: Double?,
    nextCheckAtMs: Long,
    lastCheckAtMs: Long,
    nowMs: Long,
): DashboardState {
    val nextCheckS = if (nextCheckAtMs > 0) ((nextCheckAtMs - nowMs) / 1000).toInt().coerceAtLeast(0) else -1
    val lastCheckAgoS = if (lastCheckAtMs > 0) ((nowMs - lastCheckAtMs) / 1000).toInt().coerceAtLeast(0) else -1
    return DashboardState(
        state = state,
        subtitle = subtitle,
        privateDnsStrict = privateDnsStrict,
        insideArea = insideArea,
        areaName = areaName,
        serverName = server.name,
        serverAddress = server.address.ifBlank { "Sin configurar" },
        port = server.port,
        protocol = if (server.tcp) "TCP" else "UDP",
        avgRttMs = rttMs?.toInt(),
        checkIntervalS = server.intervalS,
        nextCheckS = nextCheckS,
        lastCheckAgoS = lastCheckAgoS,
    )
}
