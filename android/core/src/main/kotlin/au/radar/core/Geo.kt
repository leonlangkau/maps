package au.radar.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical geometry, kept deliberately small. The same three functions exist in
 * RadarKit on iOS; both are checked against shared/alert-engine-fixtures.json.
 */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    private fun Double.toRadians() = this * Math.PI / 180.0

    private fun Double.toDegrees() = this * 180.0 / Math.PI

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /** Initial bearing from the first point to the second, degrees clockwise from north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = (lon2 - lon1).toRadians()
        val y = sin(dLon) * cos(lat2.toRadians())
        val x = cos(lat1.toRadians()) * sin(lat2.toRadians()) -
            sin(lat1.toRadians()) * cos(lat2.toRadians()) * cos(dLon)
        return (atan2(y, x).toDegrees() + 360.0) % 360.0
    }

    /** The point a given distance along a bearing from a start point. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val angular = distanceM / EARTH_RADIUS_M
        val theta = bearingDeg.toRadians()
        val lat1 = lat.toRadians()
        val lon1 = lon.toRadians()
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(theta))
        val lon2 = lon1 + atan2(
            sin(theta) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2),
        )
        return lat2.toDegrees() to lon2.toDegrees()
    }

    /** Smallest angle between two bearings, 0..180, wrapping across north. */
    fun bearingDelta(a: Double, b: Double): Double = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)
}
