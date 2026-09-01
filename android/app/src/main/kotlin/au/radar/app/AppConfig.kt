package au.radar.app

import android.content.Context
import java.util.UUID

/**
 * Where the backend lives and who this device claims to be.
 *
 * The app token is a shared secret for a handful of known users, not an
 * authentication system. It keeps the endpoint from being trivially scraped; it
 * is not a defence against anyone who has the app. If this ever goes wider than
 * friends, replace it with real per-user credentials before it ships.
 */
object AppConfig {
    val baseUrl: String get() = BuildConfig.RADAR_BASE_URL
    val appToken: String get() = BuildConfig.RADAR_APP_TOKEN

    /**
     * A random id minted on first launch. It is not tied to the device, the
     * user, or anything Google assigns, so it cannot be correlated with
     * anything outside this app — and it disappears when the app is uninstalled.
     */
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("radar", Context.MODE_PRIVATE)
        prefs.getString("deviceId", null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString("deviceId", fresh).apply()
        return fresh
    }

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && appToken.isNotBlank()
}
