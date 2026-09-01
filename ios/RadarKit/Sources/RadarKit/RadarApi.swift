import Foundation

public struct HttpReply: Sendable {
    public let status: Int
    public let body: Data

    public var isSuccess: Bool { (200..<300).contains(status) }

    public init(status: Int, body: Data) {
        self.status = status
        self.body = body
    }
}

/// The transport is a protocol so the API surface can be tested without a
/// network, and so the app can swap in a session with its own configuration.
public protocol HttpTransport: Sendable {
    func send(
        method: String,
        url: URL,
        headers: [String: String],
        body: Data?
    ) async throws -> HttpReply
}

public struct URLSessionTransport: HttpTransport {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func send(
        method: String,
        url: URL,
        headers: [String: String],
        body: Data?
    ) async throws -> HttpReply {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        // A driving app on patchy rural coverage should give up quickly and try
        // again rather than hang on a dead socket.
        request.timeoutInterval = 12
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        return HttpReply(status: status, body: data)
    }
}

public struct ApiError: Error, LocalizedError {
    public let status: Int
    public let what: String

    public var errorDescription: String? { "\(what) failed: HTTP \(status)" }
}

/// Everything the app asks the backend for. The backend already merged the feeds
/// and normalised them, so there is nothing to reconcile here — this is a thin,
/// typed wrapper and deliberately stays that way.
public struct RadarApi: Sendable {
    private let base: String
    private let appToken: String
    private let deviceId: String
    private let transport: HttpTransport
    private let decoder: JSONDecoder

    public init(
        baseUrl: String,
        appToken: String,
        deviceId: String,
        transport: HttpTransport = URLSessionTransport()
    ) {
        var trimmed = baseUrl
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        self.base = trimmed
        self.appToken = appToken
        self.deviceId = deviceId
        self.transport = transport
        self.decoder = JSONDecoder()
    }

    private func headers(withBody: Bool = false) -> [String: String] {
        var out = [
            "authorization": "Bearer \(appToken)",
            "x-device-id": deviceId,
            "accept": "application/json",
        ]
        if withBody { out["content-type"] = "application/json" }
        return out
    }

    private func decode<T: Decodable>(
        _ reply: HttpReply,
        as type: T.Type,
        what: String
    ) throws -> T {
        guard reply.isSuccess else { throw ApiError(status: reply.status, what: what) }
        return try decoder.decode(T.self, from: reply.body)
    }

    private func url(_ path: String) throws -> URL {
        guard let url = URL(string: base + path) else {
            throw ApiError(status: 0, what: "building URL for \(path)")
        }
        return url
    }

    /// Live hazards inside a bounding box, already merged across every source.
    public func alerts(
        minLon: Double, minLat: Double,
        maxLon: Double, maxLat: Double,
        since: Int64? = nil
    ) async throws -> GeoJsonFeatureCollection {
        let bbox = "\(minLon),\(minLat),\(maxLon),\(maxLat)"
        let sinceParam = since.map { "&since=\($0)" } ?? ""
        let reply = try await transport.send(
            method: "GET",
            url: try url("/v1/alerts?bbox=\(bbox)\(sinceParam)"),
            headers: headers(),
            body: nil
        )
        return try decode(reply, as: GeoJsonFeatureCollection.self, what: "alerts")
    }

    /// The camera bundle version, so the app can skip a download it already has.
    public func cameraBundleVersion() async throws -> BundleVersion {
        let reply = try await transport.send(
            method: "GET",
            url: try url("/v1/cameras/version"),
            headers: headers(),
            body: nil
        )
        return try decode(reply, as: BundleVersion.self, what: "camera version")
    }

    /// Every camera in the country. Downloaded once, then kept for offline use.
    public func cameraBundle() async throws -> CameraBundle {
        let reply = try await transport.send(
            method: "GET",
            url: try url("/v1/cameras/bundle"),
            headers: headers(),
            body: nil
        )
        return try decode(reply, as: CameraBundle.self, what: "camera bundle")
    }

    public func search(
        query: String,
        nearLat: Double? = nil,
        nearLon: Double? = nil
    ) async throws -> [PlaceResult] {
        let encoded = query.addingPercentEncoding(
            withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-_.~"))
        ) ?? query
        var path = "/v1/search?q=\(encoded)"
        if let nearLat, let nearLon { path += "&near=\(nearLon),\(nearLat)" }

        let reply = try await transport.send(
            method: "GET", url: try url(path), headers: headers(), body: nil
        )
        struct Wrapper: Decodable { let places: [PlaceResult] }
        return try decode(reply, as: Wrapper.self, what: "search").places
    }

    public func route(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ) async throws -> RouteResult {
        let reply = try await transport.send(
            method: "GET",
            url: try url("/v1/route?from=\(fromLon),\(fromLat)&to=\(toLon),\(toLat)"),
            headers: headers(),
            body: nil
        )
        return try decode(reply, as: RouteResult.self, what: "route")
    }

    @discardableResult
    public func report(_ request: ReportRequest) async throws -> String {
        let body = try JSONEncoder().encode(request)
        let reply = try await transport.send(
            method: "POST",
            url: try url("/v1/reports"),
            headers: headers(withBody: true),
            body: body
        )
        struct Created: Decodable { let id: String }
        return try decode(reply, as: Created.self, what: "report").id
    }

    /// Confirm or deny somebody else's report. One vote per device, enforced server side.
    public func vote(reportId: String, confirm: Bool) async throws {
        let verb = confirm ? "confirm" : "deny"
        let reply = try await transport.send(
            method: "POST",
            url: try url("/v1/reports/\(reportId)/\(verb)"),
            headers: headers(),
            body: nil
        )
        // A 409 means this device already voted, which is not an error worth
        // surfacing to a driver — the vote is recorded either way.
        if reply.status == 409 { return }
        guard reply.isSuccess else { throw ApiError(status: reply.status, what: "vote") }
    }

    public func retract(reportId: String) async throws {
        let reply = try await transport.send(
            method: "DELETE",
            url: try url("/v1/reports/\(reportId)"),
            headers: headers(),
            body: nil
        )
        guard reply.isSuccess else { throw ApiError(status: reply.status, what: "retract") }
    }

    /// The MapLibre style URL. Public, so it carries no token.
    public func styleUrl(theme: String = "dark") -> String {
        "\(base)/v1/style.json?theme=\(theme)"
    }
}
