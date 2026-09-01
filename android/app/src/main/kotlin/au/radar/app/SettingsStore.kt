package au.radar.app

import android.content.Context
import au.radar.core.AlertSettings
import kotlinx.serialization.json.Json

/**
 * Persists the driver's alert preferences.
 *
 * Stored as one JSON blob rather than a field per preference, because the
 * per-kind table is the bulk of it and a flat key-value store would need a
 * migration every time a new kind appears. Unknown keys are ignored on read, so
 * a settings file written by a newer build does not crash an older one.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("radar-settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): AlertSettings {
        val stored = prefs.getString(KEY, null) ?: return AlertSettings()
        return runCatching { json.decodeFromString<AlertSettings>(stored) }
            // A corrupt or incompatible blob falls back to defaults rather than
            // leaving the driver with no warnings at all.
            .getOrElse { AlertSettings() }
            .let { saved ->
                // Fill in kinds added since the file was written, so a new feed
                // type is never silently unconfigured.
                saved.copy(kinds = AlertSettings.defaultKinds + saved.kinds)
            }
    }

    fun save(settings: AlertSettings) {
        prefs.edit()
            .putString(KEY, json.encodeToString(AlertSettings.serializer(), settings))
            .apply()
    }

    private companion object {
        const val KEY = "alert-settings"
    }
}
