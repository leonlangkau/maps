import Foundation

/// Decides what, if anything, to say to the driver right now.
///
/// The rules and the reasoning behind them are in `docs/alert-engine.md`. This
/// and the Kotlin implementation are both verified against
/// `shared/alert-engine-fixtures.json`, whose expectations come from a third
/// implementation of the same rules — so a bug shared by these two still fails.
///
/// The engine is pure: same inputs, same output, no clock and no I/O. Everything
/// it remembers arrives as `EngineState` and leaves through `record`. It also
/// takes no view on muting — that is a playback decision for the caller, because
/// a muted app should still flash the screen.
public enum AlertEngine {

    /// Beyond this, nothing is worth the trigonometry.
    public static let maxConsiderM = 3_000.0

    /// Below this speed the time-based range collapses, so a flat range is used.
    public static let crawlSpeedKmh = 8.0
    public static let crawlRangeM = 400.0

    public static let minTriggerM = 300.0
    public static let maxTriggerM = 2_000.0

    /// How wide "in front of me" is, before speed narrows it.
    public static let coneBaseDeg = 70.0
    public static let conePerKmh = 0.45
    public static let coneMinDeg = 22.0

    /// Past this angle a threat is beside or behind you. The radius still
    /// reaches out to the sides — a police car at the intersection you are
    /// approaching sits near 90 degrees — but not backwards, because something
    /// behind you has already been passed.
    public static let nearbyMaxAngleDeg = 120.0

    /// How far a threat's own direction may differ from ours before it is the
    /// other carriageway.
    public static let carriagewayToleranceDeg = 60.0

    /// How far off the route line a threat may sit and still count as on it.
    public static let routeCorridorM = 45.0

    public static let repeatCooldownMs: Int64 = 10 * 60 * 1000
    public static let globalGapMs: Int64 = 6_000
    public static let parkedSilenceMs: Int64 = 60_000

    public static let minConfidence = 0.3
    public static let trustedConfidence = 0.5

    private struct Candidate {
        let threat: Threat
        let distanceM: Double
        let effectiveSeverity: Int
        let relation: Relation
        let kind: KindSettings
    }

    private struct Placement {
        let relation: Relation
        let distanceM: Double
    }

    /// The single announcement due this tick, or nil for silence.
    ///
    /// Silence is the common case and the correct default: an app that talks
    /// constantly is one the driver stops listening to.
    public static func evaluate(
        now: Int64,
        car: CarState,
        threats: [Threat],
        state: EngineState,
        settings: AlertSettings = AlertSettings(),
        route: RouteContext? = nil
    ) -> Announcement? {
        // Parked for a while: say nothing at all, whatever is nearby.
        if car.stationaryForMs > parkedSilenceMs { return nil }

        // An explicit floor the driver set, for anyone who does not want to be
        // spoken to while crawling. Off by default.
        if settings.minSpeedKmh > 0, car.speedKmh < settings.minSpeedKmh { return nil }

        // Never stack one announcement on top of another.
        if let lastAny = state.lastAnyAnnounceAt, now - lastAny < globalGapMs { return nil }

        let due = threats.compactMap {
            assess(now: now, car: car, threat: $0, state: state, settings: settings, route: route)
        }
        guard !due.isEmpty else { return nil }

        // Severity first, then whichever we reach soonest. On-route distances
        // are measured along the road and so are never shorter than the
        // straight line, which biases the tie a little towards things beside
        // you — the right way to be wrong, since those are the ones you cannot
        // see coming.
        let chosen = due.sorted { lhs, rhs in
            if lhs.effectiveSeverity != rhs.effectiveSeverity {
                return lhs.effectiveSeverity > rhs.effectiveSeverity
            }
            return lhs.distanceM < rhs.distanceM
        }[0]

        let level: AnnouncementLevel
        if !chosen.kind.voice {
            level = .chime
        } else if chosen.threat.isCamera || chosen.effectiveSeverity >= 2 {
            level = .speak
        } else {
            level = .chime
        }

        return Announcement(
            threatId: chosen.threat.id,
            level: level,
            spokenText: "\(label(chosen.threat)), \(spokenDistance(chosen.distanceM))",
            distanceM: chosen.distanceM,
            flash: settings.flashEnabled && chosen.kind.flash,
            relation: chosen.relation
        )
    }

