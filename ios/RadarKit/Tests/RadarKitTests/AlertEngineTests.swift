import XCTest
@testable import RadarKit

private struct FixtureDoc: Decodable {
    let note: String
    let now: Int64
    let cases: [FixtureCase]
}

private struct FixtureCase: Decodable {
    let name: String
    let why: String
    let car: CarState
    let state: FixtureState
    let threats: [Threat]
    let expect: FixtureExpect
}

private struct FixtureState: Decodable {
    let lastAnnouncedAt: [String: Int64]
    let lastAnyAnnounceAt: Int64?
    let retired: [String]
}

private struct FixtureExpect: Decodable {
    let threatId: String?
    let level: String?
    let spokenText: String?
}

/// The contract between the two apps. These cases are written by hand from
/// `docs/alert-engine.md`; the Kotlin implementation runs the identical file. If
/// one platform starts behaving differently from the other, it fails here first.
final class AlertEngineFixtureTests: XCTestCase {

    /// Read the fixtures from the repository rather than copying them into the
    /// test bundle, so the two platforms can never drift onto different copies.
    private func loadFixtures() throws -> FixtureDoc {
        let here = URL(fileURLWithPath: #filePath)
        let repoRoot = here
            .deletingLastPathComponent()  // -> RadarKitTests/
            .deletingLastPathComponent()  // -> Tests/
            .deletingLastPathComponent()  // -> RadarKit/
            .deletingLastPathComponent()  // -> ios/
            .deletingLastPathComponent()  // -> repository root
        let fixtures = repoRoot
            .appendingPathComponent("shared")
            .appendingPathComponent("alert-engine-fixtures.json")

        let data = try Data(contentsOf: fixtures)
        return try JSONDecoder().decode(FixtureDoc.self, from: data)
    }

    func testEverySharedFixtureBehavesAsSpecified() throws {
        let doc = try loadFixtures()
        var failures: [String] = []

        for testCase in doc.cases {
            let result = AlertEngine.evaluate(
                now: doc.now,
                car: testCase.car,
                threats: testCase.threats,
                state: EngineState(
                    lastAnnouncedAt: testCase.state.lastAnnouncedAt,
                    lastAnyAnnounceAt: testCase.state.lastAnyAnnounceAt,
                    retired: Set(testCase.state.retired)
                )
            )

            guard let expectedId = testCase.expect.threatId else {
                if let result {
                    failures.append("\(testCase.name): expected silence, got \(result.spokenText)")
                }
                continue
            }

            guard let result else {
                failures.append(
                    "\(testCase.name): expected \(testCase.expect.spokenText ?? "?"), got silence"
                )
                continue
            }

            if result.threatId != expectedId {
                failures.append(
                    "\(testCase.name): expected threat \(expectedId), got \(result.threatId)"
                )
            }
            if result.level.rawValue != testCase.expect.level {
                failures.append(
                    "\(testCase.name): expected level \(testCase.expect.level ?? "?"), got \(result.level.rawValue)"
                )
            }
            if result.spokenText != testCase.expect.spokenText {
                failures.append(
                    "\(testCase.name): expected \"\(testCase.expect.spokenText ?? "")\", got \"\(result.spokenText)\""
                )
            }
        }

        XCTAssertTrue(failures.isEmpty, "\n" + failures.joined(separator: "\n"))
    }

    func testFixtureFileCoversBothOutcomes() throws {
        let doc = try loadFixtures()
        let silent = doc.cases.filter { $0.expect.threatId == nil }.count
        XCTAssertGreaterThanOrEqual(silent, 5, "too few silence cases")
        XCTAssertGreaterThanOrEqual(doc.cases.count - silent, 5, "too few announcement cases")
    }
}

final class AlertEngineUnitTests: XCTestCase {

    private func camera(id: String = "c", lat: Double = -33.86, lon: Double = 151.2093) -> Threat {
        Threat(id: id, kind: "fixed_speed", lat: lat, lon: lon, severity: 2, isCamera: true)
    }

    func testConeNarrowsWithSpeedAndStaysInBounds() {
        XCTAssertEqual(AlertEngine.coneHalfAngle(speedKmh: 0), 70, accuracy: 0.001)
        XCTAssertEqual(AlertEngine.coneHalfAngle(speedKmh: 50), 47.5, accuracy: 0.001)
        XCTAssertEqual(AlertEngine.coneHalfAngle(speedKmh: 100), 25, accuracy: 0.001)
        XCTAssertEqual(AlertEngine.coneHalfAngle(speedKmh: 300), 22, accuracy: 0.001)
        XCTAssertEqual(AlertEngine.coneHalfAngle(speedKmh: -10), 70, accuracy: 0.001)
    }

    func testTriggerRangeRespectsFloorAndCeiling() {
        let cam = camera()
        XCTAssertEqual(
            AlertEngine.triggerRange(speedKmh: 5, threat: cam), AlertEngine.crawlRangeM,
            accuracy: 0.001
        )
        XCTAssertEqual(
            AlertEngine.triggerRange(speedKmh: 20, threat: cam), AlertEngine.minTriggerM,
            accuracy: 0.001
        )
        XCTAssertEqual(
            AlertEngine.triggerRange(speedKmh: 400, threat: cam), AlertEngine.maxTriggerM,
            accuracy: 0.001
        )
    }

    func testCriticalHazardsGetMoreWarningThanMajorOnes() {
        let critical = Threat(id: "a", kind: "closure", lat: -33.86, lon: 151.2, severity: 3)
        let major = Threat(id: "b", kind: "crash", lat: -33.86, lon: 151.2, severity: 2)
        XCTAssertGreaterThan(
            AlertEngine.triggerRange(speedKmh: 100, threat: critical),
            AlertEngine.triggerRange(speedKmh: 100, threat: major)
        )
    }

