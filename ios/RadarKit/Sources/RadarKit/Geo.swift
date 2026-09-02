import Foundation

/// Spherical geometry, deliberately small. The same three functions exist in
/// `au.radar.core.Geo` on Android; both are checked against
/// `shared/alert-engine-fixtures.json`.
public enum Geo {
    static let earthRadiusM = 6_371_008.8

    /// Great-circle distance in metres.
    public static func distanceM(
        _ lat1: Double, _ lon1: Double,
        _ lat2: Double, _ lon2: Double
    ) -> Double {
        let dLat = (lat2 - lat1) * .pi / 180
        let dLon = (lon2 - lon1) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadiusM * asin(min(1, sqrt(a)))
    }

    /// Initial bearing from the first point to the second, degrees clockwise from north.
    public static func bearingDeg(
        _ lat1: Double, _ lon1: Double,
        _ lat2: Double, _ lon2: Double
    ) -> Double {
        let dLon = (lon2 - lon1) * .pi / 180
        let y = sin(dLon) * cos(lat2 * .pi / 180)
        let x = cos(lat1 * .pi / 180) * sin(lat2 * .pi / 180)
            - sin(lat1 * .pi / 180) * cos(lat2 * .pi / 180) * cos(dLon)
        return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    /// The point a given distance along a bearing from a start point.
    public static func destination(
        lat: Double, lon: Double, bearingDeg: Double, distanceM: Double
    ) -> (lat: Double, lon: Double) {
        let angular = distanceM / earthRadiusM
        let theta = bearingDeg * .pi / 180
        let lat1 = lat * .pi / 180
        let lon1 = lon * .pi / 180
        let lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(theta))
        let lon2 = lon1 + atan2(
            sin(theta) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2)
        )
        return (lat2 * 180 / .pi, lon2 * 180 / .pi)
    }

    /// Smallest angle between two bearings, 0...180, wrapping across north.
    public static func bearingDelta(_ a: Double, _ b: Double) -> Double {
        abs(((a - b).truncatingRemainder(dividingBy: 360) + 540)
            .truncatingRemainder(dividingBy: 360) - 180)
    }
}
