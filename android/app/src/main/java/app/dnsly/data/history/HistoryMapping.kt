package app.dnsly.data.history

import app.dnsly.core.EngineState

/** Values stored in [HistoryEntry.kind]; the UI maps them to its own event kinds. */
object HistoryKind {
    const val SERVER_DOWN = "server_down"
    const val SERVER_BACK = "server_back"
    const val ENTER_AREA = "enter_area"
    const val LEAVE_AREA = "leave_area"
    const val VPN_ERROR = "vpn_error"
}

data class HistoryRecord(val kind: String, val detail: String)

/**
 * Maps an engine state transition to a history record (PLAN §5, Historial), or null when the
 * transition is not user-relevant (e.g. entering `CheckingServer`). Pure: unit-testable.
 */
fun historyForTransition(previous: EngineState, current: EngineState): HistoryRecord? = when {
    // Routine DNS on/off transitions are not recorded: the point of the app is that the DNS
    // activates by itself, so only anomalies and area changes are worth keeping.
    current == EngineState.ServerUnavailable ->
        HistoryRecord(HistoryKind.SERVER_DOWN, "El servidor dejó de responder")
    current == EngineState.DnsActive && previous == EngineState.ServerUnavailable ->
        HistoryRecord(HistoryKind.SERVER_BACK, "El servidor DNS volvió a responder")
    current == EngineState.VpnError ->
        HistoryRecord(HistoryKind.VPN_ERROR, "No se pudo mantener la VPN local")
    current == EngineState.OutsideGeofence ->
        HistoryRecord(HistoryKind.LEAVE_AREA, "Saliste del área configurada")
    previous == EngineState.OutsideGeofence ->
        HistoryRecord(HistoryKind.ENTER_AREA, "Entraste al área configurada")
    else -> null
}
