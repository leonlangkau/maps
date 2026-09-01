package au.radar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {

    @Test
    fun `distance between a point and itself is zero`() {
        assertEquals(0.0, Geo.distanceM(-33.87, 151.21, -33.87, 151.21), 0.0001)
    }

    @Test
    fun `Sydney to Melbourne is about 713 kilometres`() {
        val d = Geo.distanceM(-33.8568, 151.2153, -37.8183, 144.9671)
        assertTrue(d in 705_000.0..720_000.0, "got $d")
    }

    @Test
    fun `bearings read as compass directions`() {
        assertEquals(0.0, Geo.bearingDeg(-33.87, 151.21, -33.86, 151.21), 0.1)
        assertEquals(90.0, Geo.bearingDeg(-33.87, 151.21, -33.87, 151.22), 0.1)
        assertEquals(180.0, Geo.bearingDeg(-33.87, 151.21, -33.88, 151.21), 0.1)
        assertEquals(270.0, Geo.bearingDeg(-33.87, 151.21, -33.87, 151.20), 0.1)
    }

    @Test
    fun `bearing delta wraps across north`() {
        assertEquals(0.0, Geo.bearingDelta(0.0, 0.0), 0.0001)
        assertEquals(20.0, Geo.bearingDelta(10.0, 350.0), 0.0001)
        assertEquals(20.0, Geo.bearingDelta(350.0, 10.0), 0.0001)
        assertEquals(180.0, Geo.bearingDelta(0.0, 180.0), 0.0001)
    }

    @Test
    fun `bearing delta stays within zero and one eighty`() {
        var a = 0.0
        while (a < 360.0) {
            var b = 0.0
            while (b < 360.0) {
                val d = Geo.bearingDelta(a, b)
                assertTrue(d in 0.0..180.0, "delta($a, $b) = $d")
                b += 13.0
            }
            a += 7.0
        }
    }

    @Test
    fun `distance matches the Kotlin and TypeScript implementations`() {
        // The same pair is asserted in the Worker's geo tests, so a change in one
        // implementation shows up as a mismatch rather than a silent divergence.
        val d = Geo.distanceM(-33.87, 151.21, -33.88, 151.21)
        assertTrue(d in 1_090.0..1_120.0, "got $d")
    }
}
