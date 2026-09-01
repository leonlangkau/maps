import Combine
import Foundation
import RadarKit

/// One route the router offered, with the thing this app knows that a general
/// navigation app does not: what you will drive past on it.
struct RouteChoice: Identifiable {
    let id: Int
    let option: RouteOption
    let geometry: [RoutePoint]
    let threatSummary: String?
}

/// Which of the app's four states the driver is in.
enum NavMode {
    case idle
    case searching
    case previewing
    case navigating
}

/// Wires location to the engine to the voice, and keeps the map fed.
@MainActor
final class DriveModel: ObservableObject {
    @Published private(set) var hazards: [Threat] = []
    @Published private(set) var cameras: [Threat] = []
    @Published private(set) var lastAnnouncement: Announcement?
    @Published private(set) var postedLimit: Int?
    @Published private(set) var connected = true
    @Published private(set) var speedKmh: Double = 0
    @Published private(set) var settings = SettingsStore.load()
    /// Bumped every time a warning wants the screen pulsed. The view watches the
    /// value rather than a boolean so two flashes in a row both land.
    @Published private(set) var flashAt: Int64 = 0

    var muted: Bool { settings.muted }

    @Published var navMode: NavMode = .idle
    @Published var searchQuery = ""
    @Published private(set) var searchResults: [PlaceResult] = []
    @Published private(set) var searching = false
    @Published private(set) var destination: PlaceResult?
    @Published private(set) var route: RouteOption?
    @Published private(set) var routeGeometry: [RoutePoint] = []
    /// Every alternative the router offered, with what each one drives past.
    @Published private(set) var routeChoices: [RouteChoice] = []
    @Published private(set) var selectedRoute = 0
    @Published private(set) var progress: RouteProgress?
    @Published var selectedThreat: Threat?
    @Published private(set) var toast: String?

    let location = LocationProvider()

    private let api: RadarApi
    private let voice = AlertVoice()
    private let store = CameraStore()
    private var engineState = EngineState()
    private var cancellables = Set<AnyCancellable>()
    private var pollTask: Task<Void, Never>?
    private var searchTask: Task<Void, Never>?
    private var lastFetchCentre: (lat: Double, lon: Double)?
    private var lastCar: LocationProvider.CarStateSnapshot?

    /// The maneuver we last spoke, so we announce each turn once, not every tick.
    private var lastSpokenStep = -1
    private var lastRerouteAt = Date.distantPast

    /// Refetch once the car has moved far enough that the previous window is
    /// running out, rather than on a timer that ignores how fast we are going.
    private let refetchAfterMetres = 4_000.0
    private let pollInterval: UInt64 = 30 * 1_000_000_000
    private let rerouteCooldown: TimeInterval = 20

    var threats: [Threat] { cameras + hazards }

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
        searchTask?.cancel()
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

    // MARK: - The tick

