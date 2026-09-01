import XCTest
@testable import RadarKit

/// The speedometer has to be both accurate and steady, and those pull against
/// each other. These tests pin both ends: it must settle on a true constant, and
/// it must not lag a real change. They mirror the Kotlin suite case for case.
final class SpeedFilterTests: XCTestCase {

    private func feed(
        _ filter: SpeedFilter,
        _ values: [Double],
        startMs: Int64 = 1_000,
        stepMs: Int64 = 1_000,
        accuracy: Double? = 0.5
    ) -> [SpeedReading] {
        values.enumerated().map { index, value in
            filter.update(
                SpeedSample(
                    rawMps: value,
                    accuracyMps: accuracy,
                    timestampMs: startMs + Int64(index) * stepMs
                )
            )
        }
    }

    func testASteadySpeedSettlesOnExactlyThatSpeed() {
        let readings = feed(SpeedFilter(), Array(repeating: 27.78, count: 15))
        XCTAssertEqual(readings.last!.mps, 27.78, accuracy: 0.01)
        XCTAssertTrue(readings.last!.trusted)
    }

    func testTheFirstReadingIsTakenAtFaceValue() {
        // There is nothing to smooth against, and starting from zero would show
        // the driver accelerating from a standstill they are not at.
        let reading = SpeedFilter().update(
            SpeedSample(rawMps: 25, accuracyMps: 0.5, timestampMs: 1_000)
        )
        XCTAssertEqual(reading.mps, 25, accuracy: 0.001)
    }

    func testJitterIsSmoothedAway() {
        // A deterministic wobble standing in for the few tenths of noise a
        // phone actually produces.
        let filter = SpeedFilter()
        let noise = [0.3, -0.25, 0.18, -0.31, 0.09, 0.27, -0.19, -0.08, 0.22, -0.3]
        let values = (0..<40).map { 27.78 + noise[$0 % noise.count] }
        let readings = feed(filter, values)

        let settled = readings.dropFirst(10)
        let spread = settled.map(\.mps).max()! - settled.map(\.mps).min()!
        XCTAssertLessThan(spread, 0.35, "output still wobbling by \(spread) m/s")
    }

    func testHardBrakingIsFollowedAlmostImmediately() {
        // The constraint that matters more than smoothness.
        let filter = SpeedFilter()
        _ = feed(filter, Array(repeating: 27.78, count: 10))

        let braking = feed(filter, [22, 16, 10, 5], startMs: 11_000)
        XCTAssertLessThan(
            abs(braking[1].mps - 16), 2.0,
            "lagging badly during braking: \(braking[1].mps) against 16"
        )
        XCTAssertLessThan(abs(braking.last!.mps - 5), 1.5)
    }

    func testASingleWildOutlierIsLargelyAbsorbed() {
        let filter = SpeedFilter()
        _ = feed(filter, Array(repeating: 27.78, count: 10))

        // One fix claiming a 40 m/s jump: physically impossible in a second.
        let spike = filter.update(
            SpeedSample(rawMps: 68, accuracyMps: 0.5, timestampMs: 11_000)
        )
        XCTAssertLessThan(spike.mps, 45, "the spike went almost straight through")

        let recovered = feed(filter, Array(repeating: 27.78, count: 4), startMs: 12_000)
        XCTAssertLessThan(abs(recovered.last!.mps - 27.78), 1.5)
    }

    func testAParkedCarReadsExactlyZero() {
        let readings = feed(SpeedFilter(), Array(repeating: 0.45, count: 10))
        XCTAssertEqual(readings.last!.mps, 0, accuracy: 0.001)
        XCTAssertEqual(readings.last!.displayKmh, 0)
    }

    func testAPoorFixHoldsThenAdmitsItDoesNotKnow() {
        let filter = SpeedFilter()
        _ = feed(filter, Array(repeating: 27.78, count: 10))

        let held = filter.update(
            SpeedSample(rawMps: 5, accuracyMps: 9, timestampMs: 11_000)
        )
        XCTAssertEqual(held.mps, 27.78, accuracy: 0.1)
        XCTAssertFalse(held.trusted)

        let given = filter.update(
            SpeedSample(rawMps: 5, accuracyMps: 9, timestampMs: 15_500)
        )
        XCTAssertEqual(given.mps, 0, accuracy: 0.001)
        XCTAssertFalse(given.trusted)
    }

    func testAPlatformFlaggingItsReadingAsInvalidIsNotBelieved() {
        // iOS reports -1 for both speed and accuracy when it has no fix.
        let reading = SpeedFilter().update(
            SpeedSample(rawMps: -1, accuracyMps: -1, timestampMs: 1_000)
        )
        XCTAssertEqual(reading.mps, 0, accuracy: 0.001)
        XCTAssertFalse(reading.trusted)
    }

    func testComingOutOfATunnelSnapsRatherThanCrawlingUp() {
        let filter = SpeedFilter()
        _ = feed(filter, Array(repeating: 27.78, count: 5))

        let after = filter.update(
            SpeedSample(rawMps: 15, accuracyMps: 0.5, timestampMs: 95_000)
        )
        XCTAssertEqual(after.mps, 15, accuracy: 0.001)
    }