    func testSeverityIsCappedForHalfBelievedReports() {
        let shaky = Threat(
            id: "a", kind: "police", lat: -33.86, lon: 151.2, severity: 3, confidence: 0.4
        )
        XCTAssertEqual(AlertEngine.effectiveSeverity(shaky), 1)

        let solid = Threat(
            id: "a", kind: "police", lat: -33.86, lon: 151.2, severity: 3, confidence: 0.9
        )
        XCTAssertEqual(AlertEngine.effectiveSeverity(solid), 3)
    }

    func testCamerasAlwaysReachSpeakingSeverity() {
        let lowRank = Threat(
            id: "a", kind: "mobile_zone", lat: -33.86, lon: 151.2, severity: 0, isCamera: true
        )
        XCTAssertEqual(AlertEngine.effectiveSeverity(lowRank), 2)
    }

    func testDistancesAreSpokenTheWayAPersonWould() {
        XCTAssertEqual(AlertEngine.spokenDistance(280), "300 metres")
        XCTAssertEqual(AlertEngine.spokenDistance(420), "400 metres")
        XCTAssertEqual(AlertEngine.spokenDistance(949), "900 metres")
        XCTAssertEqual(AlertEngine.spokenDistance(1_000), "1 kilometre")
        XCTAssertEqual(AlertEngine.spokenDistance(1_100), "1.1 kilometres")
        XCTAssertEqual(AlertEngine.spokenDistance(1_480), "1.5 kilometres")
    }

    func testRecordingAnAnnouncementBlocksAnImmediateRepeat() throws {
        let now: Int64 = 1_760_000_000_000
        let car = CarState(lat: -33.8688, lon: 151.2093, speedMps: 27.78, headingDeg: 0)
        let threats = [camera(id: "cam1", lat: -33.8650229)]

        let first = try XCTUnwrap(
            AlertEngine.evaluate(now: now, car: car, threats: threats, state: EngineState())
        )
        let after = AlertEngine.record(EngineState(), announcement: first, now: now)

        XCTAssertNil(
            AlertEngine.evaluate(now: now + 1_000, car: car, threats: threats, state: after)
        )
        XCTAssertNil(
            AlertEngine.evaluate(now: now + 9 * 60_000, car: car, threats: threats, state: after)
        )
        XCTAssertNotNil(
            AlertEngine.evaluate(now: now + 11 * 60_000, car: car, threats: threats, state: after)
        )
    }

    func testPassingAThreatRetiresItPermanently() {
        let car = CarState(lat: -33.8688, lon: 151.2093, speedMps: 27.78, headingDeg: 0)
        let behind = Threat(
            id: "gone", kind: "fixed_speed", lat: -33.8788, lon: 151.2093, isCamera: true
        )
        let state = AlertEngine.retirePassed(EngineState(), car: car, threats: [behind])
        XCTAssertTrue(state.retired.contains("gone"))
    }

    func testAThreatStillAheadIsNotRetired() {
        let car = CarState(lat: -33.8688, lon: 151.2093, speedMps: 27.78, headingDeg: 0)
        let ahead = Threat(
            id: "here", kind: "fixed_speed", lat: -33.8650229, lon: 151.2093, isCamera: true
        )
        let state = AlertEngine.retirePassed(EngineState(), car: car, threats: [ahead])
        XCTAssertTrue(state.retired.isEmpty)
    }

    func testNothingIsAnnouncedWithNoThreats() {
        let car = CarState(lat: -33.8688, lon: 151.2093, speedMps: 27.78, headingDeg: 0)
        XCTAssertNil(AlertEngine.evaluate(now: 1, car: car, threats: [], state: EngineState()))
    }
}

final class GeoTests: XCTestCase {

    func testDistanceBetweenAPointAndItselfIsZero() {
        XCTAssertEqual(Geo.distanceM(-33.87, 151.21, -33.87, 151.21), 0, accuracy: 0.0001)
    }

    func testSydneyToMelbourneIsAboutSevenHundredThirteenKilometres() {
        let d = Geo.distanceM(-33.8568, 151.2153, -37.8183, 144.9671)
        XCTAssertGreaterThan(d, 705_000)
        XCTAssertLessThan(d, 720_000)
    }

    func testBearingsReadAsCompassDirections() {
        XCTAssertEqual(Geo.bearingDeg(-33.87, 151.21, -33.86, 151.21), 0, accuracy: 0.1)
        XCTAssertEqual(Geo.bearingDeg(-33.87, 151.21, -33.87, 151.22), 90, accuracy: 0.1)
        XCTAssertEqual(Geo.bearingDeg(-33.87, 151.21, -33.88, 151.21), 180, accuracy: 0.1)
        XCTAssertEqual(Geo.bearingDeg(-33.87, 151.21, -33.87, 151.20), 270, accuracy: 0.1)
    }

    func testBearingDeltaWrapsAcrossNorth() {
        XCTAssertEqual(Geo.bearingDelta(0, 0), 0, accuracy: 0.0001)
        XCTAssertEqual(Geo.bearingDelta(10, 350), 20, accuracy: 0.0001)
        XCTAssertEqual(Geo.bearingDelta(350, 10), 20, accuracy: 0.0001)
        XCTAssertEqual(Geo.bearingDelta(0, 180), 180, accuracy: 0.0001)
    }

    func testBearingDeltaStaysWithinZeroAndOneEighty() {
        for a in stride(from: 0.0, to: 360.0, by: 7) {
            for b in stride(from: 0.0, to: 360.0, by: 13) {
                let d = Geo.bearingDelta(a, b)
                XCTAssertTrue((0...180).contains(d), "delta(\(a), \(b)) = \(d)")
            }
        }
    }
}
