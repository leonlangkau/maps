import Foundation

/// How loudly a single kind of threat is treated.
///
/// Per-kind settings exist because the kinds are genuinely not equivalent. A
/// fixed camera is at a known point and you want a fixed distance of warning. A
/// police car is somewhere in an area, might be facing either way, and is worth
/// knowing about earlier and more insistently. Roadworks, most of the time, you
/// want on the map and out of your ears.
public struct KindSettings: Codable, Equatable, Sendable {
    public var enabled: Bool
    /// False means a tone rather than a spoken warning.
    public var voice: Bool
    /// Pulse the screen, for warnings you might miss over loud audio.
    public var flash: Bool
    /// Seconds of warning on the open road. Converted to metres at your speed.
    public var leadSeconds: Double
    /// Warn about anything inside this radius whatever direction it lies in.
    ///
    /// This is the answer to the case heading alone cannot solve: something just
    /// around a bend, on a side street you are about to cross, or on the far side
    /// of a divided road. Within the radius we stop asking whether it is on your
    /// road and simply tell you it is near.
    public var radiusM: Double

    public init(
        enabled: Bool = true,
        voice: Bool = true,
        flash: Bool = false,
        leadSeconds: Double,
        radiusM: Double
    ) {
        self.enabled = enabled
        self.voice = voice
        self.flash = flash
        self.leadSeconds = leadSeconds
        self.radiusM = radiusM
    }
}

/// Everything the driver can tune, and the defaults they start from.
///
/// The defaults are opinionated rather than neutral: police and mobile cameras
/// flash and get the longest lead, fixed cameras speak, congestion and roadworks
/// stay quiet. Someone who disagrees can change any of it.
public struct AlertSettings: Codable, Equatable, Sendable {
    public var muted: Bool
    /// Master switch for the screen pulse, above the per-kind flags.
    public var flashEnabled: Bool
    /// Below this, say nothing at all. Off by default — the parked rule already
    /// covers the common case, and a driver crawling in traffic still wants to
    /// know about the camera at the next set of lights.
    public var minSpeedKmh: Double
    /// How much further to warn when the threat is on the road you are on. The
    /// whole point of telling those two cases apart.
    public var sameRoadLeadMultiplier: Double
    /// Half-width of the corridor that counts as "my road", at zero distance.
    public var corridorHalfWidthM: Double
    /// How much the corridor widens per metre of distance, absorbing heading
    /// noise and gentle curves without swallowing the next street over.
    public var corridorWidenPerM: Double
    public var corridorMaxHalfWidthM: Double
    public var kinds: [String: KindSettings]

    public init(
        muted: Bool = false,
        flashEnabled: Bool = true,
        minSpeedKmh: Double = 0,
        sameRoadLeadMultiplier: Double = 1.7,
        corridorHalfWidthM: Double = 40,
        corridorWidenPerM: Double = 0.02,
        corridorMaxHalfWidthM: Double = 90,
        kinds: [String: KindSettings] = AlertSettings.defaultKinds
    ) {
        self.muted = muted
        self.flashEnabled = flashEnabled
        self.minSpeedKmh = minSpeedKmh
        self.sameRoadLeadMultiplier = sameRoadLeadMultiplier
        self.corridorHalfWidthM = corridorHalfWidthM
        self.corridorWidenPerM = corridorWidenPerM
        self.corridorMaxHalfWidthM = corridorMaxHalfWidthM
        self.kinds = kinds
    }

    public func forKind(_ kind: String) -> KindSettings {
        kinds[kind] ?? AlertSettings.fallbackKind
    }

    /// Used for a kind nobody has configured, so a new feed cannot go silent.
    public static let fallbackKind = KindSettings(leadSeconds: 20, radiusM: 300)