    private static func assess(
        now: Int64,
        car: CarState,
        threat: Threat,
        state: EngineState,
        settings: AlertSettings,
        route: RouteContext?
    ) -> Candidate? {
        if state.retired.contains(threat.id) { return nil }
        if threat.confidence < minConfidence { return nil }

        let kind = settings.forKind(threat.kind)
        guard kind.enabled else { return nil }

        if let lastForThreat = state.lastAnnouncedAt[threat.id],
           now - lastForThreat < repeatCooldownMs {
            return nil
        }

        let straightDistance = Geo.distanceM(car.lat, car.lon, threat.lat, threat.lon)
        if straightDistance > maxConsiderM { return nil }

        // A camera that names the direction it faces only counts on that side,
        // however close it is. The other carriageway is not your problem.
        if let faces = threat.bearingDeg, let heading = car.headingDeg,
           Geo.bearingDelta(heading, faces) > carriagewayToleranceDeg {
            return nil
        }

        guard let placement = classify(
            car: car, threat: threat, straightDistance: straightDistance,
            settings: settings, route: route
        ) else { return nil }

        let multiplier: Double
        switch placement.relation {
        case .onRoute, .sameRoad: multiplier = settings.sameRoadLeadMultiplier
        case .ahead, .nearby: multiplier = 1
        }
        let leadSeconds = kind.leadSeconds * multiplier

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
        let triggerRange = placement.relation == .nearby
            ? kind.radiusM
            : max(kind.radiusM, leadRange(speedKmh: car.speedKmh, leadSeconds: leadSeconds))

        if placement.distanceM > triggerRange { return nil }

        return Candidate(
            threat: threat,
            distanceM: placement.distanceM,
            effectiveSeverity: effectiveSeverity(threat),
            relation: placement.relation,
            kind: kind
        )
    }

    /// Work out how a threat relates to the road under the car.
    ///
    /// The order matters. The route, when there is one, is the best answer
    /// available: it follows the road around bends, which nothing derived from
    /// an instantaneous heading can do. Failing that, a narrow corridor down the
    /// heading is a good proxy for "my road", and the wider cone catches what is
    /// merely in front. The radius is the backstop for everything geometry
    /// cannot settle.
    private static func classify(
        car: CarState,
        threat: Threat,
        straightDistance: Double,
        settings: AlertSettings,
        route: RouteContext?
    ) -> Placement? {
        if let route, route.aheadGeometry.count >= 2 {
            // The slice starts at the car, so a positive along-value is
            // genuinely in front of us however the road bends in between.
            if let onLine = RouteTracker.locate(
                geometry: route.aheadGeometry, lat: threat.lat, lon: threat.lon
            ), onLine.offM <= routeCorridorM, onLine.alongM > 0 {
                return Placement(relation: .onRoute, distanceM: onLine.alongM)
            }
        }

        // No heading, so no notion of ahead. Everything in range is simply near.
        guard let heading = car.headingDeg else {
            return Placement(relation: .nearby, distanceM: straightDistance)
        }

        let course = Geo.bearingDeg(car.lat, car.lon, threat.lat, threat.lon)
        let relativeAngle = Geo.bearingDelta(heading, course)
        let radians = relativeAngle * .pi / 180

        let alongTrack = straightDistance * cos(radians)
        let crossTrack = abs(straightDistance * sin(radians))

        if alongTrack > 0,
           crossTrack <= corridorHalfWidth(distanceM: straightDistance, settings: settings) {
            return Placement(relation: .sameRoad, distanceM: straightDistance)
        }
        if relativeAngle <= coneHalfAngle(speedKmh: car.speedKmh) {
            return Placement(relation: .ahead, distanceM: straightDistance)
        }
        if relativeAngle <= nearbyMaxAngleDeg {
            return Placement(relation: .nearby, distanceM: straightDistance)
        }
        return nil
    }

    /// How far to either side still counts as the road you are on.
    ///
    /// A fixed width would be wrong at both ends: too generous close up, where
    /// it swallows the parallel street, and too mean far off, where a degree of
    /// heading noise moves the corridor by tens of metres. So it widens with
    /// distance, and stops widening before it can reach the next road over.
    public static func corridorHalfWidth(distanceM: Double, settings: AlertSettings) -> Double {
        min(
            settings.corridorHalfWidthM + distanceM * settings.corridorWidenPerM,
            settings.corridorMaxHalfWidthM
        )
    }

    /// The cone narrows as speed rises: at 100 km/h, only what is nearly straight ahead.
    public static func coneHalfAngle(speedKmh: Double) -> Double {
        min(max(coneBaseDeg - speedKmh * conePerKmh, coneMinDeg), coneBaseDeg)
    }

    /// Warning distance is a lead *time* converted to metres at the current speed.
    public static func leadRange(speedKmh: Double, leadSeconds: Double) -> Double {
        if speedKmh < crawlSpeedKmh { return crawlRangeM }
        return min(max(speedKmh / 3.6 * leadSeconds, minTriggerM), maxTriggerM)
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
        _ state: EngineState, announcement: Announcement, now: Int64
    ) -> EngineState {
        var next = state
        next.lastAnnouncedAt[announcement.threatId] = now
        next.lastAnyAnnounceAt = now
        return next
    }

    /// Retire threats the car has passed. Once behind and receding, a threat is
    /// finished for this trip rather than merely cooled down.
    public static func retirePassed(
        _ state: EngineState, car: CarState, threats: [Threat]
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
