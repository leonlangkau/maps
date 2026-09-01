import Foundation

/// Where the backend lives and who this device claims to be.
///
/// The app token is a shared secret for a handful of known users, not an
/// authentication system. It keeps the endpoint from being trivially scraped;
/// it is not a defence against anyone who has the app. If this ever goes wider
/// than friends, replace it with real per-user credentials before it ships.
enum AppConfig {
    /// Set to your deployed Worker, e.g. https://radar-au.<subdomain>.workers.dev
    static let baseUrl = Bundle.main.object(forInfoDictionaryKey: "RadarBaseUrl") as? String
        ?? ProcessInfo.processInfo.environment["RADAR_BASE_URL"]
        ?? "https://radar-au.example.workers.dev"

    static let appToken = Bundle.main.object(forInfoDictionaryKey: "RadarAppToken") as? String
        ?? ProcessInfo.processInfo.environment["RADAR_APP_TOKEN"]
        ?? ""

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
