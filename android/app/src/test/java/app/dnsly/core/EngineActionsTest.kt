package app.dnsly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineActionsTest {
    @Test
    fun parsesEmpty() {
        assertTrue(EngineActions.parse("").isEmpty())
    }

    @Test
    fun parsesAllActions() {
        assertEquals(
            listOf(
                EngineAction.StopVpn,
                EngineAction.ScheduleProbe(delayMs = 2000, generation = 3),
                EngineAction.CancelProbe,
                EngineAction.StartVpn,
            ),
            EngineActions.parse("stop_vpn;schedule_probe:2000:3;cancel_probe;start_vpn"),
        )
    }

    @Test
    fun rejectsGarbage() {
        assertThrows(IllegalStateException::class.java) { EngineActions.parse("bogus") }
        assertThrows(IllegalArgumentException::class.java) {
            EngineActions.parse("schedule_probe:2000")
        }
        assertThrows(NumberFormatException::class.java) {
            EngineActions.parse("schedule_probe:x:3")
        }
    }

    @Test
    fun parsesStates() {
        assertEquals(EngineState.DnsActive, EngineActions.parseState("dns_active"))
        assertEquals(EngineState.ServerUnavailable, EngineActions.parseState("server_unavailable"))
        assertEquals(EngineState.Disabled, EngineActions.parseState("disabled"))
        assertThrows(IllegalStateException::class.java) { EngineActions.parseState("nope") }
    }
}
