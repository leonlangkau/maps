package au.radar.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Anything the engine can warn about: a camera from the bundle, or a live hazard. */
@Serializable
data class Threat(
    val id: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    /** The direction of travel this applies to, or null when it applies both ways. */
    val bearingDeg: Double? = null,
    val severity: Int = 1,
    val confidence: Double = 1.0,
    val isCamera: Boolean = false,
    val speedLimit: Int? = null,
)

/** Where the car is right now, as the location provider last reported it. */
@Serializable
data class CarState(
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    /** Null when the fix is too poor or the car is stopped, so heading is meaningless. */
    val headingDeg: Double? = null,
    /** How long the car has been effectively stationary. Zero when moving. */
    val stationaryForMs: Long = 0,
) {
    val speedKmh: Double get() = speedMps * 3.6
}

/** What the engine remembers between ticks. Immutable; [AlertEngine.record] returns a new one. */
@Serializable
data class EngineState(
    val lastAnnouncedAt: Map<String, Long> = emptyMap(),
    val lastAnyAnnounceAt: Long? = null,
    val retired: Set<String> = emptySet(),
)

enum class AnnouncementLevel {
    /** A tone. Low severity, or a report we do not fully believe. */
    CHIME,

    /** Spoken aloud through the car audio. */
    SPEAK,
}

@Serializable
data class Announcement(
    val threatId: String,
    val level: AnnouncementLevel,
    val spokenText: String,
    val distanceM: Double,
)

/** One hazard as the API returns it, before it becomes a [Threat]. */
@Serializable
data class ApiAlert(
    val id: String,
    val source: String,
    val kind: String,
    val headline: String,
    val detail: String? = null,
    val road: String? = null,
    val bearing: Double? = null,
    val severity: Int = 1,
    val startedAt: Long? = null,
    val updatedAt: Long = 0,
    val expiresAt: Long = 0,
    val confidence: Double = 1.0,
)

@Serializable
data class ApiCamera(
    val id: String,
    val source: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val road: String? = null,
    val suburb: String? = null,
    val state: String,
    val speedLimit: Int? = null,
    val bearing: Double? = null,
    val verifiedAt: Long = 0,
)

@Serializable
data class CameraBundle(
    val version: Long,
    val count: Int,
    val cameras: List<ApiCamera>,
)

@Serializable
data class BundleVersion(val version: Long, val url: String)

@Serializable
data class PlaceResult(
    val name: String,
    val address: String? = null,
    val lat: Double,
    val lon: Double,
)

@Serializable
data class RouteStep(
    val instruction: String,
    val distanceM: Double,
    val durationS: Double,
    val modifier: String? = null,
    val name: String? = null,
)

@Serializable
data class RouteLeg(
    val distanceM: Double,
    val durationS: Double,
    val steps: List<RouteStep> = emptyList(),
)

@Serializable
data class RouteOption(
    val distanceM: Double,
    val durationS: Double,
    val geometry: String,
    val legs: List<RouteLeg> = emptyList(),
)

@Serializable
data class RouteResult(
    val provider: String,
    val routes: List<RouteOption> = emptyList(),
)

@Serializable
data class ReportRequest(
    val kind: String,
    val lat: Double,
    val lon: Double,
    val bearing: Double? = null,
    val note: String? = null,
)

@Serializable
data class GeoJsonFeatureCollection(
    val type: String = "FeatureCollection",
    val features: List<GeoJsonFeature> = emptyList(),
    val generatedAt: Long = 0,
)

@Serializable
data class GeoJsonFeature(
    val type: String = "Feature",
    val geometry: GeoJsonGeometry,
    val properties: ApiAlert,
)

@Serializable
data class GeoJsonGeometry(
    val type: String,
    @SerialName("coordinates") val coordinates: List<Double>,
)
