import Foundation

public struct RoutePoint: Codable, Equatable, Sendable {
    public let lat: Double
    public let lon: Double

    public init(lat: Double, lon: Double) {
        self.lat = lat
        self.lon = lon
    }
}

/// Google's encoded-polyline format, which is what both Mapbox Directions and
/// Valhalla return. Precision 6 is the default here because that is what the
/// Worker asks Mapbox for; precision 5 is the older convention and still turns
/// up in third-party data.
public enum Polyline {

    public static func decode(_ encoded: String, precision: Int = 6) -> [RoutePoint] {
        guard !encoded.isEmpty else { return [] }

        let factor = pow(10.0, Double(precision))
        let characters = Array(encoded.unicodeScalars)
        var points: [RoutePoint] = []
        points.reserveCapacity(characters.count / 4)

        var index = 0
        var lat = 0
        var lon = 0

        while index < characters.count {
            guard let dLat = readVarint(characters, from: index) else { break }
            index = dLat.next
            lat += dLat.delta

            guard let dLon = readVarint(characters, from: index) else { break }
            index = dLon.next
            lon += dLon.delta

            points.append(RoutePoint(lat: Double(lat) / factor, lon: Double(lon) / factor))
        }
        return points
    }

    /// Returns the decoded delta and the next index, or nil on a truncated string.
    private static func readVarint(
        _ characters: [Unicode.Scalar],
        from start: Int
    ) -> (delta: Int, next: Int)? {
        var index = start
        var shift = 0
        var result = 0
        var byte = 0

        repeat {
            guard index < characters.count else { return nil }
            byte = Int(characters[index].value) - 63
            index += 1
            result |= (byte & 0x1f) << shift
            shift += 5
        } while byte >= 0x20

        // The low bit is the sign, inverted for negatives.
        let delta = (result & 1) != 0 ? ~(result >> 1) : (result >> 1)
        return (delta, index)
    }
}

/// Where the car is along the route, and whether it is still on it.
public struct RouteProgress: Sendable {
    /// The position snapped onto the route line.
    public let snappedLat: Double
    public let snappedLon: Double
    public let distanceAlongM: Double
    public let distanceRemainingM: Double
    public let durationRemainingS: Double
    /// How far the car is from the line. Large values mean a wrong turn.
    public let offRouteByM: Double
    public let isOffRoute: Bool
    public let stepIndex: Int
    public let distanceToManeuverM: Double
    public let currentStep: RouteStep?
    public let nextStep: RouteStep?
}

/// Tracks a car against a route.
///
/// Every tick projects the car onto every segment and takes the nearest. A
/// windowed search around the previous position would be cheaper, but a full
/// scan is a few thousand floating-point operations once a second — nothing on
/// a phone — and it handles the cases a window gets wrong: a U-turn, a route
/// that doubles back on itself, or a GPS fix that jumps after a tunnel.
public enum RouteTracker {

    /// Beyond this from the line, treat it as a wrong turn rather than GPS noise.
    public static let offRouteThresholdM = 50.0

    private static let metresPerDegree = 111_194.926

    public static func progress(
        geometry: [RoutePoint],
        steps: [RouteStep],
        lat: Double,
        lon: Double,
        totalDurationS: Double = 0
    ) -> RouteProgress? {
        guard geometry.count >= 2 else { return nil }

        // Cumulative distance to each vertex, measured the same way the route's
        // own distances are: great-circle between consecutive points.
        var cumulative = [Double](repeating: 0, count: geometry.count)
        for i in 1..<geometry.count {
            let a = geometry[i - 1]
            let b = geometry[i]
            cumulative[i] = cumulative[i - 1] + Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
        }
        let total = cumulative[geometry.count - 1]

        var bestDistance = Double.greatestFiniteMagnitude
        var bestAlong = 0.0
        var bestLat = geometry[0].lat
        var bestLon = geometry[0].lon

        for i in 0..<(geometry.count - 1) {
            let projection = projectOntoSegment(
                lat: lat, lon: lon, a: geometry[i], b: geometry[i + 1]
            )
            if projection.distanceM < bestDistance {
                bestDistance = projection.distanceM
                bestLat = projection.lat
                bestLon = projection.lon
                bestAlong = cumulative[i] + projection.t * (cumulative[i + 1] - cumulative[i])
            }
        }

        let along = min(max(bestAlong, 0), total)
        let remaining = max(total - along, 0)

        // Walk the step distances to find which one we are inside.
        var stepIndex = 0
        var stepEnd = 0.0
        for (i, step) in steps.enumerated() {
            stepEnd += step.distanceM
            stepIndex = i
            if along < stepEnd { break }
        }
        let toManeuver = max(stepEnd - along, 0)

        return RouteProgress(
            snappedLat: bestLat,
            snappedLon: bestLon,
            distanceAlongM: along,
            distanceRemainingM: remaining,
            // Assume an even pace across the route: good enough for an ETA that
            // is refreshed every time the route is refetched.
            durationRemainingS: total > 0 ? totalDurationS * (remaining / total) : 0,
            offRouteByM: bestDistance,
            isOffRoute: bestDistance > offRouteThresholdM,
            stepIndex: stepIndex,
            distanceToManeuverM: toManeuver,
            currentStep: stepIndex < steps.count ? steps[stepIndex] : nil,
            nextStep: stepIndex + 1 < steps.count ? steps[stepIndex + 1] : nil
        )
    }

