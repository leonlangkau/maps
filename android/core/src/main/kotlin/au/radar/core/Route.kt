package au.radar.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

data class RoutePoint(val lat: Double, val lon: Double)

/**
 * Google's encoded-polyline format, which is what both Mapbox Directions and
 * Valhalla return. Precision 6 is the default here because that is what the
 * Worker asks Mapbox for; precision 5 is the older convention and still turns
 * up in third-party data.
 */
object Polyline {

    fun decode(encoded: String, precision: Int = 6): List<RoutePoint> {
        if (encoded.isEmpty()) return emptyList()

        val factor = Math.pow(10.0, precision.toDouble())
        val points = ArrayList<RoutePoint>(encoded.length / 4)
        var index = 0
        var lat = 0
        var lon = 0

        while (index < encoded.length) {
            val dLat = readVarint(encoded, index) ?: break
            index = dLat.second
            lat += dLat.first

            val dLon = readVarint(encoded, index) ?: break
            index = dLon.second
            lon += dLon.first

            points.add(RoutePoint(lat / factor, lon / factor))
        }
        return points
    }

    /** Returns the decoded delta and the next index, or null on a truncated string. */
    private fun readVarint(encoded: String, start: Int): Pair<Int, Int>? {
        var index = start
        var shift = 0
        var result = 0
        var byte: Int

        do {
            if (index >= encoded.length) return null
            byte = encoded[index++].code - 63
            result = result or ((byte and 0x1f) shl shift)
            shift += 5
        } while (byte >= 0x20)

        // The low bit is the sign, inverted for negatives.
        val delta = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return delta to index
    }
}

/** Where the car is along the route, and whether it is still on it. */
data class RouteProgress(
    /** The position snapped onto the route line. */
    val snappedLat: Double,
    val snappedLon: Double,
    val distanceAlongM: Double,
    val distanceRemainingM: Double,
    val durationRemainingS: Double,
    /** How far the car is from the line. Large values mean a wrong turn. */
    val offRouteByM: Double,
    val isOffRoute: Boolean,
    val stepIndex: Int,
    val distanceToManeuverM: Double,
    val currentStep: RouteStep?,
    val nextStep: RouteStep?,
)

/**
 * Tracks a car against a route.
 *
 * Every tick projects the car onto every segment and takes the nearest. A
 * windowed search around the previous position would be cheaper, but a full
 * scan is a few thousand floating-point operations once a second — nothing on
 * a phone — and it handles the cases a window gets wrong: a U-turn, a route
 * that doubles back on itself, or a GPS fix that jumps after a tunnel.
 */
object RouteTracker {

    /** Beyond this from the line, treat it as a wrong turn rather than GPS noise. */
    const val OFF_ROUTE_THRESHOLD_M = 50.0

    private const val METRES_PER_DEGREE = 111_194.926

    fun progress(
        geometry: List<RoutePoint>,
        steps: List<RouteStep>,
        lat: Double,
        lon: Double,
        totalDurationS: Double = 0.0,
    ): RouteProgress? {
        if (geometry.size < 2) return null

        // Cumulative distance to each vertex, measured the same way the route's
        // own distances are: great-circle between consecutive points.
        val cumulative = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            val a = geometry[i - 1]
            val b = geometry[i]
            cumulative[i] = cumulative[i - 1] + Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
        }
        val total = cumulative[geometry.size - 1]

        var bestDistance = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestLat = geometry[0].lat
        var bestLon = geometry[0].lon

        for (i in 0 until geometry.size - 1) {
            val a = geometry[i]
            val b = geometry[i + 1]
            val projection = projectOntoSegment(lat, lon, a, b)

            if (projection.distanceM < bestDistance) {
                bestDistance = projection.distanceM
                bestLat = projection.lat
                bestLon = projection.lon
                bestAlong = cumulative[i] +
                    projection.t * (cumulative[i + 1] - cumulative[i])
            }
        }

        val along = bestAlong.coerceIn(0.0, total)
        val remaining = (total - along).coerceAtLeast(0.0)

        // Walk the step distances to find which one we are inside.
        var stepIndex = 0
        var stepEnd = 0.0
        for ((i, step) in steps.withIndex()) {
            stepEnd += step.distanceM
            stepIndex = i
            if (along < stepEnd) break
        }
        val toManeuver = (stepEnd - along).coerceAtLeast(0.0)

        return RouteProgress(
            snappedLat = bestLat,
            snappedLon = bestLon,
            distanceAlongM = along,
            distanceRemainingM = remaining,
            // Assume an even pace across the route: good enough for an ETA that
            // is refreshed every time the route is refetched.
            durationRemainingS = if (total > 0) totalDurationS * (remaining / total) else 0.0,
            offRouteByM = bestDistance,
            isOffRoute = bestDistance > OFF_ROUTE_THRESHOLD_M,
            stepIndex = stepIndex,
            distanceToManeuverM = toManeuver,
            currentStep = steps.getOrNull(stepIndex),
            nextStep = steps.getOrNull(stepIndex + 1),
        )
    }

    private data class Projection(val lat: Double, val lon: Double, val t: Double, val distanceM: Double)

    /**
     * Projects a point onto one segment using a local flat-earth approximation.
     *
     * Over a segment of a few hundred metres the curvature error is far below
     * GPS accuracy, and it turns the projection into simple two-dimensional
     * vector arithmetic instead of spherical trigonometry.
     */
    private fun projectOntoSegment(
        lat: Double,
        lon: Double,
        a: RoutePoint,
        b: RoutePoint,
    ): Projection {
        val cosLat = cos(Math.toRadians(a.lat))

        val ax = 0.0
        val ay = 0.0
        val bx = (b.lon - a.lon) * METRES_PER_DEGREE * cosLat
        val by = (b.lat - a.lat) * METRES_PER_DEGREE
        val px = (lon - a.lon) * METRES_PER_DEGREE * cosLat
        val py = (lat - a.lat) * METRES_PER_DEGREE

        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy

        // A zero-length segment (duplicate vertices) degenerates to its endpoint.
        val t = if (lengthSquared < 1e-9) {
            0.0
        } else {
            (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        }

        val closestX = ax + t * dx
        val closestY = ay + t * dy
        val distance = sqrt((px - closestX) * (px - closestX) + (py - closestY) * (py - closestY))

        return Projection(
            lat = a.lat + (closestY / METRES_PER_DEGREE),
            lon = a.lon + (closestX / (METRES_PER_DEGREE * cosLat)),
            t = t,
            distanceM = distance,
        )
    }

    /** "In 400 metres, turn left" — the phrasing a person expects to hear. */
    fun maneuverPrompt(progress: RouteProgress): String? {
        val step = progress.currentStep ?: return null
        val instruction = step.instruction.ifBlank { return null }
        val distance = progress.distanceToManeuverM
        return when {
            distance < 30 -> instruction
            else -> "In ${AlertEngine.spokenDistance(distance)}, ${instruction.replaceFirstChar { it.lowercase() }}"
        }
    }

    /** Formats a remaining duration the way an ETA strip reads. */
    fun formatDuration(seconds: Double): String {
        val totalMinutes = (seconds / 60).toInt()
        if (totalMinutes < 60) return "$totalMinutes min"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0) "$hours hr" else "$hours hr $minutes min"
    }

    /** Formats a remaining distance for the ETA strip. */
    fun formatDistance(metres: Double): String {
        if (metres < 1_000) return "${(metres / 100).toInt() * 100} m"
        val km = metres / 1_000
        return if (km < 10) String.format("%.1f km", km) else "${km.toInt()} km"
    }
}
