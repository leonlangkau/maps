package au.radar.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private class FakeTransport(
    private val replies: Map<String, HttpReply> = emptyMap(),
    private val default: HttpReply = HttpReply(200, "{}"),
) : HttpTransport {
    val requests = mutableListOf<Triple<String, String, String?>>()
    var lastHeaders: Map<String, String> = emptyMap()

    private fun reply(url: String): HttpReply =
        replies.entries.firstOrNull { url.contains(it.key) }?.value ?: default

    override suspend fun get(url: String, headers: Map<String, String>): HttpReply {
        requests += Triple("GET", url, null)
        lastHeaders = headers
        return reply(url)
    }

    override suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpReply {
        requests += Triple(method, url, body)
        lastHeaders = headers
        return reply(url)
    }
}

class RadarApiTest {

    private fun api(transport: HttpTransport) =
        RadarApi("https://radar.example.com/", "secret-token", "device-abc", transport)

    @Test
    fun `alerts are requested with the bbox in lon lat order`() = runBlocking {
        val transport = FakeTransport(
            default = HttpReply(200, """{"type":"FeatureCollection","features":[],"generatedAt":1}"""),
        )
        api(transport).alerts(151.0, -34.0, 151.3, -33.7)

        val url = transport.requests.single().second
        assertContains(url, "/v1/alerts?bbox=151.0,-34.0,151.3,-33.7")
    }

    @Test
    fun `a delta request carries the since cursor`() = runBlocking {
        val transport = FakeTransport(
            default = HttpReply(200, """{"type":"FeatureCollection","features":[]}"""),
        )
        api(transport).alerts(151.0, -34.0, 151.3, -33.7, since = 1_700_000_000_000)
        assertContains(transport.requests.single().second, "&since=1700000000000")
    }

    @Test
    fun `every request carries the app token and the device id`() = runBlocking {
        val transport = FakeTransport(default = HttpReply(200, """{"version":1,"url":"/x"}"""))
        api(transport).cameraBundleVersion()

        assertEquals("Bearer secret-token", transport.lastHeaders["authorization"])
        assertEquals("device-abc", transport.lastHeaders["x-device-id"])
    }

    @Test
    fun `a trailing slash on the base url does not double up`() = runBlocking {
        val transport = FakeTransport(default = HttpReply(200, """{"version":1,"url":"/x"}"""))
        api(transport).cameraBundleVersion()
        assertContains(transport.requests.single().second, "https://radar.example.com/v1/cameras/version")
        assertTrue(!transport.requests.single().second.contains("//v1"))
    }

    @Test
    fun `an error status becomes an ApiException carrying the code`() {
        val transport = FakeTransport(default = HttpReply(401, "unauthorised"))
        val error = assertFailsWith<ApiException> {
            runBlocking { api(transport).cameraBundleVersion() }
        }
        assertEquals(401, error.status)
    }

    @Test
    fun `a duplicate vote is swallowed rather than thrown`() = runBlocking {
        // The server returns 409 when this device already voted. A driver who
        // taps twice should not see an error for it.
        val transport = FakeTransport(default = HttpReply(409, "already voted"))
        api(transport).vote("report-1", confirm = true)
        assertEquals("POST", transport.requests.single().first)
    }

    @Test
    fun `search encodes the query safely`() = runBlocking {
        val transport = FakeTransport(default = HttpReply(200, """{"places":[]}"""))
        api(transport).search("King St & George St", -33.87, 151.21)

        val url = transport.requests.single().second
        assertContains(url, "q=King%20St%20%26%20George%20St")
        assertContains(url, "near=151.21,-33.87")
    }

    @Test
    fun `a report posts the body as JSON`() = runBlocking {
        val transport = FakeTransport(default = HttpReply(201, """{"id":"r1"}"""))
        val id = api(transport).report(
            ReportRequest(kind = "police", lat = -33.87, lon = 151.21, bearing = 90.0),
        )

        assertEquals("r1", id)
        val (method, _, body) = transport.requests.single()
        assertEquals("POST", method)
        assertContains(body ?: "", "\"kind\":\"police\"")
        assertEquals("application/json", transport.lastHeaders["content-type"])
    }
}

class ThreatMapperTest {

    @Test
    fun `alert features become threats with lon lat unswapped`() {
        val collection = GeoJsonFeatureCollection(
            features = listOf(
                GeoJsonFeature(
                    geometry = GeoJsonGeometry("Point", listOf(151.2093, -33.8688)),
                    properties = ApiAlert(
                        id = "nsw:incident:1",
                        source = "nsw",
                        kind = "crash",
                        headline = "Crash",
                        severity = 3,
                        confidence = 1.0,
                        bearing = 90.0,
                    ),
                ),
            ),
        )

        val threat = ThreatMapper.fromAlerts(collection).single()
        assertEquals(-33.8688, threat.lat, 1e-9)
        assertEquals(151.2093, threat.lon, 1e-9)
        assertEquals(3, threat.severity)
        assertEquals(90.0, threat.bearingDeg)
        assertTrue(!threat.isCamera)
    }

    @Test
    fun `a feature with no usable coordinates is dropped, not crashed on`() {
        val collection = GeoJsonFeatureCollection(
            features = listOf(
                GeoJsonFeature(
                    geometry = GeoJsonGeometry("Point", listOf(151.2093)),
                    properties = ApiAlert("x", "nsw", "crash", "Crash"),
                ),
            ),
        )
        assertTrue(ThreatMapper.fromAlerts(collection).isEmpty())
    }

    @Test
    fun `cameras map across with their posted limit intact`() {
        val threats = ThreatMapper.fromCameras(
            listOf(
                ApiCamera(
                    id = "qld-mobile:1",
                    source = "qld-mobile",
                    kind = "mobile_zone",
                    lat = -27.47,
                    lon = 153.02,
                    state = "QLD",
                    speedLimit = 60,
                ),
            ),
        )
        val threat = threats.single()
        assertTrue(threat.isCamera)
        assertEquals(60, threat.speedLimit)
        assertEquals(2, threat.severity)
    }
}
