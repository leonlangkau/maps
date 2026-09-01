import Foundation
import RadarKit

/// Keeps the camera bundle on disk so warnings work with no signal.
///
/// This is the reason the app is useful on a country highway: the cameras are
/// already on the phone, and the network is only needed for live hazards.
actor CameraStore {
    private let fileUrl: URL
    private var cached: [Threat] = []
    private var version: Int64 = 0

    init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileUrl = directory.appendingPathComponent("cameras.json")
    }

    var cameras: [Threat] { cached }
    var currentVersion: Int64 { version }

    func loadFromDisk() {
        guard let data = try? Data(contentsOf: fileUrl),
              let bundle = try? JSONDecoder().decode(CameraBundle.self, from: data)
        else { return }
        cached = ThreatMapper.fromCameras(bundle.cameras)
        version = bundle.version
    }

    /// Download only when the server's version differs from what is on disk.
    func syncIfNeeded(api: RadarApi) async {
        do {
            let latest = try await api.cameraBundleVersion()
            guard latest.version != version else { return }

            let bundle = try await api.cameraBundle()
            let encoded = try JSONEncoder().encode(bundle)
            // Write to a temporary file first: a download interrupted halfway
            // must not leave a truncated bundle where the good one was.
            let temporary = fileUrl.appendingPathExtension("tmp")
            try encoded.write(to: temporary, options: .atomic)
            _ = try? FileManager.default.replaceItemAt(fileUrl, withItemAt: temporary)

            cached = ThreatMapper.fromCameras(bundle.cameras)
            version = bundle.version
        } catch {
            // Falling back to the bundle already on disk is exactly right here:
            // stale cameras beat no cameras.
        }
    }
}
