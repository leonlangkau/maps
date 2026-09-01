import Foundation

/// Anything the engine can warn about: a camera from the bundle, or a live hazard.
public struct Threat: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let kind: String
    public let lat: Double
    public let lon: Double
    /// The direction of travel this applies to, or nil when it applies both ways.
    public let bearingDeg: Double?
    public let severity: Int
    public let confidence: Double
    public let isCamera: Bool
    public let speedLimit: Int?

    public init(
        id: String,
        kind: String,
        lat: Double,
        lon: Double,
        bearingDeg: Double? = nil,
        severity: Int = 1,
        confidence: Double = 1.0,
        isCamera: Bool = false,
        speedLimit: Int? = nil
    ) {
        self.id = id
        self.kind = kind
        self.lat = lat
        self.lon = lon
        self.bearingDeg = bearingDeg
        self.severity = severity
        self.confidence = confidence
        self.isCamera = isCamera
        self.speedLimit = speedLimit
    }
}

/// Where the car is right now, as CoreLocation last reported it.
public struct CarState: Codable, Equatable, Sendable {
    public let lat: Double
    public let lon: Double
    public let speedMps: Double
    /// Nil when the fix is too poor or the car is stopped, so heading is meaningless.
    public let headingDeg: Double?
    /// How long the car has been effectively stationary. Zero when moving.
    public let stationaryForMs: Int64

    public var speedKmh: Double { speedMps * 3.6 }

    public init(
        lat: Double,
        lon: Double,
        speedMps: Double,
        headingDeg: Double? = nil,
        stationaryForMs: Int64 = 0
    ) {
        self.lat = lat
        self.lon = lon
        self.speedMps = speedMps
        self.headingDeg = headingDeg
        self.stationaryForMs = stationaryForMs
    }
}

/// What the engine remembers between ticks. Value type; `record` returns a new one.
public struct EngineState: Codable, Equatable, Sendable {
    public var lastAnnouncedAt: [String: Int64]
    public var lastAnyAnnounceAt: Int64?
    public var retired: Set<String>

    public init(
        lastAnnouncedAt: [String: Int64] = [:],
        lastAnyAnnounceAt: Int64? = nil,
        retired: Set<String> = []
    ) {
        self.lastAnnouncedAt = lastAnnouncedAt
        self.lastAnyAnnounceAt = lastAnyAnnounceAt
        self.retired = retired
    }
}

public enum AnnouncementLevel: String, Codable, Sendable {
    /// A tone. Low severity, or a report we do not fully believe.
    case chime
    /// Spoken aloud through the car audio.
    case speak
}

public struct Announcement: Codable, Equatable, Sendable {
    public let threatId: String
    public let level: AnnouncementLevel
    public let spokenText: String
    /// How far away it is. Measured along the road when we are following a
    /// route, straight-line otherwise.
    public let distanceM: Double
    /// Pulse the screen as well as speaking, for warnings worth not missing.
    public let flash: Bool
    /// Why this one was raised: on my road, in front of me, or simply close.
    public let relation: Relation

    public init(
        threatId: String,
        level: AnnouncementLevel,
        spokenText: String,
        distanceM: Double,
        flash: Bool = false,
        relation: Relation = .ahead
    ) {
        self.threatId = threatId
        self.level = level
        self.spokenText = spokenText
        self.distanceM = distanceM
        self.flash = flash
        self.relation = relation
    }
}

// MARK: - API payloads

public struct ApiAlert: Codable, Sendable {
    public let id: String
    public let source: String
    public let kind: String
    public let headline: String
    public let detail: String?
    public let road: String?
    public let bearing: Double?
    public let severity: Int
    public let startedAt: Int64?
    public let updatedAt: Int64?
    public let expiresAt: Int64?
    public let confidence: Double
}

public struct ApiCamera: Codable, Sendable {
    public let id: String
    public let source: String
    public let kind: String
    public let lat: Double
    public let lon: Double
    public let road: String?
    public let suburb: String?
    public let state: String
    public let speedLimit: Int?
    public let bearing: Double?
    public let verifiedAt: Int64?
}

public struct CameraBundle: Codable, Sendable {
    public let version: Int64
    public let count: Int
    public let cameras: [ApiCamera]
}

public struct BundleVersion: Codable, Sendable {
    public let version: Int64
    public let url: String
}

public struct PlaceResult: Codable, Identifiable, Sendable {
    public var id: String { "\(lat),\(lon),\(name)" }
    public let name: String
    public let address: String?
    public let lat: Double
    public let lon: Double
}

public struct RouteStep: Codable, Sendable {
    public let instruction: String
    public let distanceM: Double
    public let durationS: Double
    public let modifier: String?
    public let name: String?

    public init(
        instruction: String,
        distanceM: Double,
        durationS: Double,
        modifier: String? = nil,
        name: String? = nil
    ) {
        self.instruction = instruction
        self.distanceM = distanceM
        self.durationS = durationS
        self.modifier = modifier
        self.name = name
    }
}

public struct RouteLeg: Codable, Sendable {
    public let distanceM: Double
    public let durationS: Double
    public let steps: [RouteStep]
}

public struct RouteOption: Codable, Sendable {
    public let distanceM: Double
    public let durationS: Double
    public let geometry: String
    public let legs: [RouteLeg]
}

public struct RouteResult: Codable, Sendable {
    public let provider: String
    public let routes: [RouteOption]
}

public struct ReportRequest: Codable, Sendable {
    public let kind: String
    public let lat: Double
    public let lon: Double
    public let bearing: Double?
    public let note: String?

    public init(kind: String, lat: Double, lon: Double, bearing: Double? = nil, note: String? = nil) {
        self.kind = kind
        self.lat = lat
        self.lon = lon
        self.bearing = bearing
        self.note = note
    }
}

public struct GeoJsonGeometry: Codable, Sendable {
    public let type: String
    public let coordinates: [Double]
}

public struct GeoJsonFeature: Codable, Sendable {
    public let type: String
    public let geometry: GeoJsonGeometry
    public let properties: ApiAlert
}

public struct GeoJsonFeatureCollection: Codable, Sendable {
    public let type: String
    public let features: [GeoJsonFeature]
    public let generatedAt: Int64?
}
