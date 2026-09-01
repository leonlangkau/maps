package au.radar.core

import kotlin.math.abs

/** One speed reading as the platform handed it over. */
data class SpeedSample(
    val rawMps: Double,
    /**
     * The platform's own estimate of how wrong it might be, or null when it
     * does not say. Negative means the platform is flagging the reading as
     * invalid, which both iOS and Android do.
     */
    val accuracyMps: Double?,
    val timestampMs: Long,
)

/** What the app should display, and whether it is worth believing. */
data class SpeedReading(
    val mps: Double,
    /** False when we are coasting on a stale fix rather than a fresh one. */
    val trusted: Boolean,
) {
    val kmh: Double get() = mps * 3.6

    /** The number on the dial. Rounded once, here, so the two apps agree. */
    val displayKmh: Int get() = kotlin.math.round(kmh).toInt()
}

/**
 * Turns raw GNSS speed into something steady enough to put in large digits.
 *
 * The raw value is already good — phones derive it from Doppler shift on the
 * carrier signal, not by differencing positions, which is why it is typically
 * accurate to a few tenths of a metre per second and often beats the car's own
 * speedometer. What it is not is *steady*: it jitters by a few tenths from
 * fix to fix, and a display that flickers between 98 and 101 reads as broken
 * even when it is more correct than the dashboard.
 *
 * So the filtering has one job and one constraint. Smooth the jitter, and never
 * lag real acceleration — a readout that takes three seconds to notice you
 * braked is worse than one that wobbles.
 */
class SpeedFilter {

    /** Past this, the platform is guessing. Hold the last good value instead. */
    var maxAccuracyMps: Double = 2.0

    /** How long to keep showing the last good value before giving up on it. */
    var holdMs: Long = 3_000

    /**
     * Below this, report a flat zero. GNSS noise keeps a parked car reading
     * one or two km/h, and a speedometer that will not sit still at a red light
     * looks broken.
     */
    var restThresholdMps: Double = 0.6

    /** A change bigger than this is real acceleration, not noise. */
    var fastChangeMps: Double = 1.5

    /** Time constants, in seconds: quick when the change is real, slow otherwise. */
    var tauFastS: Double = 0.15
    var tauSlowS: Double = 1.2

    /**
     * What a car can actually do, which is the honest way to reject a bad fix.
     *
     * Smoothing alone cannot tell a hard stop from a GPS glitch: tuned fast
     * enough to follow real braking, it follows a spike just as faithfully.
     * Physics can tell them apart. A road car manages roughly 4.5 m/s² under
     * power and about 9 m/s² braking hard on dry tarmac, so anything outside
     * that in the time since the last fix did not happen.
     */
    var maxAccelMps2: Double = 4.5
    var maxDecelMps2: Double = 9.0

    /** A gap longer than this is a tunnel, not a sample. Start fresh after it. */
    var gapS: Double = 5.0

    private var smoothed: Double? = null
    private var lastTimestampMs: Long = 0
    private var lastGoodMs: Long = 0

    fun reset() {
        smoothed = null
        lastTimestampMs = 0
        lastGoodMs = 0
    }

    fun update(sample: SpeedSample): SpeedReading {
        val accuracy = sample.accuracyMps
        val unusable = sample.rawMps < 0 ||
            (accuracy != null && (accuracy < 0 || accuracy > maxAccuracyMps))

        if (unusable) {
            val held = smoothed
            // Coast on the last good value briefly — a single bad fix in a
            // built-up area should not blank the dial — then admit we do not
            // know rather than showing a stale number indefinitely.
            return if (held != null && sample.timestampMs - lastGoodMs <= holdMs) {
                SpeedReading(held, trusted = false)
            } else {
                SpeedReading(0.0, trusted = false)
            }
        }

        val raw = if (sample.rawMps < restThresholdMps) 0.0 else sample.rawMps
        val previous = smoothed
        val dtS = if (lastTimestampMs == 0L) {
            0.0
        } else {
            (sample.timestampMs - lastTimestampMs) / 1000.0
        }

        val next = when {
            // First reading, a jump backwards in time, or a long gap: there is
            // nothing meaningful to smooth against.
            previous == null || dtS <= 0 || dtS > gapS -> raw

            else -> {
                val plausible = raw.coerceIn(
                    previous - maxDecelMps2 * dtS,
                    previous + maxAccelMps2 * dtS,
                )
                val tau = if (abs(plausible - previous) > fastChangeMps) tauFastS else tauSlowS
                val alpha = (dtS / (dtS + tau)).coerceIn(0.0, 1.0)
                previous + alpha * (plausible - previous)
            }
        }

        smoothed = next
        lastTimestampMs = sample.timestampMs
        lastGoodMs = sample.timestampMs
        return SpeedReading(next.coerceAtLeast(0.0), trusted = true)
    }
}
