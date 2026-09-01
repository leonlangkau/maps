package au.radar.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Properties that must hold for every input, not just the ones somebody thought
 * to write down.
 *
 * The fixtures pin specific behaviour; these pin the shape of the whole
 * function. A rule added later that quietly breaks one of these — a gate moved
 * above another, a range that stops being a ceiling — fails here even though no
 * fixture covers that exact geometry.
 *
 * The generator is seeded, so a failure is reproducible rather than a flake.
 */
class AlertEnginePropertyTest {

    private val random = Random(20260901)
    private val iterations = 4_000

    private val kinds = AlertSettings.defaultKinds.keys.toList()

    private companion object {
        const val LAT = -33.8688
        const val LON = 151.2093
        const val EARTH_RADIUS_M = 6_371_008.8
    }

    /** A point at a bearing and distance from the car, as the app's own maths sees it. */
    private fun place(bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val angular = distanceM / EARTH_RADIUS_M
        val theta = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(LAT)
        val lon1 = Math.toRadians(LON)
        val lat2 = Math.asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(theta))
        val lon2 = lon1 + Math.atan2(
            sin(theta) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2),
        )
        return Math.toDegrees(lat2) to Math.toDegrees(lon2)
    }

    private fun randomThreat(id: String, bearingDeg: Double, distanceM: Double): Threat {
        val (lat, lon) = place(bearingDeg, distanceM)
        val kind = kinds[random.nextInt(kinds.size)]
        return Threat(
            id = id,
            kind = kind,
            lat = lat,
            lon = lon,
            bearingDeg = null,
            severity = random.nextInt(0, 4),
            confidence = random.nextDouble(0.3, 1.0),
            isCamera = random.nextBoolean(),
        )
    }

    private fun movingCar(speedKmh: Double, heading: Double = 0.0) =
        CarState(LAT, LON, speedMps = speedKmh / 3.6, headingDeg = heading)

    @Test
    fun `nothing behind the car is ever announced`() {
        val settings = AlertSettings()
        repeat(iterations) { i ->
            // Strictly behind: past the 120 degree cut-off in both directions.
            val bearing = if (random.nextBoolean()) {
                random.nextDouble(121.0, 180.0)
            } else {
                random.nextDouble(180.0, 239.0)
            }
            val threat = randomThreat("t$i", bearing, random.nextDouble(20.0, 2_900.0))
            val car = movingCar(random.nextDouble(0.0, 140.0))

            val result = AlertEngine.evaluate(1L, car, listOf(threat), EngineState(), settings)
            assertNull(result, "announced a threat $bearing degrees off the nose")
        }
    }

    @Test
    fun `an announced threat is always inside a range the settings allow`() {
        val settings = AlertSettings()
        var announced = 0
        repeat(iterations) { i ->
            val threat = randomThreat(
                "t$i", random.nextDouble(0.0, 360.0), random.nextDouble(10.0, 3_500.0),
            )
            val car = movingCar(random.nextDouble(0.0, 160.0))

            val result = AlertEngine.evaluate(1L, car, listOf(threat), EngineState(), settings)
                ?: return@repeat
            announced++

            val kind = settings.forKind(threat.kind)
            val ceiling = maxOf(kind.radiusM, AlertEngine.MAX_TRIGGER_M)
            assertTrue(
                result.distanceM <= ceiling,
                "announced at ${result.distanceM} m, past the ${ceiling} m ceiling",
            )
            assertTrue(
                result.distanceM <= AlertEngine.MAX_CONSIDER_M,
                "announced something beyond the consideration limit",
            )
        }
        // Without this the test would pass just as happily on an engine that
        // never announced anything at all.
        assertTrue(announced > iterations / 20, "only $announced of $iterations announced")
    }

    @Test
    fun `getting closer never turns a warning off`() {
        // The property a driver would notice being broken: if it warned at 800m
        // it must still warn at 400m. A gate that reads the wrong way round —
        // a corridor that narrows faster than the cross-track shrinks, say —
        // shows up here and nowhere else.
        val settings = AlertSettings()
        var exercised = 0
        repeat(iterations) { i ->
            val bearing = random.nextDouble(0.0, 119.0)
            val far = random.nextDouble(200.0, 2_500.0)
            val speed = random.nextDouble(10.0, 140.0)
            val car = movingCar(speed)

            val farThreat = randomThreat("t$i", bearing, far)
            val warnedFar = AlertEngine.evaluate(
                1L, car, listOf(farThreat), EngineState(), settings,
            ) != null
            if (!warnedFar) return@repeat
            exercised++

            for (fraction in listOf(0.75, 0.5, 0.25)) {
                val near = far * fraction
                val (lat, lon) = place(bearing, near)
                val nearThreat = farThreat.copy(lat = lat, lon = lon)
                val warnedNear = AlertEngine.evaluate(
                    1L, car, listOf(nearThreat), EngineState(), settings,
                ) != null
                assertTrue(
                    warnedNear,
                    "warned at ${far.toInt()} m but not at ${near.toInt()} m, " +
                        "bearing $bearing, ${speed.toInt()} km/h, ${farThreat.kind}",
                )
            }
        }
        // The property is only meaningful for cases that warned at the far
        // distance; assert that a real share of them did.
        assertTrue(exercised > iterations / 20, "only $exercised of $iterations reached the check")
    }

    @Test
    fun `being on my road never warns later than being merely in front`() {
        // Same distance, same kind, same speed: one dead ahead, one off to the
        // side of the corridor but still inside the cone. The first must never
        // be the one that stays silent.
        val settings = AlertSettings()
        var exercised = 0
        repeat(iterations) { i ->
            val distance = random.nextDouble(300.0, 2_500.0)
            val speed = random.nextDouble(30.0, 140.0)
            val car = movingCar(speed)

            val onRoad = randomThreat("on$i", 0.0, distance)
            // Far enough off-axis to leave the corridor, close enough to stay in
            // the cone at this speed.
            val coneEdge = AlertEngine.coneHalfAngle(speed) * 0.8
            val (lat, lon) = place(coneEdge, distance)
            val inFront = onRoad.copy(id = "front$i", lat = lat, lon = lon)

            val warnedInFront = AlertEngine.evaluate(
                1L, car, listOf(inFront), EngineState(), settings,
            ) != null
            if (!warnedInFront) return@repeat
            exercised++

            assertTrue(
                AlertEngine.evaluate(1L, car, listOf(onRoad), EngineState(), settings) != null,
                "warned about one off to the side at ${distance.toInt()} m but not the " +
                    "one dead ahead (${onRoad.kind}, ${speed.toInt()} km/h)",
            )
        }
        assertTrue(exercised > iterations / 20, "only $exercised of $iterations reached the check")
    }

    @Test
    fun `a switched-off kind is never announced`() {
        repeat(iterations) { i ->
            val threat = randomThreat(
                "t$i", random.nextDouble(0.0, 90.0), random.nextDouble(20.0, 600.0),
            )
            val settings = AlertSettings(
                kinds = AlertSettings.defaultKinds +
                    (threat.kind to AlertSettings().forKind(threat.kind).copy(enabled = false)),
            )
            val car = movingCar(random.nextDouble(0.0, 140.0))

            assertNull(
                AlertEngine.evaluate(1L, car, listOf(threat), EngineState(), settings),
                "announced ${threat.kind} after it was switched off",
            )
        }
    }

    @Test
    fun `the flash switch changes the flag and nothing else`() {
        // Turning the screen pulse off must not quietly change which warnings
        // are raised, or how far away they are raised.
        repeat(iterations) { i ->
            val threat = randomThreat(
                "t$i", random.nextDouble(0.0, 90.0), random.nextDouble(20.0, 2_000.0),
            )
            val car = movingCar(random.nextDouble(0.0, 140.0))

            val withFlash = AlertEngine.evaluate(
                1L, car, listOf(threat), EngineState(), AlertSettings(flashEnabled = true),
            )
            val without = AlertEngine.evaluate(
                1L, car, listOf(threat), EngineState(), AlertSettings(flashEnabled = false),
            )

            assertEquals(withFlash?.threatId, without?.threatId)
            assertEquals(withFlash?.level, without?.level)
            assertEquals(withFlash?.distanceM, without?.distanceM)
            assertEquals(withFlash?.relation, without?.relation)
            assertTrue(without?.flash != true, "flashed with the master switch off")
        }
    }

    @Test
    fun `a cooled-down or retired threat is never the one chosen`() {
        val settings = AlertSettings()
        val now = 1_760_000_000_000L
        repeat(iterations) { i ->
            val threat = randomThreat(
                "t$i", random.nextDouble(0.0, 60.0), random.nextDouble(20.0, 800.0),
            )
            val car = movingCar(random.nextDouble(20.0, 120.0))

            val cooled = EngineState(lastAnnouncedAt = mapOf(threat.id to now - 60_000))
            assertNull(
                AlertEngine.evaluate(now, car, listOf(threat), cooled, settings),
                "announced a threat inside its cooldown",
            )

            val retired = EngineState(retired = setOf(threat.id))
            assertNull(
                AlertEngine.evaluate(now, car, listOf(threat), retired, settings),
                "announced a retired threat",
            )
        }
    }

    @Test
    fun `the same inputs always give the same answer`() {
        val settings = AlertSettings()
        repeat(500) { i ->
            val threats = List(random.nextInt(1, 12)) { n ->
                randomThreat("t$i-$n", random.nextDouble(0.0, 360.0), random.nextDouble(20.0, 3_000.0))
            }
            val car = movingCar(random.nextDouble(0.0, 140.0))

            val first = AlertEngine.evaluate(1L, car, threats, EngineState(), settings)
            val second = AlertEngine.evaluate(1L, car, threats.reversed(), EngineState(), settings)

            // Reordering the input must not change the answer: the ranking has
            // to be a total order, not "whichever happened to be first".
            assertEquals(
                first?.threatId, second?.threatId,
                "the choice depended on the order the threats arrived in",
            )
        }
    }
}
