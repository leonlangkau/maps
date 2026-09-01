import MapLibre
import RadarKit
import SwiftUI

/// The map. MapLibre rather than Mapbox: the tiles come from our own R2 bucket,
/// so there is no per-map-load bill and no vendor to ask permission from.
struct MapLibreMapView: UIViewRepresentable {
    let styleUrl: URL?
    let threats: [Threat]
    let followsUser: Bool

    func makeUIView(context: Context) -> MLNMapView {
        let mapView = MLNMapView(frame: .zero, styleURL: styleUrl)
        mapView.delegate = context.coordinator
        mapView.showsUserLocation = true
        mapView.logoView.isHidden = false
        mapView.attributionButton.isHidden = false
        // Course-tracking keeps the road ahead in the upper part of the screen,
        // which is what you want at speed.
        mapView.userTrackingMode = followsUser ? .followWithCourse : .none
        mapView.setZoomLevel(15, animated: false)
        return mapView
    }

    func updateUIView(_ mapView: MLNMapView, context: Context) {
        if mapView.styleURL != styleUrl, let styleUrl {
            mapView.styleURL = styleUrl
        }
        mapView.userTrackingMode = followsUser ? .followWithCourse : .none
        context.coordinator.render(threats: threats, on: mapView)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        private var sourceId = "threats"
        private var rendered: [String] = []

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            install(on: style)
        }

        private func install(on style: MLNStyle) {
            guard style.source(withIdentifier: sourceId) == nil else { return }

            let source = MLNShapeSource(identifier: sourceId, shape: nil, options: nil)
            style.addSource(source)

            let layer = MLNCircleStyleLayer(identifier: "threats-circles", source: source)
            layer.circleRadius = NSExpression(forConstantValue: 7)
            layer.circleStrokeWidth = NSExpression(forConstantValue: 2)
            layer.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            // Colour carries the meaning at a glance: cameras are the thing you
            // are looking for, everything else is graded by how bad it is.
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
            style.addLayer(layer)
        }

        func render(threats: [Threat], on mapView: MLNMapView) {
            guard let style = mapView.style,
                  let source = style.source(withIdentifier: sourceId) as? MLNShapeSource
            else { return }

            // Rebuilding the shape collection on every fix would thrash the
            // renderer, so only redraw when the set actually changed.
            let signature = threats.map(\.id).sorted()
            guard signature != rendered else { return }
            rendered = signature

            let features: [MLNPointFeature] = threats.map { threat in
                let feature = MLNPointFeature()
                feature.coordinate = CLLocationCoordinate2D(
                    latitude: threat.lat, longitude: threat.lon
                )
                feature.identifier = threat.id
                feature.attributes = [
                    "band": band(for: threat),
                    "title": AlertEngine.label(threat),
                ]
                return feature
            }
            source.shape = MLNShapeCollectionFeature(shapes: features)
        }

        private func band(for threat: Threat) -> String {
            if threat.isCamera { return "camera" }
            switch threat.severity {
            case 3: return "critical"
            case 2: return "major"
            default: return "minor"
            }
        }
    }
}
