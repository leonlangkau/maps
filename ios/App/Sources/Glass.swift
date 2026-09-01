import SwiftUI

/// Liquid Glass, with a floor under it.
///
/// On iOS 26 this is the real thing: `glassEffect` samples what is behind the
/// view, refracts it, and lights the edge, and `GlassEffectContainer` lets
/// nearby panes blend and morph into one another instead of being sampled
/// independently.
///
/// Below iOS 26 it falls back to `.ultraThinMaterial` with a hand-drawn rim.
/// That is not as good, but it is close enough that the layout, contrast and
/// hit targets are unchanged — which matters, because a warning app should not
/// look broken on a phone that has not been updated.
///
/// Three rules from Apple's own guidance are followed throughout, because
/// breaking them is what makes glass look cheap:
///
/// - Glass goes on controls and chrome, never on content. The map is content.
/// - Multiple panes live inside one container, so they sample consistently.
/// - Glass never sits directly on glass.
enum RadarGlass {
    /// A tint pulled through the pane, for severity colouring.
    static let warning = Color(red: 0.92, green: 0.34, blue: 0.34)
    static let caution = Color(red: 0.95, green: 0.81, blue: 0.30)
    static let route = Color(red: 0.29, green: 0.66, blue: 1.0)
    static let affirm = Color(red: 0.15, green: 0.68, blue: 0.38)
}

extension View {

    /// The glass treatment for a panel or control.
    @ViewBuilder
    func radarGlass<S: Shape>(
        in shape: S,
        tint: Color? = nil,
        interactive: Bool = false
    ) -> some View {
        if #available(iOS 26.0, *) {
            var glass = Glass.regular
            if let tint { glass = glass.tint(tint) }
            if interactive { glass = glass.interactive() }
            self.glassEffect(glass, in: shape)
        } else {
            self
                .background(.ultraThinMaterial, in: shape)
                .background(
                    (tint ?? .clear).opacity(0.22),
                    in: shape
                )
                .overlay {
                    // A rim that is bright where light enters and catches again
                    // faintly on the far edge, which is what sells a glass edge.
                    shape.stroke(
                        LinearGradient(
                            stops: [
                                .init(color: .white.opacity(0.45), location: 0.0),
                                .init(color: .white.opacity(0.10), location: 0.4),
                                .init(color: .white.opacity(0.20), location: 1.0),
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: 1
                    )
                }
                .shadow(color: .black.opacity(0.35), radius: 14, y: 6)
        }
    }

    /// A rounded-rectangle panel, the shape most of the chrome uses.
    func radarGlassPanel(cornerRadius: CGFloat = 22, tint: Color? = nil) -> some View {
        radarGlass(
            in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous),
            tint: tint
        )
    }

    /// A capsule, for pills and action buttons.
    func radarGlassCapsule(tint: Color? = nil, interactive: Bool = true) -> some View {
        radarGlass(in: Capsule(), tint: tint, interactive: interactive)
    }
}

/// Groups nearby glass panes so they sample the background consistently and can
/// morph into one another. Below iOS 26 it is a plain passthrough.
struct GlassGroup<Content: View>: View {
    var spacing: CGFloat = 18
    @ViewBuilder var content: Content

    var body: some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { content }
        } else {
            content
        }
    }
}

/// A round glass control, sized for a thumb on a bracket-mounted phone.
struct GlassCircleButton: View {
    let systemImage: String
    let label: String
    var size: CGFloat = 56
    var tint: Color?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: size * 0.36, weight: .semibold))
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .radarGlass(in: Circle(), tint: tint, interactive: true)
        .accessibilityLabel(label)
    }
}

/// A wide glass action button, for the bottom of a sheet.
struct GlassActionButton: View {
    let title: String
    var systemImage: String?
    var tint: Color?
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title).font(.headline)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .foregroundStyle(enabled ? .white : .white.opacity(0.4))
        .radarGlassCapsule(tint: tint)
        .disabled(!enabled)
    }
}
