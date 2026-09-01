package au.radar.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Decides what, if anything, to say to the driver right now.
 *
 * The rules and the reasoning behind them are in docs/alert-engine.md. Both this
 * and the Swift implementation are verified against
 * shared/alert-engine-fixtures.json, which is the authority when the two
 * disagree.
 *
 * The engine is pure: same inputs, same output, no clock and no I/O. Everything
 * it remembers arrives as [EngineState] and leaves through [record]. It also
 * takes no view on muting — that is a playback decision for the caller, because
 * a muted app should still flash the screen.
 */
object AlertEngine {

    /** Beyond this, nothing is worth the trigonometry. */
    const val MAX_CONSIDER_M = 3_000.0

    /** Below this speed the time-based range collapses, so a flat range is used. */
    const val CRAWL_SPEED_KMH = 8.0
    const val CRAWL_RANGE_M = 400.0

    const val MIN_TRIGGER_M = 300.0
    const val MAX_TRIGGER_M = 2_000.0

    /** How wide "in front of me" is, before speed narrows it. */
    const val CONE_BASE_DEG = 70.0
    const val CONE_PER_KMH = 0.45
    const val CONE_MIN_DEG = 22.0

    /**
     * Past this angle a threat is beside or behind you. The radius still
     * reaches out to the sides — a police car at the intersection you are
     * approaching sits near 90 degrees — but not backwards, because something
     * behind you has already been passed.
     */
    const val NEARBY_MAX_ANGLE_DEG = 120.0

    /** How far a threat's own direction may differ from ours before it is the other carriageway. */
    const val CARRIAGEWAY_TOLERANCE_DEG = 60.0

    /** How far off the route line a threat may sit and still count as on it. */
    const val ROUTE_CORRIDOR_M = 45.0

    const val REPEAT_COOLDOWN_MS = 10 * 60 * 1000L
    const val GLOBAL_GAP_MS = 6_000L
    const val PARKED_SILENCE_MS = 60_000L

    const val MIN_CONFIDENCE = 0.3
    const val TRUSTED_CONFIDENCE = 0.5

    /**
     * The single announcement due this tick, or null for silence.
     *
     * Silence is the common case and the correct default: an app that talks
     * constantly is one the driver stops listening to.
     */
    fun evaluate(
        now: Long,
        car: CarState,
        threats: List<Threat>,
        state: EngineState,
        settings: AlertSettings = AlertSettings(),
        route: RouteContext? = null,
    ): Announcement? {
        // Parked for a while: say nothing at all, whatever is nearby.
        if (car.stationaryForMs > PARKED_SILENCE_MS) return null

        // An explicit floor the driver set, for anyone who does not want to be
        // spoken to while crawling. Off by default.
        if (settings.minSpeedKmh > 0 && car.speedKmh < settings.minSpeedKmh) return null

        // Never stack one announcement on top of another.
        val lastAny = state.lastAnyAnnounceAt
        if (lastAny != null && now - lastAny < GLOBAL_GAP_MS) return null

        val due = threats.mapNotNull { assess(now, car, it, state, settings, route) }
        if (due.isEmpty()) return null

        // Severity first, then whichever we reach soonest. On-route distances
        // are measured along the road and so are never shorter than the
        // straight line, which biases the tie a little towards things beside
        // you — the right way to be wrong, since those are the ones you cannot
        // see coming.
        val chosen = due.sortedWith(
            compareByDescending<Candidate> { it.effectiveSeverity }.thenBy { it.distanceM },
        ).first()

        val level = when {
            !chosen.kind.voice -> AnnouncementLevel.CHIME
            chosen.threat.isCamera || chosen.effectiveSeverity >= 2 -> AnnouncementLevel.SPEAK
            else -> AnnouncementLevel.CHIME
        }

        return Announcement(
            threatId = chosen.threat.id,
            level = level,
            spokenText = "${label(chosen.threat)}, ${spokenDistance(chosen.distanceM)}",
            distanceM = chosen.distanceM,
            flash = settings.flashEnabled && chosen.kind.flash,
            relation = chosen.relation,
        )
    }

    private data class Candidate(
        val threat: Threat,
        val distanceM: Double,
        val effectiveSeverity: Int,
        val relation: Relation,
        val kind: KindSettings,
    )

