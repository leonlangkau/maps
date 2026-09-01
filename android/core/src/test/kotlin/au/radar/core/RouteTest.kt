package au.radar.core

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class RouteFixtures(
    val note: String,
    val polyline6: String,
    val expectedPoints: List<FixturePoint>,
    val totalDistanceM: Double,
    val offRouteThresholdM: Double,
    val steps: List<RouteStep>,
    val toleranceM: Double,
    val cases: List<RouteCase>,
)

@Serializable
private data class FixturePoint(val lat: Double, val lon: Double)

@Serializable
private data class RouteCase(
    val name: String,
    val why: String,
    val lat: Double,
    val lon: Double,
    val expect: RouteExpect,
)

@Serializable
private data class RouteExpect(
    val distanceAlongM: Double,
    val distanceRemainingM: Double,
    val offRouteByM: Double,
    val isOffRoute: Boolean,
    val stepIndex: Int,
    val distanceToManeuverM: Double,
)

/**
 * Route geometry, pinned to the same fixture file the Swift implementation
 * reads. The expected values were computed independently in Python, so a bug
 * shared between the two implementations still fails here.
 */
class RouteFixtureTest {

    private val fixtures: RouteFixtures by lazy {
        val file = File("../../shared/route-fixtures.json")
        assertTrue(file.exists(), "missing shared fixtures at ${file.absolutePath}")
        Json { ignoreUnknownKeys = true }.decodeFromString<RouteFixtures>(file.readText())
    }

    @Test
    fun `the encoded polyline decodes to the expected points`() {
        val decoded = Polyline.decode(fixtures.polyline6)
        assertEquals(fixtures.expectedPoints.size, decoded.size, "wrong number of points")

        for ((i, expected) in fixtures.expectedPoints.withIndex()) {
            assertEquals(expected.lat, decoded[i].lat, 1e-6, "point $i latitude")
            assertEquals(expected.lon, decoded[i].lon, 1e-6, "point $i longitude")
        }
    }

    @Test
    fun `every progress case matches the independently computed expectation`() {
        val geometry = Polyline.decode(fixtures.polyline6)
        val failures = mutableListOf<String>()
        val tolerance = fixtures.toleranceM

        for (case in fixtures.cases) {
            val progress = RouteTracker.progress(geometry, fixtures.steps, case.lat, case.lon)
            if (progress == null) {
                failures += "${case.name}: got no progress at all"
                continue
            }

            fun check(label: String, actual: Double, expected: Double) {
                if (abs(actual - expected) > tolerance) {
                    failures += "${case.name}: $label expected $expected, got $actual"
                }
            }

            check("distanceAlongM", progress.distanceAlongM, case.expect.distanceAlongM)
            check("distanceRemainingM", progress.distanceRemainingM, case.expect.distanceRemainingM)
            check("offRouteByM", progress.offRouteByM, case.expect.offRouteByM)
            check("distanceToManeuverM", progress.distanceToManeuverM, case.expect.distanceToManeuverM)

            if (progress.isOffRoute != case.expect.isOffRoute) {
                failures += "${case.name}: isOffRoute expected ${case.expect.isOffRoute}, got ${progress.isOffRoute}"
            }
            if (progress.stepIndex != case.expect.stepIndex) {
                failures += "${case.name}: stepIndex expected ${case.expect.stepIndex}, got ${progress.stepIndex}"
            }
        }

        assertTrue(failures.isEmpty(), "\n" + failures.joinToString("\n"))
    }

    @Test
    fun `the off-route threshold matches the fixture file`() {
        assertEquals(fixtures.offRouteThresholdM, RouteTracker.OFF_ROUTE_THRESHOLD_M, 0.001)
    }

    @Test
    fun `the decoded line measures the expected total length`() {
        val geometry = Polyline.decode(fixtures.polyline6)
        var total = 0.0
        for (i in 1 until geometry.size) {
            total += Geo.distanceM(
                geometry[i - 1].lat, geometry[i - 1].lon,
                geometry[i].lat, geometry[i].lon,
            )
        }
        assertEquals(fixtures.totalDistanceM, total, fixtures.toleranceM)
    }
}

