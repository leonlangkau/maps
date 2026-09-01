import XCTest
@testable import RadarKit

private struct RouteFixtures: Decodable {
    let note: String
    let polyline6: String
    let expectedPoints: [FixturePoint]
    let totalDistanceM: Double
    let offRouteThresholdM: Double
    let steps: [RouteStep]
    let toleranceM: Double
    let cases: [RouteCase]
}

private struct FixturePoint: Decodable {
    let lat: Double
    let lon: Double
}

private struct RouteCase: Decodable {
    let name: String
    let why: String
    let lat: Double
    let lon: Double
    let expect: RouteExpect
}

private struct RouteExpect: Decodable {
    let distanceAlongM: Double
    let distanceRemainingM: Double
    let offRouteByM: Double
    let isOffRoute: Bool
    let stepIndex: Int
    let distanceToManeuverM: Double
}

/// Route geometry, pinned to the same fixture file the Kotlin implementation
/// reads. The expected values were computed independently in Python, so a bug
/// shared between the two implementations still fails here.
final class RouteFixtureTests: XCTestCase {

    private func loadFixtures() throws -> RouteFixtures {
        let here = URL(fileURLWithPath: #filePath)
        let repoRoot = here
            .deletingLastPathComponent()  // -> RadarKitTests/
            .deletingLastPathComponent()  // -> Tests/
            .deletingLastPathComponent()  // -> RadarKit/
            .deletingLastPathComponent()  // -> ios/
            .deletingLastPathComponent()  // -> repository root
        let url = repoRoot
            .appendingPathComponent("shared")
            .appendingPathComponent("route-fixtures.json")
        return try JSONDecoder().decode(RouteFixtures.self, from: try Data(contentsOf: url))
    }

    func testEncodedPolylineDecodesToExpectedPoints() throws {
        let fixtures = try loadFixtures()
        let decoded = Polyline.decode(fixtures.polyline6)

        XCTAssertEqual(decoded.count, fixtures.expectedPoints.count)
        for (i, expected) in fixtures.expectedPoints.enumerated() {
            XCTAssertEqual(decoded[i].lat, expected.lat, accuracy: 1e-6, "point \(i) latitude")
            XCTAssertEqual(decoded[i].lon, expected.lon, accuracy: 1e-6, "point \(i) longitude")
        }
    }

    func testEveryProgressCaseMatchesTheIndependentExpectation() throws {
        let fixtures = try loadFixtures()
        let geometry = Polyline.decode(fixtures.polyline6)
        let tolerance = fixtures.toleranceM
        var failures: [String] = []

        for testCase in fixtures.cases {
            guard let progress = RouteTracker.progress(
                geometry: geometry, steps: fixtures.steps,
                lat: testCase.lat, lon: testCase.lon
            ) else {
                failures.append("\(testCase.name): got no progress at all")
                continue
            }

            func check(_ label: String, _ actual: Double, _ expected: Double) {
                if abs(actual - expected) > tolerance {
                    failures.append("\(testCase.name): \(label) expected \(expected), got \(actual)")
                }
            }

            check("distanceAlongM", progress.distanceAlongM, testCase.expect.distanceAlongM)
            check("distanceRemainingM", progress.distanceRemainingM, testCase.expect.distanceRemainingM)
            check("offRouteByM", progress.offRouteByM, testCase.expect.offRouteByM)
            check("distanceToManeuverM", progress.distanceToManeuverM, testCase.expect.distanceToManeuverM)

            if progress.isOffRoute != testCase.expect.isOffRoute {
                failures.append("\(testCase.name): isOffRoute expected \(testCase.expect.isOffRoute), got \(progress.isOffRoute)")
            }
            if progress.stepIndex != testCase.expect.stepIndex {
                failures.append("\(testCase.name): stepIndex expected \(testCase.expect.stepIndex), got \(progress.stepIndex)")
            }
        }

        XCTAssertTrue(failures.isEmpty, "\n" + failures.joined(separator: "\n"))
    }

    func testOffRouteThresholdMatchesTheFixtureFile() throws {
        let fixtures = try loadFixtures()
        XCTAssertEqual(RouteTracker.offRouteThresholdM, fixtures.offRouteThresholdM, accuracy: 0.001)
    }

    func testDecodedLineMeasuresTheExpectedTotalLength() throws {
        let fixtures = try loadFixtures()
        let geometry = Polyline.decode(fixtures.polyline6)

        var total = 0.0
        for i in 1..<geometry.count {
            total += Geo.distanceM(
                geometry[i - 1].lat, geometry[i - 1].lon,
                geometry[i].lat, geometry[i].lon
            )
        }
        XCTAssertEqual(total, fixtures.totalDistanceM, accuracy: fixtures.toleranceM)
    }
}

final class PolylineTests: XCTestCase {