    private fun assess(
        now: Long,
        car: CarState,
        threat: Threat,
        state: EngineState,
        settings: AlertSettings,
        route: RouteContext?,
    ): Candidate? {
        if (threat.id in state.retired) return null
        if (threat.confidence < MIN_CONFIDENCE) return null

        val kind = settings.forKind(threat.kind)
        if (!kind.enabled) return null

        val lastForThreat = state.lastAnnouncedAt[threat.id]
        if (lastForThreat != null && now - lastForThreat < REPEAT_COOLDOWN_MS) return null

        val straightDistance = Geo.distanceM(car.lat, car.lon, threat.lat, threat.lon)
        if (straightDistance > MAX_CONSIDER_M) return null

        // A camera that names the direction it faces only counts on that side,
        // however close it is. The other carriageway is not your problem.
        val heading = car.headingDeg
        val faces = threat.bearingDeg
        if (faces != null && heading != null &&
            Geo.bearingDelta(heading, faces) > CARRIAGEWAY_TOLERANCE_DEG
        ) {
            return null
        }

        val placement = classify(car, threat, straightDistance, settings, route) ?: return null

        val leadSeconds = kind.leadSeconds * when (placement.relation) {
            Relation.ON_ROUTE, Relation.SAME_ROAD -> settings.sameRoadLeadMultiplier
            else -> 1.0
        }

        // Two different questions, so two different ranges.
        //
        // Down the road, the range is a lead time in metres, floored by the
        // radius so a slow crawl still gets a useful warning.
        //
        // To the side, only the radius applies. Letting a long lead time reach
        // sideways would warn about a police car half a kilometre off your
        // route purely because you happened to be going fast — the direction
        // you are travelling says nothing about how soon you reach something
        // beside you.
        val triggerRange = if (placement.relation == Relation.NEARBY) {
            kind.radiusM
        } else {
            maxOf(kind.radiusM, leadRange(car.speedKmh, leadSeconds))
        }
        if (placement.distanceM > triggerRange) return null

        return Candidate(
            threat = threat,
            distanceM = placement.distanceM,
            effectiveSeverity = effectiveSeverity(threat),
            relation = placement.relation,
            kind = kind,
        )
    }

    private data class Placement(val relation: Relation, val distanceM: Double)

    /**
     * Work out how a threat relates to the road under the car.
     *
     * The order matters. The route, when there is one, is the best answer
     * available: it follows the road around bends, which nothing derived from
     * an instantaneous heading can do. Failing that, a narrow corridor down the
     * heading is a good proxy for "my road", and the wider cone catches what is
     * merely in front. The radius is the backstop for everything geometry
     * cannot settle.
     */
    private fun classify(
        car: CarState,
        threat: Threat,
        straightDistance: Double,
        settings: AlertSettings,
        route: RouteContext?,
    ): Placement? {
        if (route != null && route.aheadGeometry.size >= 2) {
            val onLine = RouteTracker.locate(route.aheadGeometry, threat.lat, threat.lon)
            // The slice starts at the car, so a non-negative along-value is
            // genuinely in front of us however the road bends between here and
            // there. Exclude the very end of the slice, where every point off
            // the end of the route projects onto the final vertex.
            if (onLine != null && onLine.offM <= ROUTE_CORRIDOR_M && onLine.alongM > 0) {
                return Placement(Relation.ON_ROUTE, onLine.alongM)
            }
        }

        val heading = car.headingDeg
            // No heading, so no notion of ahead. Everything in range is simply near.
            ?: return Placement(Relation.NEARBY, straightDistance)

        val course = Geo.bearingDeg(car.lat, car.lon, threat.lat, threat.lon)
        val relativeAngle = Geo.bearingDelta(heading, course)
        val radians = Math.toRadians(relativeAngle)

        val alongTrack = straightDistance * cos(radians)
        val crossTrack = abs(straightDistance * sin(radians))

        if (alongTrack > 0 && crossTrack <= corridorHalfWidth(straightDistance, settings)) {
            return Placement(Relation.SAME_ROAD, straightDistance)
        }
        if (relativeAngle <= coneHalfAngle(car.speedKmh)) {
            return Placement(Relation.AHEAD, straightDistance)
        }
        if (relativeAngle <= NEARBY_MAX_ANGLE_DEG) {
            return Placement(Relation.NEARBY, straightDistance)
        }
        return null
    }

