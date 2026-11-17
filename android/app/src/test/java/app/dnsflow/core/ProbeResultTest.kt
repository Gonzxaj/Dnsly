package app.dnsflow.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeResultTest {
    @Test
    fun parsesOk() {
        assertEquals(ProbeResult.Ok(1.234, 3), ProbeResult.parse("ok 1234 3"))
    }

    @Test
    fun parsesFailureWithSpaces() {
        assertEquals(
            ProbeResult.Failed("Connection refused (os error 111)"),
            ProbeResult.parse("err Connection refused (os error 111)"),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsGarbage() {
        ProbeResult.parse("nope")
    }
}