    func testAMissingAccuracyFigureIsStillUsable() {
        // Some Android devices never populate speed accuracy, and iOS omits it
        // on older hardware. Refusing to show a speed there would be worse.
        let readings = feed(
            SpeedFilter(), Array(repeating: 20.0, count: 12), accuracy: nil
        )
        XCTAssertEqual(readings.last!.mps, 20, accuracy: 0.05)
        XCTAssertTrue(readings.last!.trusted)
    }

    func testTheOutputIsNeverNegative() {
        let filter = SpeedFilter()
        let values: [Double] = [0.2, 2.9, 0.0, 1.1, 0.4, 2.2, 0.7, 0.1, 1.8, 0.3]
        for (i, value) in values.enumerated() {
            let reading = filter.update(
                SpeedSample(
                    rawMps: value, accuracyMps: 0.5,
                    timestampMs: 1_000 + Int64(i) * 1_000
                )
            )
            XCTAssertGreaterThanOrEqual(reading.mps, 0, "negative speed at step \(i)")
        }
    }

    func testTheDisplayedNumberRoundsTheWayASpeedometerDoes() {
        XCTAssertEqual(SpeedReading(mps: 27.78, trusted: true).displayKmh, 100)
        XCTAssertEqual(SpeedReading(mps: 16.67, trusted: true).displayKmh, 60)
        XCTAssertEqual(SpeedReading(mps: 0, trusted: true).displayKmh, 0)
    }
}

final class CongestionSpanTests: XCTestCase {

    private func line(_ n: Int) -> [RoutePoint] {
        (0..<n).map { RoutePoint(lat: -33.87 + Double($0) * 0.001, lon: 151.21) }
    }

    func testOneLevelAcrossTheWholeRouteIsOneSpan() {
        let spans = RouteTracker.congestionSpans(
            geometry: line(5), congestion: Array(repeating: "low", count: 4)
        )
        XCTAssertEqual(spans.count, 1)
        XCTAssertEqual(spans[0].level, "low")
        XCTAssertEqual(spans[0].points.count, 5)
    }

    func testChangesSplitIntoRunsThatShareTheirBoundaryPoint() {
        let spans = RouteTracker.congestionSpans(
            geometry: line(6),
            congestion: ["low", "low", "heavy", "heavy", "low"]
        )
        XCTAssertEqual(spans.map(\.level), ["low", "heavy", "low"])
        XCTAssertEqual(spans[0].points.count, 3)
        XCTAssertEqual(spans[1].points.count, 3)
        XCTAssertEqual(spans[2].points.count, 2)

        // Sharing the boundary is what stops a visible gap in the drawn line.
        XCTAssertEqual(spans[0].points.last, spans[1].points.first)
        XCTAssertEqual(spans[1].points.last, spans[2].points.first)
    }

    func testEverySegmentIsCoveredExactlyOnce() {
        let congestion = ["low", "heavy", "heavy", "severe", "low", "low", "moderate"]
        let spans = RouteTracker.congestionSpans(geometry: line(8), congestion: congestion)
        let segments = spans.reduce(0) { $0 + $1.points.count - 1 }
        XCTAssertEqual(segments, congestion.count, "spans do not tile the route")
    }

    func testAProviderWithNoTrafficDataYieldsOneUnknownSpan() {
        let spans = RouteTracker.congestionSpans(geometry: line(5), congestion: [])
        XCTAssertEqual(spans.count, 1)
        XCTAssertEqual(spans[0].level, "unknown")
        XCTAssertEqual(spans[0].points.count, 5)
    }

    func testAMismatchedArrayFallsBackRatherThanGuessing() {
        let spans = RouteTracker.congestionSpans(
            geometry: line(5), congestion: ["low", "heavy"]
        )
        XCTAssertEqual(spans.count, 1)
        XCTAssertEqual(spans[0].level, "unknown")
    }

    func testADegenerateRouteYieldsNothing() {
        XCTAssertTrue(RouteTracker.congestionSpans(geometry: [], congestion: []).isEmpty)
        XCTAssertTrue(RouteTracker.congestionSpans(geometry: line(1), congestion: []).isEmpty)
    }
}

final class TrafficDelayTests: XCTestCase {

    private func option(durationS: Double, freeFlowS: Double) -> RouteOption {
        RouteOption(
            distanceM: 18_400, durationS: durationS,
            geometry: "", durationFreeFlowS: freeFlowS
        )
    }

    func testTheDelayIsTheGapAgainstAClearRun() {
        XCTAssertEqual(
            RouteTracker.trafficDelayS(option(durationS: 1_800, freeFlowS: 1_440)),
            360, accuracy: 0.001
        )
    }

    func testARouteRunningFasterThanTypicalIsNotANegativeDelay() {
        XCTAssertEqual(
            RouteTracker.trafficDelayS(option(durationS: 1_400, freeFlowS: 1_440)),
            0, accuracy: 0.001
        )
    }

    func testADelayWorthMentioningIsDescribed() {
        XCTAssertEqual(
            RouteTracker.describeTraffic(option(durationS: 1_800, freeFlowS: 1_440)),
            "6 min of traffic"
        )
        XCTAssertNil(RouteTracker.describeTraffic(option(durationS: 1_470, freeFlowS: 1_440)))
    }
}
