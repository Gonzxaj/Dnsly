package app.dnsly.data

import app.dnsly.core.EngineState
import app.dnsly.data.history.HistoryKind
import app.dnsly.data.history.historyForTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryMappingTest {
    @Test
    fun routine_dns_on_off_is_not_recorded() {
        // DNS activating by itself is the point of the app: not worth a history entry.
        assertNull(historyForTransition(EngineState.CheckingServer, EngineState.DnsActive))
        assertNull(historyForTransition(EngineState.DnsActive, EngineState.Disabled))
        assertNull(historyForTransition(EngineState.Disabled, EngineState.CheckingServer))
    }

    @Test
    fun server_failure_and_recovery_are_recorded() {
        assertEquals(
            HistoryKind.SERVER_DOWN,
            historyForTransition(EngineState.DnsActive, EngineState.ServerUnavailable)?.kind,
        )
        assertEquals(
            HistoryKind.SERVER_BACK,
            historyForTransition(EngineState.ServerUnavailable, EngineState.DnsActive)?.kind,
        )
    }

    @Test
    fun vpn_error_is_recorded() {
        assertEquals(
            HistoryKind.VPN_ERROR,
            historyForTransition(EngineState.DnsActive, EngineState.VpnError)?.kind,
        )
    }

    @Test
    fun geofence_edges_are_recorded() {
        assertEquals(
            HistoryKind.LEAVE_AREA,
            historyForTransition(EngineState.DnsActive, EngineState.OutsideGeofence)?.kind,
        )
        assertEquals(
            HistoryKind.ENTER_AREA,
            historyForTransition(EngineState.OutsideGeofence, EngineState.CheckingServer)?.kind,
        )
        assertEquals(
            HistoryKind.LEAVE_AREA,
            historyForTransition(EngineState.ServerUnavailable, EngineState.OutsideGeofence)?.kind,
        )
    }
}
