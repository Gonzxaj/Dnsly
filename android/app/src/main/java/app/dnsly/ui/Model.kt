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
    val nextCheckS: Int,
    val lastCheckAgoS: Int,
) {
    val serverUp get() = state == DnsState.DnsActive
    val vpnConnected get() = state == DnsState.DnsActive
}

enum class EventKind(val title: String, val color: Color) {
    DnsOn("DNS activado", Palette.green),
    DnsOff("DNS desactivado", Palette.orange),
    ServerDown("Servidor no disponible", Palette.orange),
    ServerBack("Servidor recuperado", Palette.green),
    EnterArea("Dentro del área", Palette.green),
    LeaveArea("Fuera del área", Palette.gray),
    VpnError("Error de VPN", Palette.red),
}

data class HistoryEvent(val time: String, val kind: EventKind, val detail: String)

// shortcut: area name, RTT, check timing and history are demo data until history is stored
// (Room, Etapa 6). State, server and area come from the engine driver.
object Demo {
    fun dashboard(
        state: DnsState,
        subtitle: String,
        privateDnsStrict: Boolean,
        server: ServerConfig,
        insideArea: Boolean,
    ) = DashboardState(
        state = state,
        subtitle = subtitle,
        privateDnsStrict = privateDnsStrict,
        insideArea = insideArea,
        areaName = "Casa",
        serverName = server.name,
        serverAddress = server.address.ifBlank { "Sin configurar" },
        port = server.port,
        protocol = if (server.tcp) "TCP" else "UDP",
        avgRttMs = if (state == DnsState.DnsActive) 12 else null,
        checkIntervalS = server.intervalS,
        nextCheckS = 9,
        lastCheckAgoS = 3,
    )

    val history = listOf(
        HistoryEvent("09:30", EventKind.DnsOn, "Todas las condiciones se cumplen"),
        HistoryEvent("09:15", EventKind.ServerBack, "El servidor DNS volvió a responder"),
        HistoryEvent("09:15", EventKind.DnsOff, "Servidor no disponible"),
        HistoryEvent("09:14", EventKind.ServerDown, "3 fallos consecutivos (timeout)"),
        HistoryEvent("09:10", EventKind.LeaveArea, "Saliste del área configurada"),
        HistoryEvent("08:45", EventKind.EnterArea, "Entraste al área configurada"),
        HistoryEvent("08:45", EventKind.DnsOn, "Todas las condiciones se cumplen"),
    )
}
