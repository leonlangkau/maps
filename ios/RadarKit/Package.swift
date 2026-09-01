// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "RadarKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "RadarKit", targets: ["RadarKit"]),
    ],
    targets: [
        // Pure Swift: no UIKit, no CoreLocation. The app wraps it; the tests do
        // not need a simulator to run it.
        .target(name: "RadarKit"),
        .testTarget(name: "RadarKitTests", dependencies: ["RadarKit"]),
    ]
)
