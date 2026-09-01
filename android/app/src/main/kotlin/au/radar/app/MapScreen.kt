package au.radar.app

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import au.radar.core.AlertEngine
import au.radar.core.RouteOption
import au.radar.core.RoutePoint
import au.radar.core.RouteTracker
import au.radar.core.Threat
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val THREAT_SOURCE = "threats"
private const val THREAT_LAYER = "threats-circles"
private const val ROUTE_SOURCE = "route"
private const val ROUTE_CASING_LAYER = "route-casing"
private const val ROUTE_LINE_LAYER = "route-line"

/** Clear road, and the fallback for a provider with no traffic data. */
private const val ROUTE_CLEAR = "#4AA8FF"

/**
 * The map. MapLibre rather than Mapbox: the tiles come from our own R2 bucket,
 * so there is no per-map-load bill and no vendor to ask permission from.
 *
 * MapLibre runs in its default SurfaceView mode here, which is both the fastest
 * way to draw a continuously-animating map and the reason the glass chrome is
 * painted rather than blurred. See `ui/Glass.kt` for that reasoning.
 */
@Composable
fun MapScreen(
    styleUrl: String,
    threats: List<Threat>,
    routeGeometry: List<RoutePoint>,
    route: RouteOption?,
    followUser: Boolean,
    hasLocationPermission: Boolean,
    onThreatTapped: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val holder = remember { MapHolder() }
    val currentOnTap by rememberUpdatedState(onThreatTapped)

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
                map.uiSettings.isAttributionEnabled = true
                map.uiSettings.isLogoEnabled = true
                map.uiSettings.isCompassEnabled = false

                map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    holder.installLayers(style)
                    holder.renderThreats(threats)
                    holder.renderRoute(routeGeometry, route)
                    if (hasLocationPermission) holder.followUser(context, map, style)
                }

                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(-33.8688, 151.2093))
                    .zoom(15.0)
                    .build()

                map.addOnMapClickListener { point ->
                    val screenPoint = map.projection.toScreenLocation(point)
                    val hits = map.queryRenderedFeatures(screenPoint, THREAT_LAYER)
                    val id = hits.firstOrNull()?.getStringProperty("id")
                    if (id != null) {
                        currentOnTap(id)
                        true
                    } else {
                        false
                    }
                }
            }
            mapView
        },
        update = {
            holder.renderThreats(threats)
            holder.renderRoute(routeGeometry, route)
            holder.setFollowing(followUser)
        },
        modifier = modifier,
    )
}

private class MapHolder {
    var map: MapLibreMap? = null
    private var renderedThreatIds: List<String> = emptyList()
    private var renderedRouteSignature: Pair<Int, Double>? = null
    private var following = true

    fun installLayers(style: Style) {
        if (style.getSource(ROUTE_SOURCE) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE))

