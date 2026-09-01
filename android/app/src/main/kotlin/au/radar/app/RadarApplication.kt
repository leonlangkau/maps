package au.radar.app

import android.app.Application
import org.maplibre.android.MapLibre

class RadarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // MapLibre needs initialising before any MapView is inflated. There is
        // no API key: the tiles come from our own Worker.
        MapLibre.getInstance(this)
    }
}
