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
    let settings: FixtureSettings
    let kindOverrides: [String: KindSettings]
    let route: [RoutePoint]?
    let threats: [Threat]
    let expect: FixtureExpect
}

private struct FixtureState: Decodable {
    let lastAnnouncedAt: [String: Int64]
    let lastAnyAnnounceAt: Int64?
    let retired: [String]
}

/// The tunable half of `AlertSettings`; the kind table arrives separately.
private struct FixtureSettings: Decodable {
    let muted: Bool
    let flashEnabled: Bool
    let minSpeedKmh: Double
    let sameRoadLeadMultiplier: Double
    let corridorHalfWidthM: Double
    let corridorWidenPerM: Double
    let corridorMaxHalfWidthM: Double
}

private struct FixtureExpect: Decodable {
    let threatId: String?
    let level: String?
    let spokenText: String?
    let flash: Bool
    let relation: String?
}

/// The contract between the two apps.
///
/// The expectations in the fixture file are produced by a third implementation
/// of the rules, written in Python from `docs/alert-engine.md`, so a bug shared
/// by the Swift and Kotlin engines still fails here. The Kotlin suite reads the
/// same file.
final class AlertEngineFixtureTests: XCTestCase {

    private func loadFixtures() throws -> FixtureDoc {
        let here = URL(fileURLWithPath: #filePath)
        let repoRoot = here
            .deletingLastPathComponent()  // -> RadarKitTests/
            .deletingLastPathComponent()  // -> Tests/
            .deletingLastPathComponent()  // -> RadarKit/
            .deletingLastPathComponent()  // -> ios/
            .deletingLastPathComponent()  // -> repository root
        let url = repoRoot
            .appendingPathComponent("shared")
            .appendingPathComponent("alert-engine-fixtures.json")
        return try JSONDecoder().decode(FixtureDoc.self, from: try Data(contentsOf: url))
    }

    func testEverySharedFixtureBehavesAsSpecified() throws {
        let doc = try loadFixtures()
        var failures: [String] = []

        for testCase in doc.cases {
            let settings = AlertSettings(
                muted: testCase.settings.muted,
                flashEnabled: testCase.settings.flashEnabled,
                minSpeedKmh: testCase.settings.minSpeedKmh,
                sameRoadLeadMultiplier: testCase.settings.sameRoadLeadMultiplier,
                corridorHalfWidthM: testCase.settings.corridorHalfWidthM,
                corridorWidenPerM: testCase.settings.corridorWidenPerM,
                corridorMaxHalfWidthM: testCase.settings.corridorMaxHalfWidthM,
                kinds: AlertSettings.defaultKinds.merging(testCase.kindOverrides) { _, new in new }
            )

            // Trimming the route is the caller's job, so the fixture exercises
            // aheadSlice on the way in exactly as the apps do.
            var route: RouteContext?
            if let geometry = testCase.route {
                let slice = RouteTracker.aheadSlice(
                    geometry: geometry, lat: testCase.car.lat, lon: testCase.car.lon
                )
                if slice.count >= 2 { route = RouteContext(aheadGeometry: slice) }
            }

            let result = AlertEngine.evaluate(
                now: doc.now,
                car: testCase.car,
                threats: testCase.threats,
                state: EngineState(
                    lastAnnouncedAt: testCase.state.lastAnnouncedAt,
                    lastAnyAnnounceAt: testCase.state.lastAnyAnnounceAt,
                    retired: Set(testCase.state.retired)
                ),
                settings: settings,
                route: route
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
                failures.append("\(testCase.name): expected threat \(expectedId), got \(result.threatId)")
            }
            if result.level.rawValue != testCase.expect.level {
                failures.append("\(testCase.name): expected level \(testCase.expect.level ?? "?"), got \(result.level.rawValue)")
            }
            if result.spokenText != testCase.expect.spokenText {
                failures.append("\(testCase.name): expected \"\(testCase.expect.spokenText ?? "")\", got \"\(result.spokenText)\"")
            }
            if result.flash != testCase.expect.flash {
                failures.append("\(testCase.name): expected flash \(testCase.expect.flash), got \(result.flash)")
            }
            if result.relation.rawValue != testCase.expect.relation {
                failures.append("\(testCase.name): expected relation \(testCase.expect.relation ?? "?"), got \(result.relation.rawValue)")
            }
        }

        XCTAssertTrue(failures.isEmpty, "\n" + failures.joined(separator: "\n"))
    }

    func testFixtureFileCoversBothOutcomesAndEveryRelation() throws {
        let doc = try loadFixtures()
        let silent = doc.cases.filter { $0.expect.threatId == nil }.count
        XCTAssertGreaterThanOrEqual(silent, 8, "too few silence cases")
        XCTAssertGreaterThanOrEqual(doc.cases.count - silent, 8, "too few announcement cases")

        let relations = Set(doc.cases.compactMap(\.expect.relation))
        for relation in Relation.allCases {
            XCTAssertTrue(
                relations.contains(relation.rawValue),
                "no fixture exercises \(relation.rawValue)"
            )
        }
    }

    func testEveryFixtureCarriesTheReasoningBehindIt() throws {
        // A case whose expectation nobody can check by hand is a case that
        // silently encodes whatever the code happened to do.
        for testCase in try loadFixtures().cases {
            XCTAssertGreaterThan(testCase.why.count, 30, "\(testCase.name): no usable explanation")
        }
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

    func testLeadRangeRespectsFloorAndCeiling() {
        XCTAssertEqual(
            AlertEngine.leadRange(speedKmh: 5, leadSeconds: 25),
            AlertEngine.crawlRangeM, accuracy: 0.001
        )
        XCTAssertEqual(
            AlertEngine.leadRange(speedKmh: 20, leadSeconds: 25),
            AlertEngine.minTriggerM, accuracy: 0.001
        )
        XCTAssertEqual(
            AlertEngine.leadRange(speedKmh: 400, leadSeconds: 25),
            AlertEngine.maxTriggerM, accuracy: 0.001
        )
    }

    func testAClosureGetsMoreWarningThanACrash() {
        let settings = AlertSettings()
        let closure = settings.forKind("closure").leadSeconds
        let crash = settings.forKind("crash").leadSeconds
        XCTAssertGreaterThan(
            AlertEngine.leadRange(speedKmh: 100, leadSeconds: closure),
            AlertEngine.leadRange(speedKmh: 100, leadSeconds: crash)
        )
    }

    func testCorridorWidensWithDistanceThenStops() {
        let settings = AlertSettings()
        XCTAssertEqual(AlertEngine.corridorHalfWidth(distanceM: 0, settings: settings), 40, accuracy: 0.001)
        XCTAssertEqual(AlertEngine.corridorHalfWidth(distanceM: 500, settings: settings), 50, accuracy: 0.001)
        XCTAssertEqual(AlertEngine.corridorHalfWidth(distanceM: 2_000, settings: settings), 80, accuracy: 0.001)
        XCTAssertEqual(AlertEngine.corridorHalfWidth(distanceM: 9_000, settings: settings), 90, accuracy: 0.001)
    }

    func testBeingOnMyRoadAlwaysWarnsAtLeastAsEarly() {
        // The property the whole same-road distinction rests on. If this ever
        // inverts, the app warns later about the thing it is more sure of.
        let settings = AlertSettings()
        for (name, kind) in AlertSettings.defaultKinds {
            for speed in [20.0, 50.0, 80.0, 100.0, 130.0] {
                let ahead = max(
                    kind.radiusM,
                    AlertEngine.leadRange(speedKmh: speed, leadSeconds: kind.leadSeconds)
                )
                let sameRoad = max(
                    kind.radiusM,
                    AlertEngine.leadRange(
                        speedKmh: speed,
                        leadSeconds: kind.leadSeconds * settings.sameRoadLeadMultiplier
                    )
                )
                XCTAssertGreaterThanOrEqual(
                    sameRoad, ahead, "\(name) at \(speed) km/h inverted"
                )
            }
        }
    }

    func testSidewaysThreatIsNeverReachedByALongLeadTime() {
        // A police car 700m to the side must stay silent at any speed, because
        // only the radius applies sideways.
        let beside = Threat(id: "p", kind: "police", lat: -33.8625, lon: 151.2093)
        for speed in [40.0, 80.0, 110.0, 140.0] {
            let car = CarState(
                lat: -33.8688, lon: 151.2093, speedMps: speed / 3.6, headingDeg: 90
            )
            XCTAssertNil(
                AlertEngine.evaluate(now: 1, car: car, threats: [beside], state: EngineState()),
                "warned about a sideways threat at \(speed) km/h"
            )
        }
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