            // A casing under the line is what makes a route readable against a
            // busy map: the dark outline separates it from the road beneath.
            style.addLayer(
                LineLayer(ROUTE_CASING_LAYER, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#0B3D63"),
                    PropertyFactory.lineWidth(11f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(ROUTE_LINE_LAYER, ROUTE_SOURCE).withProperties(
                    // Traffic reads off the line itself, so the driver can see
                    // where the delay is rather than only that there is one.
                    PropertyFactory.lineColor(
                        Expression.match(
                            Expression.get("congestion"),
                            Expression.literal(ROUTE_CLEAR),
                            Expression.stop("moderate", Expression.literal("#F2C94C")),
                            Expression.stop("heavy", Expression.literal("#F2994A")),
                            Expression.stop("severe", Expression.literal("#EB5757")),
                        ),
                    ),
                    PropertyFactory.lineWidth(6.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
        }

        // Threats go above the route so a camera on the line stays tappable.
        if (style.getSource(THREAT_SOURCE) == null) {
            style.addSource(GeoJsonSource(THREAT_SOURCE))
            style.addLayer(
                CircleLayer(THREAT_LAYER, THREAT_SOURCE).withProperties(
                    // Markers grow with zoom so they stay hittable close in
                    // without swamping the map when zoomed out.
                    PropertyFactory.circleRadius(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(8, Expression.literal(4f)),
                            Expression.stop(14, Expression.literal(8f)),
                            Expression.stop(17, Expression.literal(12f)),
                        ),
                    ),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleColor(
                        Expression.match(
                            Expression.get("band"),
                            Expression.literal("#2F80ED"),
                            Expression.stop("camera", Expression.literal("#F2C94C")),
                            Expression.stop("critical", Expression.literal("#EB5757")),
                            Expression.stop("major", Expression.literal("#F2994A")),
                        ),
                    ),
                    // Unconfirmed community reports read as faint on purpose.
                    PropertyFactory.circleOpacity(
                        Expression.match(
                            Expression.get("trust"),
                            Expression.literal(1.0f),
                            Expression.stop("low", Expression.literal(0.55f)),
                        ),
                    ),
                ),
            )
        }
    }

    fun renderThreats(threats: List<Threat>) {
        val style = map?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(THREAT_SOURCE) ?: return

        // Rebuilding the collection on every fix would thrash the renderer, so
        // only redraw when the set actually changed.
        val signature = threats.map { it.id }.sorted()
        if (signature == renderedThreatIds) return
        renderedThreatIds = signature

        source.setGeoJson(
            FeatureCollection.fromFeatures(
                threats.map { threat ->
                    Feature.fromGeometry(Point.fromLngLat(threat.lon, threat.lat)).apply {
                        addStringProperty("id", threat.id)
                        addStringProperty("band", band(threat))
                        addStringProperty("trust", if (threat.confidence < 0.5) "low" else "high")
                        addStringProperty("title", AlertEngine.label(threat))
                    }
                },
            ),
        )
    }

    fun renderRoute(geometry: List<RoutePoint>, route: RouteOption?) {
        val style = map?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return

        // Redraw when the shape changes or when the traffic reading does — the
        // geometry can stay identical across a refresh while the colours move.
        val signature = geometry.size to (route?.durationS ?: 0.0)
        if (signature == renderedRouteSignature) return
        renderedRouteSignature = signature

        if (geometry.size < 2) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }

        // One feature per run of equal traffic, sharing boundary points so the
        // line has no gaps where the colour changes.
        val congestion = route?.legs?.flatMap { it.congestion } ?: emptyList()
        val features = RouteTracker.congestionSpans(geometry, congestion).map { span ->
            Feature.fromGeometry(
                LineString.fromLngLats(span.points.map { Point.fromLngLat(it.lon, it.lat) }),
            ).apply { addStringProperty("congestion", span.level) }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Frame a whole route on screen, for the preview before setting off. */
    fun fitRoute(geometry: List<RoutePoint>) {
        val map = map ?: return
        if (geometry.size < 2) return
        val bounds = LatLngBounds.Builder()
            .includes(geometry.map { LatLng(it.lat, it.lon) })
            .build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120), 600)
    }

    fun setFollowing(follow: Boolean) {
        if (follow == following) return
        following = follow
        val map = map ?: return
        runCatching {
            map.locationComponent.cameraMode =
                if (follow) CameraMode.TRACKING_GPS else CameraMode.NONE
        }
    }

    private fun band(threat: Threat): String = when {
        threat.isCamera -> "camera"
        threat.severity >= 3 -> "critical"
        threat.severity == 2 -> "major"
        else -> "minor"
    }

    @SuppressLint("MissingPermission")
    fun followUser(context: Context, map: MapLibreMap, style: Style) {
        map.locationComponent.apply {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style).build(),
            )
            isLocationComponentEnabled = true
            // Tracking the GPS bearing keeps the road ahead in the upper part
            // of the screen, which is what you want at speed.
            cameraMode = CameraMode.TRACKING_GPS
            renderMode = RenderMode.GPS
        }
    }
}
