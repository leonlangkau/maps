import RadarKit
import SwiftUI

/// The only screen that matters while driving: the map, whatever is being
/// warned about, and a way to report what you just passed without looking down
/// for more than a moment.
///
/// Everything floats over a full-bleed map as glass. Nothing is more than one
/// tap deep, and every control is at least 56 points because it will be used by
/// someone whose attention is on the road.
struct DriveView: View {
    @StateObject private var model = DriveModel()
    @State private var showingReport = false
    @State private var showingSettings = false
    @Namespace private var glassNamespace

    var body: some View {
        ZStack {
            MapLibreMapView(
                styleUrl: model.styleUrl,
                threats: model.threats,
                routeGeometry: model.routeGeometry,
                followsUser: model.navMode != .previewing,
                onThreatTapped: { id in
                    model.selectedThreat = model.threats.first { $0.id == id }
                }
            )
            .ignoresSafeArea()

            VStack(spacing: 10) {
                topChrome
                Spacer(minLength: 0)
                bottomChrome
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .animation(.smooth(duration: 0.28), value: model.lastAnnouncement?.threatId)
            .animation(.smooth(duration: 0.28), value: model.navMode)

            // Above everything, including any sheet: a warning worth flashing
            // for is worth seeing over whatever else is open.
            FlashOverlay(flashAt: model.flashAt)
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: .init(
            get: { model.navMode == .searching },
            set: { if !$0 { model.closeSearch() } }
        )) {
            SearchSheet(model: model)
                .presentationDetents([.medium, .large])
                .presentationBackground(.thinMaterial)
        }
        .sheet(isPresented: .init(
            get: { model.navMode == .previewing },
            set: { if !$0 { model.endNavigation() } }
        )) {
            RoutePreviewSheet(model: model)
                .presentationDetents([.medium, .large])
                .presentationBackground(.thinMaterial)
        }
        .sheet(item: $model.selectedThreat) { threat in
            ThreatSheet(threat: threat, model: model)
                .presentationDetents([.height(300)])
                .presentationBackground(.thinMaterial)
        }
        .sheet(isPresented: $showingReport) {
            ReportSheet { kind in
                showingReport = false
                Task { await model.report(kind: kind) }
            }
            .presentationDetents([.height(320)])
            .presentationBackground(.thinMaterial)
        }
        .sheet(isPresented: $showingSettings) {
            SettingsScreen(model: model)
        }
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }

    // MARK: - Chrome

    /// Panes in one container so they sample the map consistently and can morph
    /// between the search pill and the live maneuver card.
    private var topChrome: some View {
        GlassGroup(spacing: 14) {
            VStack(spacing: 10) {
                if model.navMode == .navigating, let progress = model.progress {
                    ManeuverCard(progress: progress)
                        .glassMorphID("top", in: glassNamespace)
                } else if model.navMode == .idle {
                    SearchPill { model.openSearch() }
                        .glassMorphID("top", in: glassNamespace)
                }

                if let announcement = model.lastAnnouncement {
                    WarningBanner(announcement: announcement)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }

                if !model.connected {
                    StatusChip(text: "Offline — cameras only", systemImage: "wifi.slash")
                }

                if let toast = model.toast {
                    StatusChip(text: toast, systemImage: "checkmark.circle")
                }
            }
        }
    }

    private var bottomChrome: some View {
        GlassGroup(spacing: 16) {
            VStack(spacing: 14) {
                HStack(alignment: .bottom) {
                    SpeedReadout(speedKmh: model.speedKmh, postedLimit: model.postedLimit)
                    Spacer()
                    controlStack
                }

                if model.navMode == .navigating {
                    EtaStrip(
                        progress: model.progress,
                        onEnd: { model.endNavigation() }
                    )
                }
            }
        }
    }

    private var controlStack: some View {
        VStack(spacing: 12) {
            GlassCircleButton(
                systemImage: "gearshape.fill",
                label: "Settings",
                size: 48
            ) { showingSettings = true }

            GlassCircleButton(
                systemImage: model.muted ? "speaker.slash.fill" : "speaker.wave.2.fill",
                label: model.muted ? "Unmute warnings" : "Mute warnings",
                size: 56
            ) { model.toggleMute() }

            GlassCircleButton(
                systemImage: "plus",
                label: "Report something ahead",
                size: 68,
                tint: RadarGlass.route
            ) { showingReport = true }
        }
    }
}

private extension View {
    /// Ties a pane to an identity so iOS 26 can morph it into whatever replaces
    /// it, rather than cross-fading two unrelated shapes.
    @ViewBuilder
    func glassMorphID(_ id: String, in namespace: Namespace.ID) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffectID(id, in: namespace)
        } else {
            self
        }
    }
}