    private func onFix(_ snapshot: LocationProvider.CarStateSnapshot) {
        lastCar = snapshot
        speedKmh = snapshot.speedMps * 3.6

        let car = CarState(
            lat: snapshot.lat,
            lon: snapshot.lon,
            speedMps: snapshot.speedMps,
            headingDeg: snapshot.headingDeg,
            stationaryForMs: snapshot.stationaryForMs
        )

        // Route progress first: a turn instruction is more urgent than a camera
        // three hundred metres further on, and it also feeds the ETA strip.
        if navMode == .navigating, routeGeometry.count >= 2 {
            let steps = route?.legs.flatMap(\.steps) ?? []
            let current = RouteTracker.progress(
                geometry: routeGeometry,
                steps: steps,
                lat: car.lat,
                lon: car.lon,
                totalDurationS: route?.durationS ?? 0
            )
            progress = current

            if let current {
                if current.isOffRoute {
                    maybeReroute(from: car)
                } else {
                    speakManeuverIfDue(current)
                }
            }
        }

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        engineState = AlertEngine.retirePassed(engineState, car: car, threats: threats)

        // Hand the engine the next few kilometres of route rather than the whole
        // polyline. It lets the engine measure distance along the road — the
        // only measure that stays right around a bend — without paying for a
        // cross-country projection every second.
        var routeContext: RouteContext?
        if routeGeometry.count >= 2 {
            let slice = RouteTracker.aheadSlice(
                geometry: routeGeometry, lat: car.lat, lon: car.lon
            )
            if slice.count >= 2 { routeContext = RouteContext(aheadGeometry: slice) }
        }

        guard let announcement = AlertEngine.evaluate(
            now: now, car: car, threats: threats, state: engineState,
            settings: settings, route: routeContext
        ) else { return }

        engineState = AlertEngine.record(engineState, announcement: announcement, now: now)
        lastAnnouncement = announcement
        postedLimit = threats
            .first { $0.id == announcement.threatId }
            .flatMap(AlertEngine.postedLimit)

        // Muting silences the voice, not the screen: someone driving with the
        // radio up still wants to see it.
        if announcement.flash { flashAt = now }
        if !settings.muted { voice.announce(announcement) }
    }

    /// Every settings change goes through here, so nothing is saved by accident.
    func update(settings newSettings: AlertSettings) {
        settings = newSettings
        SettingsStore.save(newSettings)
    }

    func toggleMute() {
        var next = settings
        next.muted.toggle()
        update(settings: next)
    }

    /// Speak each turn twice at most: once with warning, once on approach. The
    /// step index guards the first; the distance band guards the second.
    private func speakManeuverIfDue(_ progress: RouteProgress) {
        guard !settings.muted, let instruction = RouteTracker.maneuverPrompt(progress) else { return }

        let far = (200...600).contains(progress.distanceToManeuverM)
        let near = progress.distanceToManeuverM < 60
        guard far || near else { return }

        let key = near ? progress.stepIndex * 2 + 1 : progress.stepIndex * 2
        guard key != lastSpokenStep else { return }
        lastSpokenStep = key
        voice.speakNavigation(instruction)
    }

    private func maybeReroute(from car: CarState) {
        // A wrong turn produces off-route readings for many ticks in a row.
        // Without a cooldown that becomes a routing request every second.
        guard Date().timeIntervalSince(lastRerouteAt) > rerouteCooldown else { return }
        lastRerouteAt = Date()

        guard let destination else { return }
        Task {
            guard let result = try? await api.route(
                fromLat: car.lat, fromLon: car.lon,
                toLat: destination.lat, toLon: destination.lon
            ), let option = result.routes.first else { return }

            lastSpokenStep = -1
            route = option
            routeGeometry = Polyline.decode(option.geometry)
            show(toast: "Rerouting")
            if !settings.muted { voice.speakNavigation("Rerouting") }
        }
    }

    // MARK: - Hazards

