package au.radar.app

import android.content.Context
import au.radar.core.CameraBundle
import au.radar.core.RadarApi
import au.radar.core.Threat
import au.radar.core.ThreatMapper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Keeps the camera bundle on disk so warnings work with no signal.
 *
 * This is why the app is useful on a country highway: the cameras are already
 * on the phone, and the network is only needed for live hazards.
 */
class CameraStore(context: Context) {

    private val file = File(context.filesDir, "cameras.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var cameras: List<Threat> = emptyList()
        private set

    @Volatile
    var version: Long = 0
        private set

    suspend fun loadFromDisk() = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        runCatching {
            val bundle = json.decodeFromString<CameraBundle>(file.readText())
            cameras = ThreatMapper.fromCameras(bundle.cameras)
            version = bundle.version
        }
        Unit
    }

    /** Download only when the server's version differs from what is on disk. */
    suspend fun syncIfNeeded(api: RadarApi) = withContext(Dispatchers.IO) {
        runCatching {
            val latest = api.cameraBundleVersion()
            if (latest.version == version) return@runCatching

            val bundle = api.cameraBundle()

            // Write to a temporary file first: a download interrupted halfway
            // must not leave a truncated bundle where the good one was.
            val temporary = File(file.parentFile, "cameras.json.tmp")
            temporary.writeText(json.encodeToString(CameraBundle.serializer(), bundle))
            if (temporary.renameTo(file) || (file.delete() && temporary.renameTo(file))) {
                cameras = ThreatMapper.fromCameras(bundle.cameras)
                version = bundle.version
            }
        }
        // Falling back to the bundle already on disk is exactly right here:
        // stale cameras beat no cameras.
        Unit
    }
}
