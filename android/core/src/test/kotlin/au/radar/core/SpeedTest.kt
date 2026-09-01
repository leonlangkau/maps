package au.radar.core

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The speedometer has to be both accurate and steady, and those pull against
 * each other. These tests pin both ends: it must settle on a true constant, and
 * it must not lag a real change.
 */
class SpeedFilterTest {

    private fun feed(
        filter: SpeedFilter,
        values: List<Double>,
        startMs: Long = 1_000,
        stepMs: Long = 1_000,
        accuracy: Double? = 0.5,
    ): List<SpeedReading> = values.mapIndexed { i, value ->
        filter.update(SpeedSample(value, accuracy, startMs + i * stepMs))
    }

    @Test
    fun `a steady speed settles on exactly that speed`() {
        val readings = feed(SpeedFilter(), List(15) { 27.78 })
        assertEquals(27.78, readings.last().mps, 0.01)
        assertTrue(readings.last().trusted)
    }

    @Test
    fun `the very first reading is taken at face value`() {
        // There is nothing to smooth against, and starting from zero would show
        // the driver accelerating from a standstill they are not at.
        val reading = SpeedFilter().update(SpeedSample(25.0, 0.5, 1_000))
        assertEquals(25.0, reading.mps, 0.001)
    }

    @Test
    fun `jitter is smoothed away`() {
        // A real 100 km/h with the few tenths of noise a phone actually
        // produces. The displayed number must not flicker.
        val random = Random(7)
        val filter = SpeedFilter()
        val readings = feed(filter, List(40) { 27.78 + random.nextDouble(-0.35, 0.35) })

        val settled = readings.drop(10)
        val spread = settled.maxOf { it.mps } - settled.minOf { it.mps }
        assertTrue(spread < 0.35, "output still wobbling by $spread m/s")

        val displays = settled.map { it.displayKmh }.toSet()
        assertTrue(displays.size <= 2, "the dial showed ${displays.size} different numbers")
    }

    @Test
    fun `hard braking is followed almost immediately`() {
        // The constraint that matters more than smoothness: a readout that
        // takes three seconds to notice you braked is worse than one that
        // wobbles.
        val filter = SpeedFilter()
        feed(filter, List(10) { 27.78 })

        val braking = feed(
            filter,
            listOf(22.0, 16.0, 10.0, 5.0),
            startMs = 11_000,
        )
        // Within two fixes of a 6 m/s change it must be tracking closely.
        assertTrue(
            abs(braking[1].mps - 16.0) < 2.0,
            "lagging badly during braking: ${braking[1].mps} against 16.0",
        )
        assertTrue(abs(braking.last().mps - 5.0) < 1.5)
    }

    @Test
    fun `a single wild outlier is largely absorbed`() {
        val filter = SpeedFilter()
        feed(filter, List(10) { 27.78 })

        // One fix claiming a 40 m/s jump, then back to normal.
        val spike = filter.update(SpeedSample(68.0, 0.5, 11_000))
        assertTrue(spike.mps < 45.0, "the spike went almost straight through: ${spike.mps}")

        val recovered = feed(filter, List(4) { 27.78 }, startMs = 12_000)
        assertTrue(abs(recovered.last().mps - 27.78) < 1.5, "did not recover from the spike")
    }

    @Test
    fun `a parked car reads exactly zero`() {
        // GNSS noise keeps a stationary phone reporting one or two km/h, and a
        // dial that will not sit still at a red light looks broken.
        val readings = feed(SpeedFilter(), List(10) { 0.45 })
        assertEquals(0.0, readings.last().mps, 0.001)
        assertEquals(0, readings.last().displayKmh)
    }

    @Test
    fun `a poor fix holds the last good value, then admits it does not know`() {
        val filter = SpeedFilter()
        feed(filter, List(10) { 27.78 })

        val held = filter.update(SpeedSample(5.0, accuracyMps = 9.0, timestampMs = 11_000))
        assertEquals(27.78, held.mps, 0.1)
        assertFalse(held.trusted, "a rejected fix was reported as trusted")

        // Still bad four seconds later: stop pretending.
        val given = filter.update(SpeedSample(5.0, accuracyMps = 9.0, timestampMs = 15_500))
        assertEquals(0.0, given.mps, 0.001)
        assertFalse(given.trusted)
    }

    @Test
    fun `a platform flagging its reading as invalid is not believed`() {
        // iOS reports -1 for both speed and accuracy when it has no fix.
        val filter = SpeedFilter()
        val reading = filter.update(SpeedSample(-1.0, accuracyMps = -1.0, timestampMs = 1_000))
        assertEquals(0.0, reading.mps, 0.001)
        assertFalse(reading.trusted)
    }

