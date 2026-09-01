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
    val settings: FixtureSettings,
    val kindOverrides: Map<String, KindSettings> = emptyMap(),
    val route: List<RoutePoint>? = null,
    val threats: List<Threat>,
    val expect: FixtureExpect,
)

@Serializable
private data class FixtureState(
    val lastAnnouncedAt: Map<String, Long> = emptyMap(),
    val lastAnyAnnounceAt: Long? = null,
    val retired: List<String> = emptyList(),
)

/** The tunable half of [AlertSettings]; the kind table arrives separately. */
@Serializable
private data class FixtureSettings(
    val muted: Boolean = false,
    val flashEnabled: Boolean = true,
    val minSpeedKmh: Double = 0.0,
    val sameRoadLeadMultiplier: Double = 1.7,
    val corridorHalfWidthM: Double = 40.0,
    val corridorWidenPerM: Double = 0.02,
    val corridorMaxHalfWidthM: Double = 90.0,
)

@Serializable
private data class FixtureExpect(
    val threatId: String? = null,
    val level: String? = null,
    val spokenText: String? = null,
    val flash: Boolean = false,
    val relation: String? = null,
)

/**
 * The contract between the two apps.
 *
 * The expectations in the fixture file are produced by a third implementation
 * of the rules, written in Python from docs/alert-engine.md, so a bug shared by
 * the Kotlin and Swift engines still fails here. The Swift suite reads the same
 * file.
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
            val settings = AlertSettings(
                muted = case.settings.muted,
                flashEnabled = case.settings.flashEnabled,
                minSpeedKmh = case.settings.minSpeedKmh,
                sameRoadLeadMultiplier = case.settings.sameRoadLeadMultiplier,
                corridorHalfWidthM = case.settings.corridorHalfWidthM,
                corridorWidenPerM = case.settings.corridorWidenPerM,
                corridorMaxHalfWidthM = case.settings.corridorMaxHalfWidthM,
                kinds = AlertSettings.defaultKinds + case.kindOverrides,
            )

            // Trimming the route is the caller's job, so the fixture exercises
            // aheadSlice on the way in exactly as the apps do.
            val route = case.route?.let { geometry ->
                val slice = RouteTracker.aheadSlice(geometry, case.car.lat, case.car.lon)
                if (slice.size >= 2) RouteContext(slice) else null
            }

            val result = AlertEngine.evaluate(
                now = fixtures.now,
                car = case.car,
                threats = case.threats,
                state = EngineState(
                    lastAnnouncedAt = case.state.lastAnnouncedAt,
                    lastAnyAnnounceAt = case.state.lastAnyAnnounceAt,
                    retired = case.state.retired.toSet(),
                ),
                settings = settings,
                route = route,
            )

            val expected = case.expect
            if (expected.threatId == null) {
                if (result != null) {
                    failures += "${case.name}: expected silence, got \"${result.spokenText}\""
                }
                continue
            }

            if (result == null) {
                failures += "${case.name}: expected \"${expected.spokenText}\", got silence"
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
            if (result.flash != expected.flash) {
                failures += "${case.name}: expected flash ${expected.flash}, got ${result.flash}"
            }
            if (result.relation.name != expected.relation) {
                failures += "${case.name}: expected relation ${expected.relation}, got ${result.relation}"
            }
        }

        assertTrue(failures.isEmpty(), "\n" + failures.joinToString("\n"))
    }

    @Test
    fun `the fixture file covers both outcomes and every relation`() {
        val silent = fixtures.cases.count { it.expect.threatId == null }
        assertTrue(silent >= 8, "too few silence cases: $silent")
        assertTrue(fixtures.cases.size - silent >= 8, "too few announcement cases")

        val relations = fixtures.cases.mapNotNull { it.expect.relation }.toSet()
        for (relation in Relation.entries) {
            assertTrue(relation.name in relations, "no fixture exercises ${relation.name}")
        }
    }

    @Test
    fun `every fixture carries the reasoning behind it`() {
        // A case whose expectation nobody can check by hand is a case that
        // silently encodes whatever the code happened to do.
        for (case in fixtures.cases) {
            assertTrue(case.why.length > 30, "${case.name}: no usable explanation")
        }
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
    fun `lead range respects its floor and ceiling`() {
        // Crawling: the flat range, not a metre of lead time.
        assertEquals(AlertEngine.CRAWL_RANGE_M, AlertEngine.leadRange(5.0, 25.0), 0.001)
        // Slow but moving: time-based range would be tiny, so the floor applies.
        assertEquals(AlertEngine.MIN_TRIGGER_M, AlertEngine.leadRange(20.0, 25.0), 0.001)
        // Absurdly fast: capped.
        assertEquals(AlertEngine.MAX_TRIGGER_M, AlertEngine.leadRange(400.0, 25.0), 0.001)
    }

    @Test
    fun `a closure gets more warning than a crash at the same speed`() {
        val settings = AlertSettings()
        val closure = settings.forKind("closure").leadSeconds
        val crash = settings.forKind("crash").leadSeconds
        assertTrue(
            AlertEngine.leadRange(100.0, closure) > AlertEngine.leadRange(100.0, crash),
        )
    }

    @Test
    fun `the corridor widens with distance and then stops`() {
        val settings = AlertSettings()
        assertEquals(40.0, AlertEngine.corridorHalfWidth(0.0, settings), 0.001)
        assertEquals(50.0, AlertEngine.corridorHalfWidth(500.0, settings), 0.001)
        assertEquals(80.0, AlertEngine.corridorHalfWidth(2000.0, settings), 0.001)
        // Capped before it can reach the next street over.
        assertEquals(90.0, AlertEngine.corridorHalfWidth(9000.0, settings), 0.001)
    }

    @Test
    fun `being on my road always warns at least as early as being merely ahead`() {
        // The property the whole same-road distinction rests on. If this ever
        // inverts, the app warns later about the thing it is more sure of.
        val settings = AlertSettings()
        for (kindName in AlertSettings.defaultKinds.keys) {
            val kind = settings.forKind(kindName)
            for (speed in listOf(20.0, 50.0, 80.0, 100.0, 130.0)) {
                val ahead = maxOf(kind.radiusM, AlertEngine.leadRange(speed, kind.leadSeconds))
                val sameRoad = maxOf(
                    kind.radiusM,
                    AlertEngine.leadRange(speed, kind.leadSeconds * settings.sameRoadLeadMultiplier),
                )
                assertTrue(
                    sameRoad >= ahead,
                    "$kindName at $speed km/h: same-road $sameRoad < ahead $ahead",
                )
            }
        }
    }

    @Test
    fun `a sideways threat is never reached by a long lead time`() {
        // A police car 700m to the side must stay silent at any speed, because
        // only the radius applies sideways.
        val settings = AlertSettings()
        val beside = Threat("p", "police", -33.8625, 151.2093)
        for (speed in listOf(40.0, 80.0, 110.0, 140.0)) {
            val car = CarState(-33.8688, 151.2093, speedMps = speed / 3.6, headingDeg = 90.0)
            val result = AlertEngine.evaluate(
                1L, car, listOf(beside), EngineState(), settings,
            )
            assertNull(result, "warned about a sideways threat at $speed km/h")
        }
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