    func testDecodesTheCanonicalPrecisionFiveExample() {
        // The example from Google's own polyline documentation.
        let points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision: 5)
        XCTAssertEqual(points.count, 3)
        XCTAssertEqual(points[0].lat, 38.5, accuracy: 1e-5)
        XCTAssertEqual(points[0].lon, -120.2, accuracy: 1e-5)
        XCTAssertEqual(points[1].lat, 40.7, accuracy: 1e-5)
        XCTAssertEqual(points[1].lon, -120.95, accuracy: 1e-5)
        XCTAssertEqual(points[2].lat, 43.252, accuracy: 1e-5)
        XCTAssertEqual(points[2].lon, -126.453, accuracy: 1e-5)
    }

    func testEmptyStringDecodesToNoPoints() {
        XCTAssertTrue(Polyline.decode("").isEmpty)
    }

    func testTruncatedStringStopsCleanly() {
        // A dropped final byte is the shape a partial network read takes.
        let full = Polyline.decode("_p~iF~ps|U_ulLnnqC", precision: 5)
        let truncated = Polyline.decode("_p~iF~ps|U_ulLnnq", precision: 5)
        XCTAssertEqual(full.count, 2)
        XCTAssertLessThanOrEqual(truncated.count, full.count)
    }

    func testHandlesNegativeDeltasInBothAxes() {
        let points = Polyline.decode("~~dr_Agtal_H~xG~xG")
        XCTAssertEqual(points.count, 2)
        XCTAssertLessThan(points[1].lat, points[0].lat)
        XCTAssertLessThan(points[1].lon, points[0].lon)
    }
}

final class RouteTrackerFormattingTests: XCTestCase {

    func testDurationsReadTheWayAnEtaStripDoes() {
        XCTAssertEqual(RouteTracker.formatDuration(300), "5 min")
        XCTAssertEqual(RouteTracker.formatDuration(3_540), "59 min")
        XCTAssertEqual(RouteTracker.formatDuration(3_600), "1 hr")
        XCTAssertEqual(RouteTracker.formatDuration(5_400), "1 hr 30 min")
        XCTAssertEqual(RouteTracker.formatDuration(7_500), "2 hr 5 min")
    }

    func testDistancesReadTheWayAnEtaStripDoes() {
        XCTAssertEqual(RouteTracker.formatDistance(450), "400 m")
        XCTAssertEqual(RouteTracker.formatDistance(1_500), "1.5 km")
        XCTAssertEqual(RouteTracker.formatDistance(12_400), "12 km")
    }

    func testManeuverPromptReadsAsASentence() {
        let progress = RouteProgress(
            snappedLat: 0, snappedLon: 0,
            distanceAlongM: 100, distanceRemainingM: 900, durationRemainingS: 60,
            offRouteByM: 0, isOffRoute: false,
            stepIndex: 0, distanceToManeuverM: 400,
            currentStep: RouteStep(
                instruction: "Turn left onto George Street",
                distanceM: 500, durationS: 60, modifier: "left", name: "George Street"
            ),
            nextStep: nil
        )
        XCTAssertEqual(
            RouteTracker.maneuverPrompt(progress),
            "In 400 metres, turn left onto George Street"
        )
    }

    func testImminentManeuverDropsTheDistancePreamble() {
        let progress = RouteProgress(
            snappedLat: 0, snappedLon: 0,
            distanceAlongM: 480, distanceRemainingM: 20, durationRemainingS: 5,
            offRouteByM: 0, isOffRoute: false,
            stepIndex: 0, distanceToManeuverM: 15,
            currentStep: RouteStep(
                instruction: "Turn left onto George Street",
                distanceM: 500, durationS: 60, modifier: "left", name: "George Street"
            ),
            nextStep: nil
        )
        XCTAssertEqual(RouteTracker.maneuverPrompt(progress), "Turn left onto George Street")
    }

    func testRouteWithFewerThanTwoPointsYieldsNoProgress() {
        XCTAssertNil(RouteTracker.progress(geometry: [], steps: [], lat: -33.8, lon: 151.2))
        XCTAssertNil(RouteTracker.progress(
            geometry: [RoutePoint(lat: -33.8, lon: 151.2)], steps: [], lat: -33.8, lon: 151.2
        ))
    }
}
