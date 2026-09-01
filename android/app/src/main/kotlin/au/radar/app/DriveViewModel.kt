package au.radar.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.radar.core.AlertEngine
import au.radar.core.AlertSettings
import au.radar.core.Announcement
import au.radar.core.CarState
import au.radar.core.EngineState
import au.radar.core.Geo
import au.radar.core.PlaceResult
import au.radar.core.Polyline
import au.radar.core.RadarApi
import au.radar.core.ReportRequest
import au.radar.core.RouteOption
import au.radar.core.RoutePoint
import au.radar.core.RouteContext
import au.radar.core.RouteProgress
import au.radar.core.RouteTracker
import au.radar.core.SpeedReading
import au.radar.core.Threat
import au.radar.core.ThreatMapper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which of the app's four states the driver is in. */
enum class NavMode { IDLE, SEARCHING, PREVIEWING, NAVIGATING }

/**
 * One route the router offered, with the thing this app knows that a general
 * navigation app does not: what you will drive past on it.
 */
data class RouteChoice(
    val option: RouteOption,
    val geometry: List<RoutePoint>,
    val threatSummary: String?,
)

data class DriveUiState(
    val cameras: List<Threat> = emptyList(),
    val hazards: List<Threat> = emptyList(),
    val lastAnnouncement: Announcement? = null,
    val speed: SpeedReading = SpeedReading(0.0, trusted = false),
    val postedLimit: Int? = null,
    val connected: Boolean = true,
    val settings: AlertSettings = AlertSettings(),
    /**
     * Bumped every time a warning wants the screen pulsed. The UI watches the
     * value rather than a boolean so two flashes in a row both land.
     */
    val flashAt: Long = 0,
    val navMode: NavMode = NavMode.IDLE,
    val searchQuery: String = "",
    val searchResults: List<PlaceResult> = emptyList(),
    val searching: Boolean = false,
    val destination: PlaceResult? = null,
    val route: RouteOption? = null,
    val routeGeometry: List<RoutePoint> = emptyList(),
    /** Every alternative the router offered, with what each one drives past. */
    val routeChoices: List<RouteChoice> = emptyList(),
    val selectedRoute: Int = 0,
    val progress: RouteProgress? = null,
    val selectedThreat: Threat? = null,
    val toast: String? = null,
) {
    val threats: List<Threat> get() = cameras + hazards
    val muted: Boolean get() = settings.muted
    val speedKmh: Double get() = speed.kmh
}

/** Wires location to the engine to the voice, and keeps the map fed. */
class DriveViewModel(application: Application) : AndroidViewModel(application) {

    private val api = RadarApi(
        baseUrl = AppConfig.baseUrl,
        appToken = AppConfig.appToken,
        deviceId = AppConfig.deviceId(application),
        transport = OkHttpTransport(),
    )

    private val store = CameraStore(application)
    private val settingsStore = SettingsStore(application)
    private val voice = AlertVoice(application)
    private val locations = LocationSource(application)

    private val _state = MutableStateFlow(DriveUiState())
    val state: StateFlow<DriveUiState> = _state.asStateFlow()

    private var engineState = EngineState()
    private var pollJob: Job? = null
    private var locationJob: Job? = null
    private var searchJob: Job? = null
    private var lastFetchCentre: Pair<Double, Double>? = null
    private var lastCar: CarState? = null

    /** The maneuver we last spoke, so we announce each turn once, not every tick. */
    private var lastSpokenStep = -1
    private var lastRerouteAt = 0L

    private val refetchAfterMetres = 4_000.0
    private val pollIntervalMs = 30_000L
    private val rerouteCooldownMs = 20_000L

    /**
     * How often to re-ask for the route while driving.
     *
     * The ETA only stays honest if traffic is re-read: a route built when the
     * motorway was clear is a lie twenty minutes into a jam. Two minutes is
     * frequent enough to track a queue forming and far inside the routing tier.
     */
    private val trafficRefreshMs = 120_000L
    private var trafficJob: Job? = null

