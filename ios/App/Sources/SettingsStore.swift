import Foundation
import RadarKit

/// Persists the driver's alert preferences.
///
/// Stored as one JSON blob rather than a key per preference, because the
/// per-kind table is the bulk of it and a flat store would need a migration
/// every time a new kind appears.
enum SettingsStore {
    private static let key = "au.radar.alertSettings"

    static func load() -> AlertSettings {
        guard let data = UserDefaults.standard.data(forKey: key),
              let saved = try? JSONDecoder().decode(AlertSettings.self, from: data)
        else {
            return AlertSettings()
        }

        // Fill in kinds added since the file was written, so a new feed type is
        // never silently unconfigured.
        var merged = saved
        merged.kinds = AlertSettings.defaultKinds.merging(saved.kinds) { _, saved in saved }
        return merged
    }

    static func save(_ settings: AlertSettings) {
        guard let data = try? JSONEncoder().encode(settings) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }
}
