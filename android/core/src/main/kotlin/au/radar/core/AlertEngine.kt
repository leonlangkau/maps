package au.radar.core

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Decides what, if anything, to say to the driver right now.
 *
 * The rules and the reasoning behind them are in docs/alert-engine.md. Both this
 * and the Swift implementation are verified against
 * shared/alert-engine-fixtures.json, which is the authority when the two
 * disagree.
 *
 * The engine is pure: same inputs, same output, no clock and no I/O. Everything
 * it remembers arrives as [EngineState] and leaves through [record].
 */
object AlertEngine {

    /** Beyond this, nothing is worth the trigonometry. */
    const val MAX_CONSIDER_M = 3_000.0

    /** Used when there is no usable heading, so "ahead" has no meaning. */
    const val STATIONARY_RADIUS_M = 500.0

    /** Below this speed the time-based range collapses, so a flat range is used. */
    const val CRAWL_SPEED_KMH = 8.0
    const val CRAWL_RANGE_M = 400.0

    const val MIN_TRIGGER_M = 300.0
    const val MAX_TRIGGER_M = 1_500.0

    /** How wide "ahead" is, before speed narrows it. */
    const val CONE_BASE_DEG = 70.0
    const val CONE_PER_KMH = 0.45
    const val CONE_MIN_DEG = 22.0

    /** How far a threat's own direction may differ from ours before it is the other carriageway. */
    const val CARRIAGEWAY_TOLERANCE_DEG = 60.0

    const val REPEAT_COOLDOWN_MS = 10 * 60 * 1000L
    const val GLOBAL_GAP_MS = 6_000L
    const val PARKED_SILENCE_MS = 60_000L

    const val MIN_CONFIDENCE = 0.3
    const val TRUSTED_CONFIDENCE = 0.5

    private const val LEAD_CAMERA_S = 25.0
    private const val LEAD_CRITICAL_S = 35.0
    private const val LEAD_MAJOR_S = 25.0
    private const val LEAD_MINOR_S = 15.0

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
    ): Announcement? {
        // Parked for a while: say nothing at all, whatever is nearby.
        if (car.stationaryForMs > PARKED_SILENCE_MS) return null

        // Never stack one announcement on top of another.
        val lastAny = state.lastAnyAnnounceAt
        if (lastAny != null && now - lastAny < GLOBAL_GAP_MS) return null

        val due = threats.mapNotNull { threat -> assess(now, car, threat, state) }
        if (due.isEmpty()) return null

        // Severity first, then whichever we reach soonest.
        val chosen = due.sortedWith(
            compareByDescending<Candidate> { it.effectiveSeverity }.thenBy { it.distanceM },
        ).first()

        val level = if (chosen.threat.isCamera || chosen.effectiveSeverity >= 2) {
            AnnouncementLevel.SPEAK
        } else {
            AnnouncementLevel.CHIME
        }

        return Announcement(
            threatId = chosen.threat.id,
            level = level,
            spokenText = "${label(chosen.threat)}, ${spokenDistance(chosen.distanceM)}",
            distanceM = chosen.distanceM,
        )
    }

    private data class Candidate(
        val threat: Threat,
        val distanceM: Double,
        val effectiveSeverity: Int,
    )

    private fun assess(
        now: Long,
        car: CarState,
        threat: Threat,
        state: EngineState,
    ): Candidate? {
        if (threat.id in state.retired) return null
        if (threat.confidence < MIN_CONFIDENCE) return null

        val lastForThreat = state.lastAnnouncedAt[threat.id]
        if (lastForThreat != null && now - lastForThreat < REPEAT_COOLDOWN_MS) return null

        val distance = Geo.distanceM(car.lat, car.lon, threat.lat, threat.lon)
        if (distance > MAX_CONSIDER_M) return null

        val heading = car.headingDeg
        if (heading == null) {
            // No heading, so no notion of ahead. A plain radius is all that is left.
            if (distance > STATIONARY_RADIUS_M) return null
        } else {
            val course = Geo.bearingDeg(car.lat, car.lon, threat.lat, threat.lon)
            if (Geo.bearingDelta(heading, course) > coneHalfAngle(car.speedKmh)) return null

            // A threat that names its own direction only counts on that side.
            val faces = threat.bearingDeg
            if (faces != null && Geo.bearingDelta(heading, faces) > CARRIAGEWAY_TOLERANCE_DEG) {
                return null
            }

            if (distance > triggerRange(car.speedKmh, threat)) return null
        }

        return Candidate(threat, distance, effectiveSeverity(threat))
    }

    /** The cone narrows as speed rises: at 100 km/h, only what is nearly straight ahead. */
    fun coneHalfAngle(speedKmh: Double): Double =
        (CONE_BASE_DEG - speedKmh * CONE_PER_KMH).coerceIn(CONE_MIN_DEG, CONE_BASE_DEG)

    /** Warning distance is a lead *time* converted to metres at the current speed. */
    fun triggerRange(speedKmh: Double, threat: Threat): Double {
        if (speedKmh < CRAWL_SPEED_KMH) return CRAWL_RANGE_M
        val lead = when {
            threat.isCamera -> LEAD_CAMERA_S
            threat.severity >= 3 -> LEAD_CRITICAL_S
            threat.severity == 2 -> LEAD_MAJOR_S
            else -> LEAD_MINOR_S
        }
        return (speedKmh / 3.6 * lead).coerceIn(MIN_TRIGGER_M, MAX_TRIGGER_M)
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