    public static let defaultKinds: [String: KindSettings] = [
        // The two people actually install this app for.
        "police": KindSettings(voice: true, flash: true, leadSeconds: 45, radiusM: 500),
        "mobile_camera": KindSettings(voice: true, flash: true, leadSeconds: 40, radiusM: 450),
        "mobile_zone": KindSettings(voice: true, flash: true, leadSeconds: 35, radiusM: 400),
        "trailer": KindSettings(voice: true, flash: false, leadSeconds: 30, radiusM: 350),

        // Fixed infrastructure: a known point, so a steady lead is enough.
        "fixed_speed": KindSettings(leadSeconds: 25, radiusM: 300),
        "red_light": KindSettings(leadSeconds: 22, radiusM: 250),
        "red_light_speed": KindSettings(leadSeconds: 25, radiusM: 300),
        "average_speed_start": KindSettings(leadSeconds: 30, radiusM: 350),
        "average_speed_end": KindSettings(leadSeconds: 20, radiusM: 250),

        // Road conditions.
        "crash": KindSettings(flash: true, leadSeconds: 35, radiusM: 400),
        "closure": KindSettings(flash: true, leadSeconds: 45, radiusM: 500),
        "flood": KindSettings(flash: true, leadSeconds: 40, radiusM: 450),
        "fire": KindSettings(flash: true, leadSeconds: 40, radiusM: 450),
        "object_on_road": KindSettings(leadSeconds: 30, radiusM: 350),
        "stopped_vehicle": KindSettings(leadSeconds: 25, radiusM: 300),
        "hazard": KindSettings(leadSeconds: 25, radiusM: 300),
        "alpine": KindSettings(voice: false, leadSeconds: 30, radiusM: 400),

        // Things you can see for yourself. On the map, out of your ears.
        "congestion": KindSettings(voice: false, leadSeconds: 20, radiusM: 250),
        "roadwork": KindSettings(voice: false, leadSeconds: 15, radiusM: 200),
        "event": KindSettings(voice: false, leadSeconds: 20, radiusM: 250),
    ]

    /// The kinds a settings screen offers, in the order they are shown.
    public static let editableKinds: [(kind: String, title: String, group: String)] = [
        ("police", "Police", "Crowd reports"),
        ("mobile_camera", "Mobile camera (reported)", "Crowd reports"),
        ("mobile_zone", "Mobile camera zone", "Cameras"),
        ("trailer", "Camera trailer", "Cameras"),
        ("fixed_speed", "Fixed speed camera", "Cameras"),
        ("red_light_speed", "Red light and speed", "Cameras"),
        ("red_light", "Red light camera", "Cameras"),
        ("average_speed_start", "Average speed zone", "Cameras"),
        ("crash", "Crash", "Road conditions"),
        ("closure", "Road closed", "Road conditions"),
        ("flood", "Flooding", "Road conditions"),
        ("fire", "Fire", "Road conditions"),
        ("object_on_road", "Object on road", "Road conditions"),
        ("stopped_vehicle", "Stopped vehicle", "Road conditions"),
        ("hazard", "Other hazards", "Road conditions"),
        ("congestion", "Heavy traffic", "Quiet by default"),
        ("roadwork", "Roadworks", "Quiet by default"),
        ("event", "Events", "Quiet by default"),
    ]
}

/// How a threat relates to the road you are on. This is the distinction the
/// whole engine turns on: something on your road deserves a much earlier warning
/// than something merely in front of you, and something very close deserves one
/// whichever way it lies.
public enum Relation: String, Codable, Sendable, CaseIterable {
    /// On the route you are following, measured along the road, so curves count.
    case onRoute = "ON_ROUTE"
    /// Inside a narrow corridor straight down your heading: almost certainly your road.
    case sameRoad = "SAME_ROAD"
    /// Within the forward cone, but off the corridor: a nearby road, or a bend.
    case ahead = "AHEAD"
    /// Close enough that direction stops mattering.
    case nearby = "NEARBY"
}

/// The stretch of route immediately in front of the car, already trimmed by the
/// caller with `RouteTracker.aheadSlice`.
///
/// Trimming outside the engine keeps it cheap: projecting every nearby threat
/// onto a full cross-country polyline every second would be thousands of times
/// more work than projecting it onto the next few kilometres.
public struct RouteContext: Sendable {
    public let aheadGeometry: [RoutePoint]

    public init(aheadGeometry: [RoutePoint]) {
        self.aheadGeometry = aheadGeometry
    }
}
