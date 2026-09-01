import MapLibre
import RadarKit
import SwiftUI

private let threatSource = "threats"
private let threatLayer = "threats-circles"
private let routeSource = "route"
private let routeCasingLayer = "route-casing"
private let routeLineLayer = "route-line"

/// The map. MapLibre rather than Mapbox: the tiles come from our own R2 bucket,
/// so there is no per-map-load bill and no vendor to ask permission from.
struct MapLibreMapView: UIViewRepresentable {
    let styleUrl: URL?
    let threats: [Threat]
    let routeGeometry: [RoutePoint]
    let followsUser: Bool
    let onThreatTapped: (String) -> Void

    func makeUIView(context: Context) -> MLNMapView {
        let mapView = MLNMapView(frame: .zero, styleURL: styleUrl)
        mapView.delegate = context.coordinator
        mapView.showsUserLocation = true
        mapView.attributionButton.isHidden = false
        mapView.compassView.isHidden = true
        // Course tracking keeps the road ahead in the upper part of the screen,
        // which is what you want at speed.
        mapView.userTrackingMode = followsUser ? .followWithCourse : .none
        mapView.setZoomLevel(15, animated: false)

        let tap = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleTap(_:))
        )
        // Let the map's own gestures win first; only unclaimed taps reach us.
        for recognizer in mapView.gestureRecognizers ?? [] {
            if let double = recognizer as? UITapGestureRecognizer,
               double.numberOfTapsRequired == 2 {
                tap.require(toFail: double)
            }
        }
        mapView.addGestureRecognizer(tap)

        context.coordinator.mapView = mapView
        context.coordinator.onThreatTapped = onThreatTapped
        return mapView
    }

    func updateUIView(_ mapView: MLNMapView, context: Context) {
        if mapView.styleURL != styleUrl, let styleUrl {
            mapView.styleURL = styleUrl
        }
        mapView.userTrackingMode = followsUser ? .followWithCourse : .none
        context.coordinator.onThreatTapped = onThreatTapped
        context.coordinator.render(threats: threats, on: mapView)
        context.coordinator.render(route: routeGeometry, on: mapView)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        weak var mapView: MLNMapView?
        var onThreatTapped: ((String) -> Void)?

        private var renderedThreatIds: [String] = []
        private var renderedRouteCount = -1

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            installLayers(on: style)
        }

        private func installLayers(on style: MLNStyle) {
            if style.source(withIdentifier: routeSource) == nil {
                let source = MLNShapeSource(identifier: routeSource, shape: nil, options: nil)
                style.addSource(source)

                // A casing under the line is what makes a route readable against
                // a busy map: the dark outline separates it from the road.
                let casing = MLNLineStyleLayer(identifier: routeCasingLayer, source: source)
                casing.lineColor = NSExpression(
                    forConstantValue: UIColor(red: 0.04, green: 0.24, blue: 0.39, alpha: 1)
                )
                casing.lineWidth = NSExpression(forConstantValue: 11)
                casing.lineCap = NSExpression(forConstantValue: "round")
                casing.lineJoin = NSExpression(forConstantValue: "round")
                style.addLayer(casing)

                let line = MLNLineStyleLayer(identifier: routeLineLayer, source: source)
                line.lineColor = NSExpression(
                    forConstantValue: UIColor(red: 0.29, green: 0.66, blue: 1.0, alpha: 1)
                )
                line.lineWidth = NSExpression(forConstantValue: 6.5)
                line.lineCap = NSExpression(forConstantValue: "round")
                line.lineJoin = NSExpression(forConstantValue: "round")
                style.addLayer(line)
            }

            // Threats go above the route so a camera on the line stays tappable.
            if style.source(withIdentifier: threatSource) == nil {
                let source = MLNShapeSource(identifier: threatSource, shape: nil, options: nil)
                style.addSource(source)

                let layer = MLNCircleStyleLayer(identifier: threatLayer, source: source)
                // Markers grow with zoom so they stay hittable close in without
                // swamping the map when zoomed out.
                layer.circleRadius = NSExpression(
                    format: "mgl_interpolate:withCurveType:parameters:stops:($zoomLevel, 'linear', nil, %@)",
                    [8: 4, 14: 8, 17: 12]
                )
                layer.circleStrokeWidth = NSExpression(forConstantValue: 2)
                layer.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
                // Colour carries the meaning at a glance: cameras are the thing
                // you are looking for, everything else is graded by severity.
                layer.circleColor = NSExpression(
                    forMLNMatchingKey: NSExpression(forKeyPath: "band"),
                    in: [
                        NSExpression(forConstantValue: "camera"):
                            NSExpression(forConstantValue: UIColor.systemYellow),
                        NSExpression(forConstantValue: "critical"):
                            NSExpression(forConstantValue: UIColor.systemRed),
                        NSExpression(forConstantValue: "major"):
                            NSExpression(forConstantValue: UIColor.systemOrange),
                    ],
                    default: NSExpression(forConstantValue: UIColor.systemBlue)
                )
                // Unconfirmed community reports read as faint on purpose.
                layer.circleOpacity = NSExpression(
                    forMLNMatchingKey: NSExpression(forKeyPath: "trust"),
                    in: [
                        NSExpression(forConstantValue: "low"):
                            NSExpression(forConstantValue: 0.55),
                    ],
                    default: NSExpression(forConstantValue: 1.0)
                )
                style.addLayer(layer)
            }
        }

        func render(threats: [Threat], on mapView: MLNMapView) {
            guard let style = mapView.style,
                  let source = style.source(withIdentifier: threatSource) as? MLNShapeSource
            else { return }

            // Rebuilding the shape collection on every fix would thrash the
            // renderer, so only redraw when the set actually changed.
            let signature = threats.map(\.id).sorted()
            guard signature != renderedThreatIds else { return }
            renderedThreatIds = signature

            let features: [MLNPointFeature] = threats.map { threat in
                let feature = MLNPointFeature()
                feature.coordinate = CLLocationCoordinate2D(
                    latitude: threat.lat, longitude: threat.lon
                )
                feature.attributes = [
                    "id": threat.id,
                    "band": band(for: threat),
                    "trust": threat.confidence < 0.5 ? "low" : "high",
                ]
                return feature
            }
            source.shape = MLNShapeCollectionFeature(shapes: features)
        }

        func render(route: [RoutePoint], on mapView: MLNMapView) {
            guard let style = mapView.style,
                  let source = style.source(withIdentifier: routeSource) as? MLNShapeSource
            else { return }
            guard route.count != renderedRouteCount else { return }
            renderedRouteCount = route.count

            guard route.count >= 2 else {
                source.shape = nil
                return
            }

            var coordinates = route.map {
                CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
            }
            source.shape = MLNPolylineFeature(
                coordinates: &coordinates, count: UInt(coordinates.count)
            )
        }

        private func band(for threat: Threat) -> String {
            if threat.isCamera { return "camera" }
            switch threat.severity {
            case 3: return "critical"
            case 2: return "major"
            default: return "minor"
            }
        }

        @objc func handleTap(_ recognizer: UITapGestureRecognizer) {
            guard let mapView else { return }
            let point = recognizer.location(in: mapView)

            // A finger is much wider than a marker, so search a small box around
            // the tap rather than the single point under it.
            let slop: CGFloat = 22
            let rect = CGRect(
                x: point.x - slop, y: point.y - slop,
                width: slop * 2, height: slop * 2
            )
            let features = mapView.visibleFeatures(
                in: rect, styleLayerIdentifiers: [threatLayer]
            )
            if let id = features.first?.attribute(forKey: "id") as? String {
                onThreatTapped?(id)
            }
        }
    }
}