    /**
     * How far to either side still counts as the road you are on.
     *
     * A fixed width would be wrong at both ends: too generous close up, where
     * it swallows the parallel street, and too mean far off, where a degree of
     * heading noise moves the corridor by tens of metres. So it widens with
     * distance, and stops widening before it can reach the next road over.
     */
    fun corridorHalfWidth(distanceM: Double, settings: AlertSettings): Double =
        (settings.corridorHalfWidthM + distanceM * settings.corridorWidenPerM)
            .coerceAtMost(settings.corridorMaxHalfWidthM)

    /** The cone narrows as speed rises: at 100 km/h, only what is nearly straight ahead. */
    fun coneHalfAngle(speedKmh: Double): Double =
        (CONE_BASE_DEG - speedKmh * CONE_PER_KMH).coerceIn(CONE_MIN_DEG, CONE_BASE_DEG)

    /** Warning distance is a lead *time* converted to metres at the current speed. */
    fun leadRange(speedKmh: Double, leadSeconds: Double): Double {
        if (speedKmh < CRAWL_SPEED_KMH) return CRAWL_RANGE_M
        return (speedKmh / 3.6 * leadSeconds).coerceIn(MIN_TRIGGER_M, MAX_TRIGGER_M)
    }

    /**
     * A report we half-believe is worth a tone, not a voice. Cameras are exempt:
     * a camera dataset is either current or stale, which is a different problem
     * from an unreliable witness.
     */
    fun effectiveSeverity(threat: Threat): Int = when {
        threat.isCamera -> maxOf(threat.severity, 2)
        threat.confidence < TRUSTED_CONFIDENCE -> minOf(threat.severity, 1)
        else -> threat.severity
    }

    /** Fold an announcement back into the state for the next tick. */
    fun record(state: EngineState, announcement: Announcement, now: Long): EngineState =
        state.copy(
            lastAnnouncedAt = state.lastAnnouncedAt + (announcement.threatId to now),
            lastAnyAnnounceAt = now,
        )

    /**
     * Retire threats the car has passed. Once behind and receding, a threat is
     * finished for this trip rather than merely cooled down.
     */
    fun retirePassed(state: EngineState, car: CarState, threats: List<Threat>): EngineState {
        val heading = car.headingDeg ?: return state
        val passed = threats.filter { threat ->
            val course = Geo.bearingDeg(car.lat, car.lon, threat.lat, threat.lon)
            val distance = Geo.distanceM(car.lat, car.lon, threat.lat, threat.lon)
            Geo.bearingDelta(heading, course) > 135.0 && distance > 150.0
        }.map { it.id }
        return if (passed.isEmpty()) state else state.copy(retired = state.retired + passed)
    }

    /** How a person would say the distance out loud. */
    fun spokenDistance(metres: Double): String {
        if (metres < 1_000) {
            val rounded = (metres / 100.0).roundToLong() * 100
            return "$rounded metres"
        }
        val km = (metres / 100.0).roundToLong() / 10.0
        return if (km == 1.0) "1 kilometre" else "$km kilometres"
    }

    fun label(threat: Threat): String = when (threat.kind) {
        "fixed_speed" -> "Speed camera"
        "red_light" -> "Red light camera"
        "red_light_speed" -> "Red light and speed camera"
        "average_speed_start" -> "Average speed zone starts"
        "average_speed_end" -> "Average speed zone ends"
        "mobile_zone" -> "Mobile camera zone"
        "trailer" -> "Camera trailer"
        "mobile_camera" -> "Mobile camera reported"
        "police" -> "Police reported"
        "crash" -> "Crash ahead"
        "closure" -> "Road closed"
        "roadwork" -> "Roadworks"
        "flood" -> "Flooding"
        "fire" -> "Fire"
        "congestion" -> "Slow traffic"
        "object_on_road" -> "Object on road"
        "stopped_vehicle" -> "Stopped vehicle"
        "event" -> "Event ahead"
        "alpine" -> "Alpine conditions"
        else -> "Hazard ahead"
    }

    /** Rounded speed limit for the on-screen badge, or null when unknown. */
    fun postedLimit(threat: Threat): Int? = threat.speedLimit?.takeIf { it > 0 }?.let {
        (it / 10.0).roundToInt() * 10
    }
}