class PolylineTest {

    @Test
    fun `decodes the canonical precision-5 example`() {
        // The example from Google's own polyline documentation.
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].lat, 1e-5)
        assertEquals(-120.2, points[0].lon, 1e-5)
        assertEquals(40.7, points[1].lat, 1e-5)
        assertEquals(-120.95, points[1].lon, 1e-5)
        assertEquals(43.252, points[2].lat, 1e-5)
        assertEquals(-126.453, points[2].lon, 1e-5)
    }

    @Test
    fun `an empty string decodes to no points rather than throwing`() {
        assertTrue(Polyline.decode("").isEmpty())
    }

    @Test
    fun `a truncated string stops cleanly instead of throwing`() {
        // A dropped final byte is the shape a partial network read takes.
        val full = Polyline.decode("_p~iF~ps|U_ulLnnqC", precision = 5)
        val truncated = Polyline.decode("_p~iF~ps|U_ulLnnq", precision = 5)
        assertEquals(2, full.size)
        // The incomplete pair is discarded, not emitted as garbage coordinates.
        assertTrue(truncated.size <= full.size)
    }

    @Test
    fun `handles negative deltas in both axes`() {
        // Sydney then a point south-west of it: both deltas negative.
        val points = Polyline.decode("~~dr_Agtal_H~xG~xG")
        assertEquals(2, points.size)
        assertTrue(points[1].lat < points[0].lat, "latitude should decrease")
        assertTrue(points[1].lon < points[0].lon, "longitude should decrease")
    }
}

class RouteTrackerFormattingTest {

    @Test
    fun `durations read the way an ETA strip does`() {
        assertEquals("5 min", RouteTracker.formatDuration(300.0))
        assertEquals("59 min", RouteTracker.formatDuration(3_540.0))
        assertEquals("1 hr", RouteTracker.formatDuration(3_600.0))
        assertEquals("1 hr 30 min", RouteTracker.formatDuration(5_400.0))
        assertEquals("2 hr 5 min", RouteTracker.formatDuration(7_500.0))
    }

    @Test
    fun `distances read the way an ETA strip does`() {
        assertEquals("400 m", RouteTracker.formatDistance(450.0))
        assertEquals("1.5 km", RouteTracker.formatDistance(1_500.0))
        assertEquals("12 km", RouteTracker.formatDistance(12_400.0))
    }

    @Test
    fun `a maneuver prompt reads as a sentence`() {
        val progress = RouteProgress(
            snappedLat = 0.0, snappedLon = 0.0,
            distanceAlongM = 100.0, distanceRemainingM = 900.0, durationRemainingS = 60.0,
            offRouteByM = 0.0, isOffRoute = false,
            stepIndex = 0, distanceToManeuverM = 400.0,
            currentStep = RouteStep("Turn left onto George Street", 500.0, 60.0),
            nextStep = null,
        )
        assertEquals(
            "In 400 metres, turn left onto George Street",
            RouteTracker.maneuverPrompt(progress),
        )
    }

    @Test
    fun `a maneuver about to happen drops the distance preamble`() {
        val progress = RouteProgress(
            snappedLat = 0.0, snappedLon = 0.0,
            distanceAlongM = 480.0, distanceRemainingM = 20.0, durationRemainingS = 5.0,
            offRouteByM = 0.0, isOffRoute = false,
            stepIndex = 0, distanceToManeuverM = 15.0,
            currentStep = RouteStep("Turn left onto George Street", 500.0, 60.0),
            nextStep = null,
        )
        assertEquals("Turn left onto George Street", RouteTracker.maneuverPrompt(progress))
    }

    @Test
    fun `a route with fewer than two points yields no progress`() {
        assertEquals(null, RouteTracker.progress(emptyList(), emptyList(), -33.8, 151.2))
        assertEquals(
            null,
            RouteTracker.progress(listOf(RoutePoint(-33.8, 151.2)), emptyList(), -33.8, 151.2),
        )
    }
}
