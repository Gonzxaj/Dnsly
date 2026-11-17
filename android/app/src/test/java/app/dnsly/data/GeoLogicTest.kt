package app.dnsly.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoLogicTest {
    private fun cfg(enabled: Boolean, inside: Boolean) = LocationConfig(
        hasLocation = true,
        lat = 0.0,
        lng = 0.0,
        radiusM = 50,
        geofenceEnabled = enabled,
        lastInside = inside,
        insideAtMs = 0,
    )

    @Test
    fun geofenceOffAlwaysMeansInside() {
        assertTrue(effectiveInside(cfg(enabled = false, inside = false)))
        assertTrue(effectiveInside(cfg(enabled = false, inside = true)))
    }

    @Test
    fun geofenceOnFollowsLastTransition() {
        assertTrue(effectiveInside(cfg(enabled = true, inside = true)))
        assertFalse(effectiveInside(cfg(enabled = true, inside = false)))
    }
}
