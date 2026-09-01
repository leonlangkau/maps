import Foundation

/// Decides what, if anything, to say to the driver right now.
///
/// The rules and the reasoning behind them are in `docs/alert-engine.md`. This
/// and the Kotlin implementation are both verified against
/// `shared/alert-engine-fixtures.json`, which is the authority when the two
/// disagree.
///
/// The engine is pure: same inputs, same output, no clock and no I/O. Everything
/// it remembers arrives as `EngineState` and leaves through `record`.
public enum AlertEngine {

    /// Beyond this, nothing is worth the trigonometry.
    public static let maxConsiderM = 3_000.0

    /// Used when there is no usable heading, so "ahead" has no meaning.
    public static let stationaryRadiusM = 500.0

    /// Below this speed the time-based range collapses, so a flat range is used.
    public static let crawlSpeedKmh = 8.0
    public static let crawlRangeM = 400.0

    public static let minTriggerM = 300.0
    public static let maxTriggerM = 1_500.0

    /// How wide "ahead" is, before speed narrows it.
    public static let coneBaseDeg = 70.0
    public static let conePerKmh = 0.45
    public static let coneMinDeg = 22.0

    /// How far a threat's own direction may differ from ours before it is the
    /// other carriageway.
    public static let carriagewayToleranceDeg = 60.0

    public static let repeatCooldownMs: Int64 = 10 * 60 * 1000
    public static let globalGapMs: Int64 = 6_000
    public static let parkedSilenceMs: Int64 = 60_000

    public static let minConfidence = 0.3
    public static let trustedConfidence = 0.5

    private static let leadCameraS = 25.0
    private static let leadCriticalS = 35.0
    private static let leadMajorS = 25.0
    private static let leadMinorS = 15.0

    private struct Candidate {
        let threat: Threat
        let distanceM: Double
        let effectiveSeverity: Int
    }

    /// The single announcement due this tick, or nil for silence.
    ///
    /// Silence is the common case and the correct default: an app that talks
    /// constantly is one the driver stops listening to.
    public static func evaluate(
        now: Int64,
        car: CarState,
        threats: [Threat],
        state: EngineState
    ) -> Announcement? {
        // Parked for a while: say nothing at all, whatever is nearby.
        if car.stationaryForMs > parkedSilenceMs { return nil }

        // Never stack one announcement on top of another.
        if let lastAny = state.lastAnyAnnounceAt, now - lastAny < globalGapMs { return nil }

        let due = threats.compactMap { assess(now: now, car: car, threat: $0, state: state) }
        guard !due.isEmpty else { return nil }

        // Severity first, then whichever we reach soonest.
        let chosen = due.sorted { lhs, rhs in
            if lhs.effectiveSeverity != rhs.effectiveSeverity {
                return lhs.effectiveSeverity > rhs.effectiveSeverity
            }
            return lhs.distanceM < rhs.distanceM
        }[0]

        let level: AnnouncementLevel =
            (chosen.threat.isCamera || chosen.effectiveSeverity >= 2) ? .speak : .chime

        return Announcement(
            threatId: chosen.threat.id,
            level: level,
            spokenText: "\(label(chosen.threat)), \(spokenDistance(chosen.distanceM))",
            distanceM: chosen.distanceM
        )
    }

    private static func assess(
        now: Int64,
        car: CarState,
        threat: Threat,
        state: EngineState
    ) -> Candidate? {
        if state.retired.contains(threat.id) { return nil }
        if threat.confidence < minConfidence { return nil }

        if let lastForThreat = state.lastAnnouncedAt[threat.id],
           now - lastForThreat < repeatCooldownMs {
            return nil
        }

        let distance = Geo.distanceM(car.lat, car.lon, threat.lat, threat.lon)
        if distance > maxConsiderM { return nil }

        if let heading = car.headingDeg {
            let course = Geo.bearingDeg(car.lat, car.lon, threat.lat, threat.lon)
            if Geo.bearingDelta(heading, course) > coneHalfAngle(speedKmh: car.speedKmh) {
                return nil
            }

            // A threat that names its own direction only counts on that side.
            if let faces = threat.bearingDeg,
               Geo.bearingDelta(heading, faces) > carriagewayToleranceDeg {
                return nil
            }

            if distance > triggerRange(speedKmh: car.speedKmh, threat: threat) { return nil }
        } else {
            // No heading, so no notion of ahead. A plain radius is all that is left.
            if distance > stationaryRadiusM { return nil }
        }

        return Candidate(
            threat: threat,
            distanceM: distance,
            effectiveSeverity: effectiveSeverity(threat)
        )
    }

    /// The cone narrows as speed rises: at 100 km/h, only what is nearly straight ahead.
    public static func coneHalfAngle(speedKmh: Double) -> Double {
        min(max(coneBaseDeg - speedKmh * conePerKmh, coneMinDeg), coneBaseDeg)
    }