    @Test
    fun `coming out of a tunnel snaps rather than crawling up`() {
        val filter = SpeedFilter()
        feed(filter, List(5) { 27.78 })

        // Ninety seconds of nothing, then a fix at a different speed.
        val after = filter.update(SpeedSample(15.0, 0.5, 95_000))
        assertEquals(15.0, after.mps, 0.001)
    }

    @Test
    fun `a missing accuracy figure is still usable`() {
        // Some Android devices never populate speed accuracy. Refusing to show
        // a speed on those would be worse than showing a slightly noisier one.
        val readings = feed(SpeedFilter(), List(12) { 20.0 }, accuracy = null)
        assertEquals(20.0, readings.last().mps, 0.05)
        assertTrue(readings.last().trusted)
    }

    @Test
    fun `the output is never negative`() {
        val random = Random(11)
        val filter = SpeedFilter()
        repeat(500) { i ->
            val reading = filter.update(
                SpeedSample(random.nextDouble(0.0, 3.0), 0.5, 1_000L + i * 1_000L),
            )
            assertTrue(reading.mps >= 0.0, "negative speed at step $i")
        }
    }

    @Test
    fun `the displayed number rounds the way a speedometer does`() {
        assertEquals(100, SpeedReading(27.78, true).displayKmh)
        assertEquals(60, SpeedReading(16.67, true).displayKmh)
        assertEquals(0, SpeedReading(0.0, true).displayKmh)
    }
}

class CongestionSpanTest {

    private fun line(n: Int) = List(n) { RoutePoint(-33.87 + it * 0.001, 151.21) }

    @Test
    fun `one level across the whole route is one span`() {
        val spans = RouteTracker.congestionSpans(line(5), List(4) { "low" })
        assertEquals(1, spans.size)
        assertEquals("low", spans[0].level)
        assertEquals(5, spans[0].points.size)
    }

    @Test
    fun `changes split into runs that share their boundary point`() {
        val spans = RouteTracker.congestionSpans(
            line(6),
            listOf("low", "low", "heavy", "heavy", "low"),
        )
        assertEquals(listOf("low", "heavy", "low"), spans.map { it.level })
        assertEquals(3, spans[0].points.size)
        assertEquals(3, spans[1].points.size)
        assertEquals(2, spans[2].points.size)

        // Sharing the boundary is what stops a visible gap in the drawn line.
        assertEquals(spans[0].points.last(), spans[1].points.first())
        assertEquals(spans[1].points.last(), spans[2].points.first())
    }

    @Test
    fun `every segment is covered exactly once`() {
        val congestion = listOf("low", "heavy", "heavy", "severe", "low", "low", "moderate")
        val spans = RouteTracker.congestionSpans(line(8), congestion)
        val segments = spans.sumOf { it.points.size - 1 }
        assertEquals(congestion.size, segments, "spans do not tile the route")
    }

    @Test
    fun `a provider with no traffic data yields one unknown span`() {
        val spans = RouteTracker.congestionSpans(line(5), emptyList())
        assertEquals(1, spans.size)
        assertEquals("unknown", spans[0].level)
        assertEquals(5, spans[0].points.size)
    }

    @Test
    fun `a mismatched array falls back rather than guessing`() {
        val spans = RouteTracker.congestionSpans(line(5), listOf("low", "heavy"))
        assertEquals(1, spans.size)
        assertEquals("unknown", spans[0].level)
    }

    @Test
    fun `a degenerate route yields nothing`() {
        assertTrue(RouteTracker.congestionSpans(emptyList(), emptyList()).isEmpty())
        assertTrue(RouteTracker.congestionSpans(line(1), emptyList()).isEmpty())
    }
}

class TrafficDelayTest {

    private fun option(durationS: Double, freeFlowS: Double) =
        RouteOption(
            distanceM = 18_400.0,
            durationS = durationS,
            geometry = "",
            durationFreeFlowS = freeFlowS,
        )

    @Test
    fun `the delay is the gap against a clear run`() {
        assertEquals(360.0, RouteTracker.trafficDelayS(option(1_800.0, 1_440.0)), 0.001)
    }

    @Test
    fun `a route running faster than typical is not a negative delay`() {
        assertEquals(0.0, RouteTracker.trafficDelayS(option(1_400.0, 1_440.0)), 0.001)
    }

    @Test
    fun `a delay worth mentioning is described, a trivial one is not`() {
        assertEquals("6 min of traffic", RouteTracker.describeTraffic(option(1_800.0, 1_440.0)))
        assertEquals(null, RouteTracker.describeTraffic(option(1_470.0, 1_440.0)))
        assertEquals(null, RouteTracker.describeTraffic(option(1_440.0, 1_440.0)))
    }
}
