package au.radar.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class FixtureDoc(val note: String, val now: Long, val cases: List<FixtureCase>)

@Serializable
private data class FixtureCase(
    val name: String,
    val why: String,
    val car: CarState,
    val state: FixtureState,
    val threats: List<Threat>,
    val expect: FixtureExpect,
)

@Serializable
private data class FixtureState(
    val lastAnnouncedAt: Map<String, Long> = emptyMap(),
    val lastAnyAnnounceAt: Long? = null,
    val retired: List<String> = emptyList(),
)

@Serializable
private data class FixtureExpect(
    val threatId: String? = null,
    val level: String? = null,
    val spokenText: String? = null,
)

/**
 * The contract between the two apps. These cases are written by hand from
 * docs/alert-engine.md; the Swift implementation runs the identical file. If
 * one platform starts behaving differently from the other, it fails here first.
 */
class AlertEngineFixtureTest {

    private val fixtures: FixtureDoc by lazy {
        val file = File("../../shared/alert-engine-fixtures.json")
        assertTrue(file.exists(), "missing shared fixtures at ${file.absolutePath}")
        Json { ignoreUnknownKeys = true }.decodeFromString<FixtureDoc>(file.readText())
    }

    @Test
    fun `every shared fixture behaves as specified`() {
        val failures = mutableListOf<String>()

        for (case in fixtures.cases) {
            val result = AlertEngine.evaluate(
                now = fixtures.now,
                car = case.car,
                threats = case.threats,
                state = EngineState(
                    lastAnnouncedAt = case.state.lastAnnouncedAt,
                    lastAnyAnnounceAt = case.state.lastAnyAnnounceAt,
                    retired = case.state.retired.toSet(),
                ),
            )

            val expected = case.expect
            if (expected.threatId == null) {
                if (result != null) {
                    failures += "${case.name}: expected silence, got ${result.spokenText}"
                }
                continue
            }

            if (result == null) {
                failures += "${case.name}: expected ${expected.spokenText}, got silence"
                continue
            }
            if (result.threatId != expected.threatId) {
                failures += "${case.name}: expected threat ${expected.threatId}, got ${result.threatId}"
            }
            if (result.level.name != expected.level?.uppercase()) {
                failures += "${case.name}: expected level ${expected.level}, got ${result.level}"
            }
            if (result.spokenText != expected.spokenText) {
                failures += "${case.name}: expected \"${expected.spokenText}\", got \"${result.spokenText}\""
            }
        }

        assertTrue(failures.isEmpty(), "\n" + failures.joinToString("\n"))
    }

    @Test
    fun `the fixture file covers both outcomes`() {
        val silent = fixtures.cases.count { it.expect.threatId == null }
        val announced = fixtures.cases.size - silent
        assertTrue(silent >= 5, "too few silence cases: $silent")
        assertTrue(announced >= 5, "too few announcement cases: $announced")
    }
}

class AlertEngineUnitTest {

    private fun camera(id: String = "c", lat: Double = -33.86, lon: Double = 151.2093) =
        Threat(id = id, kind = "fixed_speed", lat = lat, lon = lon, severity = 2, isCamera = true)

    @Test
    fun `the cone narrows with speed and never leaves its bounds`() {
        assertEquals(70.0, AlertEngine.coneHalfAngle(0.0), 0.001)
        assertEquals(47.5, AlertEngine.coneHalfAngle(50.0), 0.001)
        assertEquals(25.0, AlertEngine.coneHalfAngle(100.0), 0.001)
        // Well past any legal speed, the cone bottoms out rather than inverting.
        assertEquals(22.0, AlertEngine.coneHalfAngle(300.0), 0.001)
        assertEquals(70.0, AlertEngine.coneHalfAngle(-10.0), 0.001)
    }

