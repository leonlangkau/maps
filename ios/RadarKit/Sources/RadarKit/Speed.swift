import Foundation

/// One speed reading as the platform handed it over.
public struct SpeedSample: Sendable {
    public let rawMps: Double
    /// The platform's own estimate of how wrong it might be, or nil when it does
    /// not say. Negative means the platform is flagging the reading as invalid,
    /// which both iOS and Android do.
    public let accuracyMps: Double?
    public let timestampMs: Int64

    public init(rawMps: Double, accuracyMps: Double?, timestampMs: Int64) {
        self.rawMps = rawMps
        self.accuracyMps = accuracyMps
        self.timestampMs = timestampMs
    }
}

/// What the app should display, and whether it is worth believing.
public struct SpeedReading: Sendable, Equatable {
    public let mps: Double
    /// False when we are coasting on a stale fix rather than a fresh one.
    public let trusted: Bool

    public init(mps: Double, trusted: Bool) {
        self.mps = mps
        self.trusted = trusted
    }

    public var kmh: Double { mps * 3.6 }

    /// The number on the dial. Rounded once, here, so the two apps agree.
    public var displayKmh: Int { Int(kmh.rounded()) }
}

/// Turns raw GNSS speed into something steady enough to put in large digits.
///
/// The raw value is already good — phones derive it from Doppler shift on the
/// carrier signal, not by differencing positions, which is why it is typically
/// accurate to a few tenths of a metre per second and often beats the car's own
/// speedometer. What it is not is *steady*: it jitters by a few tenths from fix
/// to fix, and a display that flickers between 98 and 101 reads as broken even
/// when it is more correct than the dashboard.
///
/// So the filtering has one job and one constraint. Smooth the jitter, and never
/// lag real acceleration — a readout that takes three seconds to notice you
/// braked is worse than one that wobbles.
public final class SpeedFilter {

    /// Past this, the platform is guessing. Hold the last good value instead.
    public var maxAccuracyMps: Double = 2.0

    /// How long to keep showing the last good value before giving up on it.
    public var holdMs: Int64 = 3_000

    /// Below this, report a flat zero. GNSS noise keeps a parked car reading one
    /// or two km/h, and a speedometer that will not sit still at a red light
    /// looks broken.
    public var restThresholdMps: Double = 0.6

    /// A change bigger than this is real acceleration, not noise.
    public var fastChangeMps: Double = 1.5

    /// Time constants, in seconds: quick when the change is real, slow otherwise.
    public var tauFastS: Double = 0.15
    public var tauSlowS: Double = 1.2

    /// What a car can actually do, which is the honest way to reject a bad fix.
    ///
    /// Smoothing alone cannot tell a hard stop from a GPS glitch: tuned fast
    /// enough to follow real braking, it follows a spike just as faithfully.
    /// Physics can tell them apart. A road car manages roughly 4.5 m/s² under
    /// power and about 9 m/s² braking hard on dry tarmac, so anything outside
    /// that in the time since the last fix did not happen.
    public var maxAccelMps2: Double = 4.5
    public var maxDecelMps2: Double = 9.0

    /// A gap longer than this is a tunnel, not a sample. Start fresh after it.
    public var gapS: Double = 5.0

    private var smoothed: Double?
    private var lastTimestampMs: Int64 = 0
    private var lastGoodMs: Int64 = 0

    public init() {}

    public func reset() {
        smoothed = nil
        lastTimestampMs = 0
        lastGoodMs = 0
    }

    public func update(_ sample: SpeedSample) -> SpeedReading {
        let accuracy = sample.accuracyMps
        let unusable = sample.rawMps < 0
            || (accuracy != nil && (accuracy! < 0 || accuracy! > maxAccuracyMps))

        if unusable {
            // Coast on the last good value briefly — a single bad fix in a
            // built-up area should not blank the dial — then admit we do not
            // know rather than showing a stale number indefinitely.
            if let held = smoothed, sample.timestampMs - lastGoodMs <= holdMs {
                return SpeedReading(mps: held, trusted: false)
            }
            return SpeedReading(mps: 0, trusted: false)
        }

        let raw = sample.rawMps < restThresholdMps ? 0 : sample.rawMps
        let previous = smoothed
        let dtS = lastTimestampMs == 0
            ? 0
            : Double(sample.timestampMs - lastTimestampMs) / 1000.0

        let next: Double
        if previous == nil || dtS <= 0 || dtS > gapS {
            // First reading, a jump backwards in time, or a long gap: there is
            // nothing meaningful to smooth against.
            next = raw
        } else {
            let prior = previous!
            let plausible = min(
                max(raw, prior - maxDecelMps2 * dtS),
                prior + maxAccelMps2 * dtS
            )
            let tau = abs(plausible - prior) > fastChangeMps ? tauFastS : tauSlowS
            let alpha = min(max(dtS / (dtS + tau), 0), 1)
            next = prior + alpha * (plausible - prior)
        }

        smoothed = next
        lastTimestampMs = sample.timestampMs
        lastGoodMs = sample.timestampMs
        return SpeedReading(mps: max(next, 0), trusted: true)
    }
}
