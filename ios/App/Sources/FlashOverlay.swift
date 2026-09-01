import SwiftUI

/// Pulses the screen edges for warnings worth not missing.
///
/// Two deliberate choices. It flashes the *edges* rather than the whole screen,
/// because a full-screen white-out at night ruins your night vision and hides
/// the map at the exact moment you want to look at it. And it keeps going when
/// the app is muted, since someone driving with the radio up is precisely the
/// person who needs the visual.
///
/// `flashAt` is a timestamp rather than a boolean so two warnings in quick
/// succession both produce a pulse.
struct FlashOverlay: View {
    let flashAt: Int64
    var colour: Color = RadarGlass.warning

    @State private var intensity: Double = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        RadialGradient(
            stops: [
                .init(color: .clear, location: 0.45),
                .init(color: colour.opacity(intensity), location: 1.0),
            ],
            center: .center,
            startRadius: 0,
            endRadius: 700
        )
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .onChange(of: flashAt) { _, newValue in
            guard newValue != 0 else { return }
            pulse()
        }
    }

    private func pulse() {
        // Someone who has asked for reduced motion is telling us they do not
        // want things moving; hold one steady glow instead of pulsing.
        if reduceMotion {
            intensity = 0.5
            withAnimation(.easeOut(duration: 1.2)) { intensity = 0 }
            return
        }

        Task { @MainActor in
            for _ in 0..<3 {
                withAnimation(.easeIn(duration: 0.11)) { intensity = 0.62 }
                try? await Task.sleep(nanoseconds: 110_000_000)
                withAnimation(.easeOut(duration: 0.19)) { intensity = 0 }
                try? await Task.sleep(nanoseconds: 190_000_000)
            }
        }
    }
}