    private func refreshHazardsIfNeeded() async {
        guard let snapshot = location.car else { return }

        if let previous = lastFetchCentre {
            let moved = Geo.distanceM(previous.lat, previous.lon, snapshot.lat, snapshot.lon)
            if moved < refetchAfterMetres / 2, !hazards.isEmpty { return }
        }

        // Roughly a 25 km box, which at highway speed is about fifteen minutes
        // of driving — comfortably more than one poll interval.
        let pad = 0.11
        do {
            let collection = try await api.alerts(
                minLon: snapshot.lon - pad, minLat: snapshot.lat - pad,
                maxLon: snapshot.lon + pad, maxLat: snapshot.lat + pad
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

    // MARK: - Search and routing

    func openSearch() {
        searchQuery = ""
        searchResults = []
        navMode = .searching
    }

    func closeSearch() {
        searchTask?.cancel()
        searching = false
        searchResults = []
        navMode = route != nil ? .navigating : .idle
    }

    func onSearchQueryChanged(_ query: String) {
        searchQuery = query
        searchTask?.cancel()

        guard query.trimmingCharacters(in: .whitespaces).count >= 3 else {
            searchResults = []
            searching = false
            return
        }

        searchTask = Task { [weak self] in
            // Debounce: a geocoding request per keystroke is both slow and a
            // waste of the free tier.
            try? await Task.sleep(nanoseconds: 320_000_000)
            guard let self, !Task.isCancelled else { return }

            self.searching = true
            do {
                let places = try await self.api.search(
                    query: query.trimmingCharacters(in: .whitespaces),
                    nearLat: self.lastCar?.lat,
                    nearLon: self.lastCar?.lon
                )
                guard !Task.isCancelled else { return }
                self.searchResults = places
            } catch {
                self.searchResults = []
                self.show(toast: "Search unavailable")
            }
            self.searching = false
        }
    }

    func pickDestination(_ place: PlaceResult) {
        guard let car = lastCar else {
            show(toast: "Waiting for a GPS fix")
            return
        }

        destination = place
        searchResults = []
        navMode = .previewing

        Task {
            do {
                let result = try await api.route(
                    fromLat: car.lat, fromLon: car.lon,
                    toLat: place.lat, toLon: place.lon
                )
                guard let option = result.routes.first else {
                    navMode = .idle
                    show(toast: "No route found")
                    return
                }
                let choices = result.routes.enumerated().map { index, candidate -> RouteChoice in
                    let geometry = Polyline.decode(candidate.geometry)
                    return RouteChoice(
                        id: index,
                        option: candidate,
                        geometry: geometry,
                        threatSummary: RouteTracker.describeThreats(
                            RouteTracker.threatsOn(geometry: geometry, threats: cameras)
                        )
                    )
                }
                routeChoices = choices
                selectedRoute = 0
                route = option
                routeGeometry = choices.first?.geometry ?? []
            } catch {
                navMode = .idle
                show(toast: "Could not build a route")
            }
        }
    }

    /// Swap to one of the alternatives before setting off.
    func selectRoute(_ index: Int) {
        guard index < routeChoices.count else { return }
        selectedRoute = index
        route = routeChoices[index].option
        routeGeometry = routeChoices[index].geometry
    }

    func startNavigation() {
        guard route != nil else { return }
        lastSpokenStep = -1
        navMode = .navigating
    }

    func endNavigation() {
        lastSpokenStep = -1
        navMode = .idle
        route = nil
        routeGeometry = []
        routeChoices = []
        selectedRoute = 0
        progress = nil
        destination = nil
    }

    // MARK: - Reports

    func report(kind: String) async {
        guard let snapshot = lastCar else { return }
        do {
            _ = try await api.report(
                ReportRequest(
                    kind: kind, lat: snapshot.lat, lon: snapshot.lon,
                    bearing: snapshot.headingDeg
                )
            )
            // Show it immediately rather than waiting for the next poll: the
            // driver who just tapped it should see it land.
            lastFetchCentre = nil
            await refreshHazardsIfNeeded()
            show(toast: "Thanks — reported")
        } catch {
            connected = false
            show(toast: "Could not report")
        }
    }

    /// Confirm or deny somebody else's report. Only community reports can be voted on.
    func vote(on threat: Threat, confirm: Bool) async {
        let prefix = "community:"
        guard threat.id.hasPrefix(prefix) else { return }
        let reportId = String(threat.id.dropFirst(prefix.count))

        do {
            try await api.vote(reportId: reportId, confirm: confirm)
            selectedThreat = nil
            lastFetchCentre = nil
            await refreshHazardsIfNeeded()
            show(toast: confirm ? "Confirmed" : "Marked as gone")
        } catch {
            show(toast: "Could not send that")
        }
    }

    private func show(toast message: String) {
        toast = message
        Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if toast == message { toast = nil }
        }
    }

    var styleUrl: URL? { URL(string: api.styleUrl(theme: "dark")) }
}
