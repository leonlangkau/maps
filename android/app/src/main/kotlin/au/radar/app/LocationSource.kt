package au.radar.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import au.radar.core.CarState
import au.radar.core.SpeedFilter
import au.radar.core.SpeedReading
import au.radar.core.SpeedSample
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A fix, with the steadied speed the dial should show alongside it. */
data class Fix(val car: CarState, val speed: SpeedReading)

/**
 * Turns fused location updates into [CarState].
 *
 * Two things matter here that the platform does not do for you: a bearing is
 * only meaningful once the car is actually moving, and "stopped" has to be
 * measured over time rather than read off a single fix.
 */
class LocationSource(private val context: Context) {

    /** Below this, a GPS bearing is mostly the receiver wandering. */
    private val minimumHeadingSpeedMps = 2.0f

    @SuppressLint("MissingPermission")
    fun updates(): Flow<Fix> = callbackFlow {
        val client = LocationServices.getFusedLocationProviderClient(context)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMinUpdateDistanceMeters(5f)
            .setWaitForAccurateLocation(false)
            .build()

        var stationarySince: Long? = null
        val speedFilter = SpeedFilter()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val fix = result.lastLocation ?: return

                // The dial gets the filtered value; the alert engine gets it
                // too, so a GPS spike cannot briefly stretch every warning range.
                val reading = speedFilter.update(
                    SpeedSample(
                        rawMps = if (fix.hasSpeed()) fix.speed.toDouble() else -1.0,
                        accuracyMps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            fix.hasSpeedAccuracy()
                        ) {
                            fix.speedAccuracyMetersPerSecond.toDouble()
                        } else {
                            null
                        },
                        timestampMs = fix.time,
                    ),
                )
                val speed = reading.mps.toFloat()

                if (speed < 1.0f) {
                    if (stationarySince == null) stationarySince = fix.time
                } else {
                    stationarySince = null
                }

                val stationaryForMs = stationarySince?.let { fix.time - it } ?: 0L

                val heading: Double? =
                    if (speed >= minimumHeadingSpeedMps && fix.hasBearing()) {
                        fix.bearing.toDouble()
                    } else {
                        null
                    }

                trySend(
                    Fix(
                        car = CarState(
                            lat = fix.latitude,
                            lon = fix.longitude,
                            speedMps = reading.mps,
                            headingDeg = heading,
                            stationaryForMs = stationaryForMs,
                        ),
                        speed = reading,
                    ),
                )
            }
        }

        client.requestLocationUpdates(request, callback, context.mainLooper)
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