    init {
        _state.value = _state.value.copy(settings = settingsStore.load())
    }

    fun start() {
        if (locationJob != null) return

        viewModelScope.launch {
            store.loadFromDisk()
            _state.value = _state.value.copy(cameras = store.cameras)
            store.syncIfNeeded(api)
            _state.value = _state.value.copy(cameras = store.cameras)
        }

        locationJob = viewModelScope.launch {
            locations.updates().collect { fix -> onFix(fix) }
        }

        pollJob = viewModelScope.launch {
            while (true) {
                refreshHazardsIfNeeded()
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        locationJob?.cancel(); locationJob = null
        pollJob?.cancel(); pollJob = null
        trafficJob?.cancel(); trafficJob = null
    }

    fun toggleMute() {
        updateSettings(_state.value.settings.copy(muted = !_state.value.settings.muted))
    }

    /** Every settings change goes through here, so nothing is saved by accident. */
    fun updateSettings(settings: AlertSettings) {
        _state.value = _state.value.copy(settings = settings)
        settingsStore.save(settings)
    }

    fun dismissToast() {
        _state.value = _state.value.copy(toast = null)
    }

    // MARK: - The tick

    private fun onFix(fix: Fix) {
        val car = fix.car
        lastCar = car
        var next = _state.value.copy(speed = fix.speed)

        // Route progress first: a turn instruction is more urgent than a camera
        // three hundred metres further on, and it also feeds the ETA strip.
        if (next.navMode == NavMode.NAVIGATING && next.routeGeometry.size >= 2) {
            val route = next.route
            val progress = RouteTracker.progress(
                geometry = next.routeGeometry,
                steps = route?.legs?.flatMap { it.steps } ?: emptyList(),
                lat = car.lat,
                lon = car.lon,
                totalDurationS = route?.durationS ?: 0.0,
            )
            next = next.copy(progress = progress)

            if (progress != null) {
                if (progress.isOffRoute) {
                    maybeReroute(car)
                } else {
                    speakManeuverIfDue(progress, next.settings.muted)
                }
            }
        }

        val threats = next.threats
        val now = System.currentTimeMillis()
        engineState = AlertEngine.retirePassed(engineState, car, threats)

        // Hand the engine the next few kilometres of route rather than the whole
        // polyline. It lets the engine measure distance along the road — which
        // is the only measure that stays right around a bend — without paying
        // for a cross-country projection every second.
        val routeContext = if (next.routeGeometry.size >= 2) {
            RouteTracker.aheadSlice(next.routeGeometry, car.lat, car.lon)
                .takeIf { it.size >= 2 }
                ?.let { RouteContext(it) }
        } else {
            null
        }

        val announcement = AlertEngine.evaluate(
            now = now,
            car = car,
            threats = threats,
            state = engineState,
            settings = next.settings,
            route = routeContext,
        )
        if (announcement != null) {
            engineState = AlertEngine.record(engineState, announcement, now)
            next = next.copy(
                lastAnnouncement = announcement,
                postedLimit = threats.firstOrNull { it.id == announcement.threatId }
                    ?.let { AlertEngine.postedLimit(it) },
                // Muting silences the voice, not the screen: someone driving
                // with the radio up still wants to see it.
                flashAt = if (announcement.flash) now else next.flashAt,
            )
            if (!next.settings.muted) voice.announce(announcement)
        }

        _state.value = next
    }

    /**
     * Speak each turn twice at most: once with warning, once on approach. The
     * step index guards the first; the distance band guards the second.
     */
    private fun speakManeuverIfDue(progress: RouteProgress, muted: Boolean) {
        if (muted) return
        val instruction = RouteTracker.maneuverPrompt(progress) ?: return

        val far = progress.distanceToManeuverM in 200.0..600.0
        val near = progress.distanceToManeuverM < 60.0

        val key = if (near) progress.stepIndex * 2 + 1 else progress.stepIndex * 2
        if ((far || near) && key != lastSpokenStep) {
            lastSpokenStep = key
            voice.speakNavigation(instruction)
        }
    }

    private fun maybeReroute(car: CarState) {
        val now = System.currentTimeMillis()
        // A wrong turn produces off-route readings for many ticks in a row.
        // Without a cooldown that becomes a routing request every second.
        if (now - lastRerouteAt < rerouteCooldownMs) return
        lastRerouteAt = now

        val destination = _state.value.destination ?: return
        viewModelScope.launch {
            runCatching { api.route(car.lat, car.lon, destination.lat, destination.lon) }
                .onSuccess { result ->
                    val option = result.routes.firstOrNull() ?: return@onSuccess
                    lastSpokenStep = -1
                    _state.value = _state.value.copy(
                        route = option,
                        routeGeometry = Polyline.decode(option.geometry),
                        toast = "Rerouting",
                    )
                    if (!_state.value.muted) voice.speakNavigation("Rerouting")
                }
        }
    }

    // MARK: - Hazards

    private suspend fun refreshHazardsIfNeeded() {
        val car = lastCar ?: return

        lastFetchCentre?.let { (lat, lon) ->
            val moved = Geo.distanceM(lat, lon, car.lat, car.lon)
            if (moved < refetchAfterMetres / 2 && _state.value.hazards.isNotEmpty()) return
        }

        val pad = 0.11
        runCatching {
            api.alerts(car.lon - pad, car.lat - pad, car.lon + pad, car.lat + pad)
        }.onSuccess { collection ->
            _state.value = _state.value.copy(
                hazards = ThreatMapper.fromAlerts(collection),
                connected = true,
            )
            lastFetchCentre = car.lat to car.lon
        }.onFailure {
            _state.value = _state.value.copy(connected = false)
        }
    }

    // MARK: - Search and routing

    fun openSearch() {
        _state.value = _state.value.copy(navMode = NavMode.SEARCHING, searchQuery = "")
    }

    fun closeSearch() {
        searchJob?.cancel()
        _state.value = _state.value.copy(
            navMode = if (_state.value.route != null) NavMode.NAVIGATING else NavMode.IDLE,
            searchResults = emptyList(),
            searching = false,
        )
    }

    fun onSearchQueryChanged(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        searchJob?.cancel()

        if (query.trim().length < 3) {
            _state.value = _state.value.copy(searchResults = emptyList(), searching = false)
            return
        }

        searchJob = viewModelScope.launch {
            // Debounce: a geocoding request per keystroke is both slow and a
            // waste of the free tier.
            delay(320)
            _state.value = _state.value.copy(searching = true)
            val car = lastCar
            runCatching { api.search(query.trim(), car?.lat, car?.lon) }
                .onSuccess { places ->
                    _state.value = _state.value.copy(searchResults = places, searching = false)
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        searching = false,
                        searchResults = emptyList(),
                        toast = "Search unavailable",
                    )
                }
        }
    }

    fun pickDestination(place: PlaceResult) {
        val car = lastCar ?: run {
            _state.value = _state.value.copy(toast = "Waiting for a GPS fix")
            return
        }

        _state.value = _state.value.copy(
            destination = place,
            navMode = NavMode.PREVIEWING,
            searchResults = emptyList(),
        )

        viewModelScope.launch {
            runCatching { api.route(car.lat, car.lon, place.lat, place.lon) }
                .onSuccess { result ->
                    val option = result.routes.firstOrNull()
                    if (option == null) {
                        _state.value = _state.value.copy(
                            navMode = NavMode.IDLE,
                            toast = "No route found",
                        )
                        return@onSuccess
                    }
                    val cameras = _state.value.cameras
                    val choices = result.routes.map { candidate ->
                        val geometry = Polyline.decode(candidate.geometry)
                        RouteChoice(
                            option = candidate,
                            geometry = geometry,
                            threatSummary = RouteTracker.describeThreats(
                                RouteTracker.threatsOn(geometry, cameras),
                            ),
                        )
                    }
                    _state.value = _state.value.copy(
                        route = option,
                        routeGeometry = choices.firstOrNull()?.geometry ?: emptyList(),
                        routeChoices = choices,
                        selectedRoute = 0,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        navMode = NavMode.IDLE,
                        toast = "Could not build a route",
                    )
                }
        }
    }

    /** Swap to one of the alternatives before setting off. */
    fun selectRoute(index: Int) {
        val choices = _state.value.routeChoices
        val choice = choices.getOrNull(index) ?: return
        _state.value = _state.value.copy(
            selectedRoute = index,
            route = choice.option,
            routeGeometry = choice.geometry,
        )
    }

    fun startNavigation() {
        if (_state.value.route == null) return
        lastSpokenStep = -1
        _state.value = _state.value.copy(navMode = NavMode.NAVIGATING)

        trafficJob?.cancel()
        trafficJob = viewModelScope.launch {
            while (true) {
                delay(trafficRefreshMs)
                refreshRouteForTraffic()
            }
        }
    }

    /**
     * Re-ask for the route so the ETA and the traffic colouring stay current.
     *
     * This is not a reroute — the driver has not gone wrong — so it is silent.
     * The step counter is nudged forward because a fresh route restarts its step
     * numbering, and without that the turn you are already approaching would be
     * announced a second time.
     */
    private suspend fun refreshRouteForTraffic() {
        val car = lastCar ?: return
        val destination = _state.value.destination ?: return
        if (_state.value.navMode != NavMode.NAVIGATING) return

        runCatching { api.route(car.lat, car.lon, destination.lat, destination.lon) }
            .onSuccess { result ->
                val option = result.routes.firstOrNull() ?: return@onSuccess
                lastSpokenStep = 0
                _state.value = _state.value.copy(
                    route = option,
                    routeGeometry = Polyline.decode(option.geometry),
                    connected = true,
                )
            }
            .onFailure {
                // A missed refresh is not worth telling the driver about; the
                // route they have is still the route they are on.
                _state.value = _state.value.copy(connected = false)
            }
    }

    fun endNavigation() {
        trafficJob?.cancel()
        trafficJob = null
        lastSpokenStep = -1
        _state.value = _state.value.copy(
            navMode = NavMode.IDLE,
            route = null,
            routeGeometry = emptyList(),
            routeChoices = emptyList(),
            selectedRoute = 0,
            progress = null,
            destination = null,
        )
    }

    // MARK: - Reports

    fun report(kind: String) {
        val car = lastCar ?: return
        viewModelScope.launch {
            runCatching {
                api.report(ReportRequest(kind, car.lat, car.lon, car.headingDeg))
                lastFetchCentre = null
                refreshHazardsIfNeeded()
            }.onSuccess {
                _state.value = _state.value.copy(toast = "Thanks — reported")
            }.onFailure {
                _state.value = _state.value.copy(connected = false, toast = "Could not report")
            }
        }
    }

    fun selectThreat(threat: Threat?) {
        _state.value = _state.value.copy(selectedThreat = threat)
    }

    /** Confirm or deny somebody else's report. Only community reports can be voted on. */
    fun vote(threat: Threat, confirm: Boolean) {
        val reportId = threat.id.removePrefix("community:")
        if (reportId == threat.id) return

        viewModelScope.launch {
            runCatching { api.vote(reportId, confirm) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        selectedThreat = null,
                        toast = if (confirm) "Confirmed" else "Marked as gone",
                    )
                    lastFetchCentre = null
                    refreshHazardsIfNeeded()
                }
                .onFailure {
                    _state.value = _state.value.copy(toast = "Could not send that")
                }
        }
    }

    fun styleUrl(): String = api.styleUrl("dark")

    override fun onCleared() {
        stop()
        voice.release()
        super.onCleared()
    }
}
