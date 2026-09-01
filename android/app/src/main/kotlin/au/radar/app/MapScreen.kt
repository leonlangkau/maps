package au.radar.app

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import au.radar.core.Threat
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val THREAT_SOURCE = "threats"
private const val THREAT_LAYER = "threats-circles"

/**
 * The map. MapLibre rather than Mapbox: the tiles come from our own R2 bucket,
 * so there is no per-map-load bill and no vendor to ask permission from.
 */
@Composable
fun MapScreen(
    styleUrl: String,
    threats: List<Threat>,
    hasLocationPermission: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val holder = remember { MapHolder() }

    DisposableEffect(Unit) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            mapView.getMapAsync { map ->
                holder.map = map
                map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    holder.installThreatLayer(style)
                    holder.render(threats)
                    if (hasLocationPermission) holder.followUser(context, map, style)
                }
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(-33.8688, 151.2093))
                    .zoom(14.0)
                    .build()
            }
            mapView
        },
        update = { holder.render(threats) },
        modifier = modifier,
    )
}

private class MapHolder {
    var map: MapLibreMap? = null
    private var renderedIds: List<String> = emptyList()

    fun installThreatLayer(style: Style) {
        if (style.getSource(THREAT_SOURCE) != null) return

        style.addSource(GeoJsonSource(THREAT_SOURCE))
        style.addLayer(
            CircleLayer(THREAT_LAYER, THREAT_SOURCE).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                // Colour carries the meaning at a glance: cameras are the thing
                // you are looking for, everything else is graded by severity.
                PropertyFactory.circleColor(
                    Expression.match(
                        Expression.get("band"),
                        Expression.literal("#2F80ED"),
                        Expression.stop("camera", Expression.literal("#F2C94C")),
                        Expression.stop("critical", Expression.literal("#EB5757")),
                        Expression.stop("major", Expression.literal("#F2994A")),
                    ),
                ),
            ),
        )
    }

    fun render(threats: List<Threat>) {
        val style = map?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(THREAT_SOURCE) ?: return

        // Rebuilding the collection on every fix would thrash the renderer, so
        // only redraw when the set actually changed.
        val signature = threats.map { it.id }.sorted()
        if (signature == renderedIds) return
        renderedIds = signature

        val features = threats.map { threat ->
            Feature.fromGeometry(Point.fromLngLat(threat.lon, threat.lat)).apply {
                addStringProperty("band", band(threat))
                addStringProperty("title", au.radar.core.AlertEngine.label(threat))
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun band(threat: Threat): String = when {
        threat.isCamera -> "camera"
        threat.severity >= 3 -> "critical"
        threat.severity == 2 -> "major"
        else -> "minor"
    }

    @SuppressLint("MissingPermission")
    fun followUser(context: android.content.Context, map: MapLibreMap, style: Style) {
        map.locationComponent.apply {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style).build(),
            )
            isLocationComponentEnabled = true
            // Tracking the compass bearing keeps the road ahead in the upper
            // part of the screen, which is what you want at speed.
            cameraMode = CameraMode.TRACKING_GPS
            renderMode = RenderMode.GPS
        }
    }
}
