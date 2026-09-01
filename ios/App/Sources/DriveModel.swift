import Combine
import Foundation
import RadarKit

/// Wires location to the engine to the voice, and keeps the map fed.
@MainActor
final class DriveModel: ObservableObject {
    @Published private(set) var hazards: [Threat] = []
    @Published private(set) var cameras: [Threat] = []
    @Published private(set) var lastAnnouncement: Announcement?
    @Published private(set) var connected = true
    @Published private(set) var speedKmh: Double = 0
    @Published var muted = false

    let location = LocationProvider()

    private let api: RadarApi
    private let voice = AlertVoice()
    private let store = CameraStore()
    private var engineState = EngineState()
    private var cancellables = Set<AnyCancellable>()
    private var pollTask: Task<Void, Never>?
    private var lastFetchCentre: (lat: Double, lon: Double)?

    /// Refetch once the car has moved far enough that the previous window is
    /// running out, rather than on a timer that ignores how fast we are going.
    private let refetchAfterMetres = 4_000.0
    private let pollInterval: UInt64 = 30 * 1_000_000_000

    init() {
        api = RadarApi(
            baseUrl: AppConfig.baseUrl,
            appToken: AppConfig.appToken,
            deviceId: AppConfig.deviceId
        )

        location.$car
            .compactMap { $0 }
            .sink { [weak self] snapshot in self?.onFix(snapshot) }
            .store(in: &cancellables)
    }

    func start() {
        location.start()
        Task {
            await store.loadFromDisk()
            cameras = await store.cameras
            await store.syncIfNeeded(api: api)
            cameras = await store.cameras
        }
        startPolling()
    }

    func stop() {
        pollTask?.cancel()
        location.stop()
        voice.release()
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refreshHazardsIfNeeded()
                try? await Task.sleep(nanoseconds: self?.pollInterval ?? 30_000_000_000)
            }
        }
    }

    private func onFix(_ snapshot: LocationProvider.CarStateSnapshot) {
        speedKmh = snapshot.speedMps * 3.6

        let car = CarState(
            lat: snapshot.lat,
            lon: snapshot.lon,
            speedMps: snapshot.speedMps,
            headingDeg: snapshot.headingDeg,
            stationaryForMs: snapshot.stationaryForMs
        )

        let threats = cameras + hazards
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        engineState = AlertEngine.retirePassed(engineState, car: car, threats: threats)

        guard let announcement = AlertEngine.evaluate(
            now: now, car: car, threats: threats, state: engineState
        ) else { return }

        engineState = AlertEngine.record(engineState, announcement: announcement, now: now)
        lastAnnouncement = announcement
        if !muted { voice.announce(announcement) }
    }

    private func refreshHazardsIfNeeded() async {
        guard let snapshot = location.car else { return }

        if let previous = lastFetchCentre {
            let moved = Geo.distanceM(previous.lat, previous.lon, snapshot.lat, snapshot.lon)
            // Still well inside the window we already hold: nothing to do.
            if moved < refetchAfterMetres / 2, !hazards.isEmpty { return }
        }

        // Roughly a 25 km box, which at highway speed is about fifteen minutes
        // of driving — comfortably more than one poll interval.
        let pad = 0.11
        do {
            let collection = try await api.alerts(
                minLon: snapshot.lon - pad,
                minLat: snapshot.lat - pad,
                maxLon: snapshot.lon + pad,
                maxLat: snapshot.lat + pad
            )
            hazards = ThreatMapper.fromAlerts(collection)
            lastFetchCentre = (snapshot.lat, snapshot.lon)
            connected = true
        } catch {
            // Keep the hazards we already have. They carry their own expiry, so
            // an outage degrades to cameras-only rather than to nothing.
            connected = false
        }
    }

    // MARK: - Reporting

    func report(kind: String) async {
        guard let snapshot = location.car else { return }
        let request = ReportRequest(
            kind: kind,
            lat: snapshot.lat,
            lon: snapshot.lon,
            bearing: snapshot.headingDeg
        )
        do {
            _ = try await api.report(request)
            // Show it immediately rather than waiting for the next poll: the
            // driver who just tapped it should see it land.
            await refreshHazardsIfNeeded()
        } catch {
            connected = false
        }
    }

    var styleUrl: URL? { URL(string: api.styleUrl(theme: "dark")) }
}