    /// Warning distance is a lead *time* converted to metres at the current speed.
    public static func triggerRange(speedKmh: Double, threat: Threat) -> Double {
        if speedKmh < crawlSpeedKmh { return crawlRangeM }
        let lead: Double
        if threat.isCamera {
            lead = leadCameraS
        } else if threat.severity >= 3 {
            lead = leadCriticalS
        } else if threat.severity == 2 {
            lead = leadMajorS
        } else {
            lead = leadMinorS
        }
        return min(max(speedKmh / 3.6 * lead, minTriggerM), maxTriggerM)
    }

    /// A report we half-believe is worth a tone, not a voice. Cameras are exempt:
    /// a camera dataset is either current or stale, which is a different problem
    /// from an unreliable witness.
    public static func effectiveSeverity(_ threat: Threat) -> Int {
        if threat.isCamera { return max(threat.severity, 2) }
        if threat.confidence < trustedConfidence { return min(threat.severity, 1) }
        return threat.severity
    }

    /// Fold an announcement back into the state for the next tick.
    public static func record(
        _ state: EngineState,
        announcement: Announcement,
        now: Int64
    ) -> EngineState {
        var next = state
        next.lastAnnouncedAt[announcement.threatId] = now
        next.lastAnyAnnounceAt = now
        return next
    }

    /// Retire threats the car has passed. Once behind and receding, a threat is
    /// finished for this trip rather than merely cooled down.
    public static func retirePassed(
        _ state: EngineState,
        car: CarState,
        threats: [Threat]
    ) -> EngineState {
        guard let heading = car.headingDeg else { return state }
        let passed = threats.filter { threat in
            let course = Geo.bearingDeg(car.lat, car.lon, threat.lat, threat.lon)
            let distance = Geo.distanceM(car.lat, car.lon, threat.lat, threat.lon)
            return Geo.bearingDelta(heading, course) > 135 && distance > 150
        }.map(\.id)

        guard !passed.isEmpty else { return state }
        var next = state
        next.retired.formUnion(passed)
        return next
    }

    /// How a person would say the distance out loud.
    public static func spokenDistance(_ metres: Double) -> String {
        if metres < 1_000 {
            let rounded = Int((metres / 100).rounded()) * 100
            return "\(rounded) metres"
        }
        let km = (metres / 100).rounded() / 10
        if km == 1.0 { return "1 kilometre" }
        return String(format: "%.1f kilometres", km)
    }

    public static func label(_ threat: Threat) -> String {
        switch threat.kind {
        case "fixed_speed": return "Speed camera"
        case "red_light": return "Red light camera"
        case "red_light_speed": return "Red light and speed camera"
        case "average_speed_start": return "Average speed zone starts"
        case "average_speed_end": return "Average speed zone ends"
        case "mobile_zone": return "Mobile camera zone"
        case "trailer": return "Camera trailer"
        case "mobile_camera": return "Mobile camera reported"
        case "police": return "Police reported"
        case "crash": return "Crash ahead"
        case "closure": return "Road closed"
        case "roadwork": return "Roadworks"
        case "flood": return "Flooding"
        case "fire": return "Fire"
        case "congestion": return "Slow traffic"
        case "object_on_road": return "Object on road"
        case "stopped_vehicle": return "Stopped vehicle"
        case "event": return "Event ahead"
        case "alpine": return "Alpine conditions"
        default: return "Hazard ahead"
        }
    }

    /// Rounded speed limit for the on-screen badge, or nil when unknown.
    public static func postedLimit(_ threat: Threat) -> Int? {
        guard let limit = threat.speedLimit, limit > 0 else { return nil }
        return Int((Double(limit) / 10).rounded()) * 10
    }
}

/// Turns API payloads into what the engine consumes.
public enum ThreatMapper {
    public static func fromAlerts(_ collection: GeoJsonFeatureCollection) -> [Threat] {
        collection.features.compactMap { feature in
            let coords = feature.geometry.coordinates
            guard coords.count >= 2 else { return nil }
            let props = feature.properties
            return Threat(
                id: props.id,
                kind: props.kind,
                lat: coords[1],
                lon: coords[0],
                bearingDeg: props.bearing,
                severity: props.severity,
                confidence: props.confidence,
                isCamera: false
            )
        }
    }

    public static func fromCameras(_ cameras: [ApiCamera]) -> [Threat] {
        cameras.map { camera in
            Threat(
                id: camera.id,
                kind: camera.kind,
                lat: camera.lat,
                lon: camera.lon,
                bearingDeg: camera.bearing,
                // Cameras are floored at speaking severity by the engine anyway;
                // this keeps the ordering sensible against live hazards.
                severity: 2,
                confidence: 1.0,
                isCamera: true,
                speedLimit: camera.speedLimit
            )
        }
    }
}