    @Test
    fun `trigger range respects its floor and ceiling`() {
        val cam = camera()
        // Crawling: the flat range, not a metre of lead time.
        assertEquals(AlertEngine.CRAWL_RANGE_M, AlertEngine.triggerRange(5.0, cam), 0.001)
        // Slow but moving: time-based range would be tiny, so the floor applies.
        assertEquals(AlertEngine.MIN_TRIGGER_M, AlertEngine.triggerRange(20.0, cam), 0.001)
        // Absurdly fast: capped.
        assertEquals(AlertEngine.MAX_TRIGGER_M, AlertEngine.triggerRange(400.0, cam), 0.001)
    }

    @Test
    fun `critical hazards get more warning than major ones at the same speed`() {
        val critical = Threat("a", "closure", -33.86, 151.2, severity = 3)
        val major = Threat("b", "crash", -33.86, 151.2, severity = 2)
        assertTrue(AlertEngine.triggerRange(100.0, critical) > AlertEngine.triggerRange(100.0, major))
    }

    @Test
    fun `severity is capped for reports we only half believe`() {
        val shaky = Threat("a", "police", -33.86, 151.2, severity = 3, confidence = 0.4)
        assertEquals(1, AlertEngine.effectiveSeverity(shaky))

        val solid = shaky.copy(confidence = 0.9)
        assertEquals(3, AlertEngine.effectiveSeverity(solid))
    }

    @Test
    fun `cameras always reach speaking severity`() {
        val lowRankCamera = Threat("a", "mobile_zone", -33.86, 151.2, severity = 0, isCamera = true)
        assertEquals(2, AlertEngine.effectiveSeverity(lowRankCamera))
    }

    @Test
    fun `distances are spoken the way a person would say them`() {
        assertEquals("300 metres", AlertEngine.spokenDistance(280.0))
        assertEquals("400 metres", AlertEngine.spokenDistance(420.0))
        assertEquals("900 metres", AlertEngine.spokenDistance(949.0))
        assertEquals("1 kilometre", AlertEngine.spokenDistance(1_000.0))
        assertEquals("1.1 kilometres", AlertEngine.spokenDistance(1_100.0))
        assertEquals("1.5 kilometres", AlertEngine.spokenDistance(1_480.0))
    }

    @Test
    fun `recording an announcement blocks an immediate repeat`() {
        val now = 1_760_000_000_000L
        val car = CarState(-33.8688, 151.2093, speedMps = 27.78, headingDeg = 0.0)
        val threats = listOf(camera("cam1", lat = -33.8650229))

        val first = AlertEngine.evaluate(now, car, threats, EngineState())
        assertNotNull(first)

        val after = AlertEngine.record(EngineState(), first, now)
        // One second later the global gap alone is enough to keep it quiet.
        assertNull(AlertEngine.evaluate(now + 1_000, car, threats, after))
        // Ten minutes later the per-threat cooldown is still holding.
        assertNull(AlertEngine.evaluate(now + 9 * 60_000, car, threats, after))
        // Past the cooldown it may speak again.
        assertNotNull(AlertEngine.evaluate(now + 11 * 60_000, car, threats, after))
    }

    @Test
    fun `passing a threat retires it permanently`() {
        val car = CarState(-33.8688, 151.2093, speedMps = 27.78, headingDeg = 0.0)
        // Placed due south of the car, which is heading north: already passed.
        val behind = Threat("gone", "fixed_speed", -33.8788, 151.2093, isCamera = true)
        val state = AlertEngine.retirePassed(EngineState(), car, listOf(behind))
        assertTrue("gone" in state.retired)
    }

    @Test
    fun `a threat still ahead is not retired`() {
        val car = CarState(-33.8688, 151.2093, speedMps = 27.78, headingDeg = 0.0)
        val ahead = Threat("here", "fixed_speed", -33.8650229, 151.2093, isCamera = true)
        val state = AlertEngine.retirePassed(EngineState(), car, listOf(ahead))
        assertTrue(state.retired.isEmpty())
    }

    @Test
    fun `nothing is announced with no threats at all`() {
        val car = CarState(-33.8688, 151.2093, speedMps = 27.78, headingDeg = 0.0)
        assertNull(AlertEngine.evaluate(1L, car, emptyList(), EngineState()))
    }
}
