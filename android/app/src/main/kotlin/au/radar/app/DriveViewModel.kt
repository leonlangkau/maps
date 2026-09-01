package au.radar.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.radar.core.AlertEngine
import au.radar.core.Announcement
import au.radar.core.CarState
import au.radar.core.EngineState
import au.radar.core.Geo
import au.radar.core.RadarApi
import au.radar.core.ReportRequest
import au.radar.core.Threat
import au.radar.core.ThreatMapper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DriveUiState(
    val cameras: List<Threat> = emptyList(),
    val hazards: List<Threat> = emptyList(),
    val lastAnnouncement: Announcement? = null,
    val speedKmh: Double = 0.0,
    val connected: Boolean = true,
    val muted: Boolean = false,
) {
    val threats: List<Threat> get() = cameras + hazards
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
    private val voice = AlertVoice(application)
    private val locations = LocationSource(application)

    private val _state = MutableStateFlow(DriveUiState())
    val state: StateFlow<DriveUiState> = _state.asStateFlow()

    private var engineState = EngineState()
    private var pollJob: Job? = null
    private var locationJob: Job? = null
    private var lastFetchCentre: Pair<Double, Double>? = null

    /**
     * Refetch once the car has moved far enough that the previous window is
     * running out, rather than on a timer that ignores how fast we are going.
     */
    private val refetchAfterMetres = 4_000.0
    private val pollIntervalMs = 30_000L

    fun start() {
        if (locationJob != null) return

        viewModelScope.launch {
            store.loadFromDisk()
            _state.value = _state.value.copy(cameras = store.cameras)
            store.syncIfNeeded(api)
            _state.value = _state.value.copy(cameras = store.cameras)
        }

        locationJob = viewModelScope.launch {
            locations.updates().collect { car -> onFix(car) }
        }

        pollJob = viewModelScope.launch {
            while (true) {
                refreshHazardsIfNeeded()
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        locationJob?.cancel()
        locationJob = null
        pollJob?.cancel()
        pollJob = null
    }

    fun toggleMute() {
        _state.value = _state.value.copy(muted = !_state.value.muted)
    }

    private var lastCar: CarState? = null

    private fun onFix(car: CarState) {
        lastCar = car
        val current = _state.value
        val threats = current.threats
        val now = System.currentTimeMillis()

        engineState = AlertEngine.retirePassed(engineState, car, threats)

        val announcement = AlertEngine.evaluate(now, car, threats, engineState)
        if (announcement == null) {
            _state.value = current.copy(speedKmh = car.speedKmh)
            return
        }

        engineState = AlertEngine.record(engineState, announcement, now)
        _state.value = current.copy(speedKmh = car.speedKmh, lastAnnouncement = announcement)
        if (!current.muted) voice.announce(announcement)
    }

    private suspend fun refreshHazardsIfNeeded() {
        val car = lastCar ?: return

        lastFetchCentre?.let { (lat, lon) ->
            val moved = Geo.distanceM(lat, lon, car.lat, car.lon)
            // Still well inside the window we already hold: nothing to do.
            if (moved < refetchAfterMetres / 2 && _state.value.hazards.isNotEmpty()) return
        }

        // Roughly a 25 km box, which at highway speed is about fifteen minutes
        // of driving — comfortably more than one poll interval.
        val pad = 0.11
        runCatching {
            api.alerts(
                minLon = car.lon - pad,
                minLat = car.lat - pad,
                maxLon = car.lon + pad,
                maxLat = car.lat + pad,
            )
        }.onSuccess { collection ->
            _state.value = _state.value.copy(
                hazards = ThreatMapper.fromAlerts(collection),
                connected = true,
            )
            lastFetchCentre = car.lat to car.lon
        }.onFailure {
            // Keep the hazards we already have. They carry their own expiry, so
            // an outage degrades to cameras-only rather than to nothing.
            _state.value = _state.value.copy(connected = false)
        }
    }

    fun report(kind: String) {
        val car = lastCar ?: return
        viewModelScope.launch {
            runCatching {
                api.report(
                    ReportRequest(
                        kind = kind,
                        lat = car.lat,
                        lon = car.lon,
                        bearing = car.headingDeg,
                    ),
                )
                // Show it immediately rather than waiting for the next poll: the
                // driver who just tapped it should see it land.
                lastFetchCentre = null
                refreshHazardsIfNeeded()
            }.onFailure {
                _state.value = _state.value.copy(connected = false)
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