    /// Where a point sits relative to a polyline.
    public struct OnLine: Sendable {
        /// Distance from the start of the line to the projected point.
        public let alongM: Double
        /// Perpendicular distance from the line.
        public let offM: Double
    }

    /// Project any point onto a polyline. Used by the alert engine to ask how
    /// far ahead a threat is *along the road*, which is the only measure that
    /// stays right around a bend.
    public static func locate(
        geometry: [RoutePoint], lat: Double, lon: Double
    ) -> OnLine? {
        guard geometry.count >= 2 else { return nil }

        var cumulative = 0.0
        var bestOff = Double.greatestFiniteMagnitude
        var bestAlong = 0.0

        for i in 0..<(geometry.count - 1) {
            let a = geometry[i]
            let b = geometry[i + 1]
            let segment = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
            let projection = projectOntoSegment(lat: lat, lon: lon, a: a, b: b)

            if projection.distanceM < bestOff {
                bestOff = projection.distanceM
                bestAlong = cumulative + projection.t * segment
            }
            cumulative += segment
        }
        return OnLine(alongM: bestAlong, offM: bestOff)
    }

    /// The next `lengthM` of route starting from the car's position on it.
    ///
    /// The first point is the car snapped onto the line, so distances measured
    /// against the result are distances from the car, and anything with a
    /// positive along-value is genuinely in front.
    public static func aheadSlice(
        geometry: [RoutePoint], lat: Double, lon: Double, lengthM: Double = 6_000
    ) -> [RoutePoint] {
        guard geometry.count >= 2 else { return [] }

        var bestOff = Double.greatestFiniteMagnitude
        var bestIndex = 0
        var bestPoint = geometry[0]

        for i in 0..<(geometry.count - 1) {
            let projection = projectOntoSegment(
                lat: lat, lon: lon, a: geometry[i], b: geometry[i + 1]
            )
            if projection.distanceM < bestOff {
                bestOff = projection.distanceM
                bestIndex = i
                bestPoint = RoutePoint(lat: projection.lat, lon: projection.lon)
            }
        }

        var slice: [RoutePoint] = [bestPoint]
        var travelled = 0.0
        var previous = bestPoint

        for i in (bestIndex + 1)..<geometry.count {
            let point = geometry[i]
            travelled += Geo.distanceM(previous.lat, previous.lon, point.lat, point.lon)
            slice.append(point)
            previous = point
            if travelled >= lengthM { break }
        }
        return slice.count >= 2 ? slice : []
    }

    private struct Projection {
        let lat: Double
        let lon: Double
        let t: Double
        let distanceM: Double
    }

