import Foundation

/// Where the backend lives and who this device claims to be.
///
/// The app token is a shared secret for a handful of known users, not an
/// authentication system. It keeps the endpoint from being trivially scraped;
/// it is not a defence against anyone who has the app. If this ever goes wider
/// than friends, replace it with real per-user credentials before it ships.
enum AppConfig {
    /// Set in ios/App/Local.xcconfig, which the build folds into Info.plist.
    /// Falls back to the environment for a quick run from Xcode.
    static let baseUrl = plist("RadarBaseUrl")
        ?? env("RADAR_BASE_URL")
        ?? "https://radar-au.example.workers.dev"

    static let appToken = plist("RadarAppToken")
        ?? env("RADAR_APP_TOKEN")
        ?? ""

    /// True when there is a real backend to talk to.
    static var isConfigured: Bool {
        !appToken.isEmpty && !baseUrl.contains("example.workers.dev")
    }

    /// An unset xcconfig variable arrives as an empty string, which is not a
    /// configured value and must not be treated as one.
    private static func plist(_ key: String) -> String? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String,
              !value.trimmingCharacters(in: .whitespaces).isEmpty
        else { return nil }
        return value
    }

    private static func env(_ key: String) -> String? {
        guard let value = ProcessInfo.processInfo.environment[key], !value.isEmpty
        else { return nil }
        return value
    }

    /// A random id minted on first launch and kept in the keychain-free defaults.
    /// It is not tied to the device, the user, or anything Apple assigns, so it
    /// cannot be correlated with anything outside this app — and it disappears
    /// when the app is deleted.
    static var deviceId: String {
        let key = "au.radar.deviceId"
        if let existing = UserDefaults.standard.string(forKey: key) { return existing }
        let fresh = UUID().uuidString
        UserDefaults.standard.set(fresh, forKey: key)
        return fresh
    }
}
