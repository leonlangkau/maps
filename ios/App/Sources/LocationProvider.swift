import CoreLocation
import Foundation

/// Wraps CoreLocation and hands out a clean `CarState`.
///
/// Two things matter here that CoreLocation does not do for you: a heading is
/// only meaningful when the car is actually moving, and "stopped" needs to be
/// measured over time rather than read off a single fix.
final class LocationProvider: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var car: CarStateSnapshot?
    @Published private(set) var authorised = false

    private let manager = CLLocationManager()
    private var stationarySince: Date?

    /// Below this, a GPS course reading is mostly noise from the receiver
    /// wandering rather than the car actually pointing anywhere.
    private let minimumHeadingSpeedMps = 2.0

    struct CarStateSnapshot {
        let lat: Double
        let lon: Double
        let speedMps: Double
        let headingDeg: Double?
        let stationaryForMs: Int64
        let accuracyM: Double
    }

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.activityType = .automotiveNavigation
        manager.distanceFilter = 5
        // Keeps warnings coming with the screen off, which is the normal way to
        // drive with a phone in a cradle.
        manager.pausesLocationUpdatesAutomatically = false
    }

    func start() {
        manager.requestAlwaysAuthorization()
        manager.startUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = true
    }

    func stop() {
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            authorised = true
        default:
            authorised = false
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.last else { return }
        // A negative accuracy means the fix is invalid, not merely poor.
        guard fix.horizontalAccuracy >= 0 else { return }

        let speed = max(0, fix.speed)

        if speed < 1.0 {
            if stationarySince == nil { stationarySince = fix.timestamp }
        } else {
            stationarySince = nil
        }

        let stationaryForMs: Int64
        if let since = stationarySince {
            stationaryForMs = Int64(fix.timestamp.timeIntervalSince(since) * 1000)
        } else {
            stationaryForMs = 0
        }

        // course is -1 when CoreLocation has no confident heading.
        let heading: Double? =
            (speed >= minimumHeadingSpeedMps && fix.course >= 0) ? fix.course : nil

        car = CarStateSnapshot(
            lat: fix.coordinate.latitude,
            lon: fix.coordinate.longitude,
            speedMps: speed,
            headingDeg: heading,
            stationaryForMs: stationaryForMs,
            accuracyM: fix.horizontalAccuracy
        )
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // A transient failure is normal in tunnels and car parks; the next fix
        // recovers on its own, so there is nothing useful to do but wait.
    }
}