// MARK: - Pieces

private struct SearchPill: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: "magnifyingglass")
                Text("Where to?")
                    .font(.system(size: 17))
                Spacer()
            }
            .foregroundStyle(.white.opacity(0.78))
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .radarGlassCapsule()
    }
}

private struct WarningBanner: View {
    let announcement: Announcement

    private var tint: Color {
        announcement.level == .speak ? RadarGlass.warning : RadarGlass.caution
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: announcement.level == .speak
                  ? "exclamationmark.triangle.fill" : "bell.fill")
                .font(.title2)
                .foregroundStyle(tint)
            Text(announcement.spokenText)
                .font(.title3.weight(.semibold))
                .lineLimit(2)
            Spacer(minLength: 0)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 18)
        .padding(.vertical, 15)
        .radarGlassPanel(cornerRadius: 20, tint: tint)
    }
}

private struct ManeuverCard: View {
    let progress: RouteProgress

    var body: some View {
        HStack(spacing: 16) {
            Text(RouteTracker.formatDistance(progress.distanceToManeuverM))
                .font(.system(size: 26, weight: .bold, design: .rounded))
                .foregroundStyle(RadarGlass.route)
                .monospacedDigit()

            Text(progress.currentStep?.instruction ?? "Continue")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(.white)
                .lineLimit(2)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .radarGlassPanel(cornerRadius: 20)
    }
}

private struct StatusChip: View {
    let text: String
    let systemImage: String

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: systemImage)
            Text(text)
        }
        .font(.footnote.weight(.medium))
        .foregroundStyle(.white.opacity(0.85))
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .radarGlassCapsule(interactive: false)
    }
}

/// Speed, and the posted limit where a camera told us one. The limit is shown
/// as information, never as a judgement — the app does not tell you off.
private struct SpeedReadout: View {
    let speedKmh: Double
    let postedLimit: Int?

    var body: some View {
        HStack(alignment: .bottom, spacing: 10) {
            VStack(spacing: 0) {
                Text("\(Int(speedKmh.rounded()))")
                    .font(.system(size: 42, weight: .bold, design: .rounded))
                    .monospacedDigit()
                Text("km/h")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.white.opacity(0.6))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .radarGlassPanel(cornerRadius: 20)

            if let postedLimit {
                Text("\(postedLimit)")
                    .font(.system(size: 20, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .frame(width: 54, height: 54)
                    .radarGlass(in: Circle(), tint: RadarGlass.warning)
            }
        }
    }
}

private struct EtaStrip: View {
    let progress: RouteProgress?
    let onEnd: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(RouteTracker.formatDuration(progress?.durationRemainingS ?? 0))
                    .font(.system(size: 22, weight: .bold, design: .rounded))
                    .monospacedDigit()
                Text(RouteTracker.formatDistance(progress?.distanceRemainingM ?? 0))
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.65))
            }
            .foregroundStyle(.white)

            Spacer()

            GlassCircleButton(
                systemImage: "xmark",
                label: "End navigation",
                size: 48,
                tint: RadarGlass.warning,
                action: onEnd
            )
        }
        .padding(.leading, 20)
        .padding(.trailing, 12)
        .padding(.vertical, 12)
        .radarGlassPanel(cornerRadius: 20)
    }
}
