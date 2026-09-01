package au.radar.core

import kotlinx.serialization.json.Json

/**
 * The transport is an interface rather than a concrete client because [core] is
 * a plain JVM module: it has to be unit testable without a network, and Android
 * has no java.net.http. The app supplies an OkHttp-backed implementation.
 */
interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): HttpReply

    suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpReply
}

data class HttpReply(val status: Int, val body: String) {
    val isSuccess: Boolean get() = status in 200..299
}

class ApiException(val status: Int, message: String) : Exception(message)

/**
 * Everything the app asks the backend for. The backend already merged the feeds
 * and normalised them, so there is nothing to reconcile here — this is a thin,
 * typed wrapper and deliberately stays that way.
 */
class RadarApi(
    private val baseUrl: String,
    private val appToken: String,
    private val deviceId: String,
    private val transport: HttpTransport,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val base = baseUrl.trimEnd('/')

    private fun headers(withBody: Boolean = false): Map<String, String> = buildMap {
        put("authorization", "Bearer $appToken")
        put("x-device-id", deviceId)
        put("accept", "application/json")
        if (withBody) put("content-type", "application/json")
    }

    private fun require(reply: HttpReply, what: String): String {
        if (!reply.isSuccess) throw ApiException(reply.status, "$what failed: HTTP ${reply.status}")
        return reply.body
    }

    /** Live hazards inside a bounding box, already merged across every source. */
    suspend fun alerts(
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
        since: Long? = null,
    ): GeoJsonFeatureCollection {
        val bbox = "$minLon,$minLat,$maxLon,$maxLat"
        val sinceParam = since?.let { "&since=$it" } ?: ""
        val reply = transport.get("$base/v1/alerts?bbox=$bbox$sinceParam", headers())
        return json.decodeFromString(require(reply, "alerts"))
    }

    /** The camera bundle version, so the app can skip a download it already has. */
    suspend fun cameraBundleVersion(): BundleVersion {
        val reply = transport.get("$base/v1/cameras/version", headers())
        return json.decodeFromString(require(reply, "camera version"))
    }

    /** Every camera in the country. Downloaded once, then kept for offline use. */
    suspend fun cameraBundle(): CameraBundle {
        val reply = transport.get("$base/v1/cameras/bundle", headers())
        return json.decodeFromString(require(reply, "camera bundle"))
    }

    suspend fun search(query: String, nearLat: Double?, nearLon: Double?): List<PlaceResult> {
        val near = if (nearLat != null && nearLon != null) "&near=$nearLon,$nearLat" else ""
        val encoded = query.encodeUrlComponent()
        val reply = transport.get("$base/v1/search?q=$encoded$near", headers())
        val wrapper = json.decodeFromString<SearchResponse>(require(reply, "search"))
        return wrapper.places
    }

    suspend fun route(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): RouteResult {
        val reply = transport.get(
            "$base/v1/route?from=$fromLon,$fromLat&to=$toLon,$toLat",
            headers(),
        )
        return json.decodeFromString(require(reply, "route"))
    }

    suspend fun report(request: ReportRequest): String {
        val reply = transport.send(
            "POST",
            "$base/v1/reports",
            headers(withBody = true),
            json.encodeToString(ReportRequest.serializer(), request),
        )
        val created = json.decodeFromString<CreatedReport>(require(reply, "report"))
        return created.id
    }

    /** Confirm or deny somebody else's report. One vote per device, enforced server side. */
    suspend fun vote(reportId: String, confirm: Boolean) {
        val verb = if (confirm) "confirm" else "deny"
        val reply = transport.send("POST", "$base/v1/reports/$reportId/$verb", headers(), null)
        // A 409 means this device already voted, which is not an error worth
        // surfacing to a driver — the vote is recorded either way.
        if (reply.status == 409) return
        require(reply, "vote")
    }

    suspend fun retract(reportId: String) {
        val reply = transport.send("DELETE", "$base/v1/reports/$reportId", headers(), null)
        require(reply, "retract")
    }

    /** The MapLibre style URL. Public, so it carries no token. */
    fun styleUrl(theme: String = "dark"): String = "$base/v1/style.json?theme=$theme"

    private fun String.encodeUrlComponent(): String = buildString {
        for (byte in this@encodeUrlComponent.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() || c in "-_.~") append(c)
            else append('%').append("%02X".format(byte))
        }
    }
}

@kotlinx.serialization.Serializable
private data class SearchResponse(val places: List<PlaceResult> = emptyList())

@kotlinx.serialization.Serializable
private data class CreatedReport(val id: String)

/** Turn the API's GeoJSON and the camera bundle into what the engine consumes. */
object ThreatMapper {
    fun fromAlerts(collection: GeoJsonFeatureCollection): List<Threat> =
        collection.features.mapNotNull { feature ->
            val coords = feature.geometry.coordinates
            if (coords.size < 2) return@mapNotNull null
            val props = feature.properties
            Threat(
                id = props.id,
                kind = props.kind,
                lat = coords[1],
                lon = coords[0],
                bearingDeg = props.bearing,
                severity = props.severity,
                confidence = props.confidence,
                isCamera = false,
            )
        }

    fun fromCameras(cameras: List<ApiCamera>): List<Threat> =
        cameras.map { camera ->
            Threat(
                id = camera.id,
                kind = camera.kind,
                lat = camera.lat,
                lon = camera.lon,
                bearingDeg = camera.bearing,
                // Cameras are floored at speaking severity by the engine anyway;
                // this keeps the ordering sensible against live hazards.
                severity = 2,
                confidence = 1.0,
                isCamera = true,
                speedLimit = camera.speedLimit,
            )
        }
}