    /// Projects a point onto one segment using a local flat-earth approximation.
    ///
    /// Over a segment of a few hundred metres the curvature error is far below
    /// GPS accuracy, and it turns the projection into simple two-dimensional
    /// vector arithmetic instead of spherical trigonometry.
    private static func projectOntoSegment(
        lat: Double, lon: Double, a: RoutePoint, b: RoutePoint
    ) -> Projection {
        let cosLat = cos(a.lat * .pi / 180)

        let bx = (b.lon - a.lon) * metresPerDegree * cosLat
        let by = (b.lat - a.lat) * metresPerDegree
        let px = (lon - a.lon) * metresPerDegree * cosLat
        let py = (lat - a.lat) * metresPerDegree

        let lengthSquared = bx * bx + by * by

        // A zero-length segment (duplicate vertices) degenerates to its endpoint.
        let t: Double
        if lengthSquared < 1e-9 {
            t = 0
        } else {
            t = min(max((px * bx + py * by) / lengthSquared, 0), 1)
        }

        let closestX = t * bx
        let closestY = t * by
        let distance = sqrt((px - closestX) * (px - closestX) + (py - closestY) * (py - closestY))

        return Projection(
            lat: a.lat + (closestY / metresPerDegree),
            lon: a.lon + (closestX / (metresPerDegree * cosLat)),
            t: t,
            distanceM: distance
        )
    }

    /// Which of these threats sit on this route.
    ///
    /// This is what lets the route picker say something Google Maps will not:
    /// "four minutes longer, two fewer cameras". Comparing alternatives on
    /// duration alone throws away the one axis this app knows about.
    ///
    /// A bounding-box pass runs first, because the country-wide camera bundle is
    /// thousands of points and all but a handful are nowhere near any given
    /// route. Only the survivors are projected onto the line.
    public static func threatsOn(
        geometry: [RoutePoint],
        threats: [Threat],
        corridorM: Double = AlertEngine.routeCorridorM
    ) -> [Threat] {
        guard geometry.count >= 2, !threats.isEmpty else { return [] }

        var minLat = Double.greatestFiniteMagnitude
        var maxLat = -Double.greatestFiniteMagnitude
        var minLon = Double.greatestFiniteMagnitude
        var maxLon = -Double.greatestFiniteMagnitude
        for point in geometry {
            minLat = min(minLat, point.lat)
            maxLat = max(maxLat, point.lat)
            minLon = min(minLon, point.lon)
            maxLon = max(maxLon, point.lon)
        }

        // Pad the box by the corridor so a threat just outside the extreme
        // vertices is not discarded before it can be measured properly.
        let padLat = corridorM / 111_194.926
        let padLon = padLat / max(cos((minLat + maxLat) / 2 * .pi / 180), 0.01)

        return threats.filter { threat in
            threat.lat >= minLat - padLat && threat.lat <= maxLat + padLat
                && threat.lon >= minLon - padLon && threat.lon <= maxLon + padLon
        }.filter { threat in
            guard let found = locate(geometry: geometry, lat: threat.lat, lon: threat.lon)
            else { return false }
            return found.offM <= corridorM
        }
    }

    /// A one-line summary of what a route will put in front of you.
    public static func describeThreats(_ threats: [Threat]) -> String? {
        let cameras = threats.filter(\.isCamera).count
        let hazards = threats.count - cameras

        var parts: [String] = []
        if cameras > 0 { parts.append(cameras == 1 ? "1 camera" : "\(cameras) cameras") }
        if hazards > 0 { parts.append(hazards == 1 ? "1 hazard" : "\(hazards) hazards") }
        return parts.isEmpty ? nil : parts.joined(separator: ", ")
    }

    /// "In 400 metres, turn left" — the phrasing a person expects to hear.
    public static func maneuverPrompt(_ progress: RouteProgress) -> String? {
        guard let step = progress.currentStep else { return nil }
        let instruction = step.instruction
        guard !instruction.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }

        if progress.distanceToManeuverM < 30 { return instruction }
        let lowered = instruction.prefix(1).lowercased() + instruction.dropFirst()
        return "In \(AlertEngine.spokenDistance(progress.distanceToManeuverM)), \(lowered)"
    }

    /// Formats a remaining duration the way an ETA strip reads.
    public static func formatDuration(_ seconds: Double) -> String {
        let totalMinutes = Int(seconds / 60)
        if totalMinutes < 60 { return "\(totalMinutes) min" }
        let hours = totalMinutes / 60
        let minutes = totalMinutes % 60
        return minutes == 0 ? "\(hours) hr" : "\(hours) hr \(minutes) min"
    }

    /// Formats a remaining distance for the ETA strip.
    public static func formatDistance(_ metres: Double) -> String {
        if metres < 1_000 { return "\(Int(metres / 100) * 100) m" }
        let km = metres / 1_000
        return km < 10 ? String(format: "%.1f km", km) : "\(Int(km)) km"
    }
}
