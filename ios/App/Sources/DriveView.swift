import RadarKit
import SwiftUI

/// The only screen that matters while driving: map, current warning, and a way
/// to report what you just passed without taking your eyes off the road for
/// more than a moment.
struct DriveView: View {
    @StateObject private var model = DriveModel()
    @State private var showingReportSheet = false

    var body: some View {
        ZStack(alignment: .top) {
            MapLibreMapView(
                styleUrl: model.styleUrl,
                threats: model.cameras + model.hazards,
                followsUser: true
            )
            .ignoresSafeArea()

            VStack(spacing: 12) {
                if let announcement = model.lastAnnouncement {
                    WarningBanner(announcement: announcement)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }

                if !model.connected {
                    OfflineBadge()
                }

                Spacer()

                HStack(alignment: .bottom) {
                    SpeedReadout(speedKmh: model.speedKmh)
                    Spacer()
                    ControlStack(
                        muted: $model.muted,
                        onReport: { showingReportSheet = true }
                    )
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 24)
            }
            .animation(.easeOut(duration: 0.2), value: model.lastAnnouncement?.threatId)
        }
        .sheet(isPresented: $showingReportSheet) {
            ReportSheet { kind in
                showingReportSheet = false
                Task { await model.report(kind: kind) }
            }
            .presentationDetents([.height(280)])
        }
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }
}

private struct WarningBanner: View {
    let announcement: Announcement

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: announcement.level == .speak ? "exclamationmark.triangle.fill" : "bell.fill")
                .font(.title2)
            Text(announcement.spokenText)
                .font(.title3.weight(.semibold))
                .lineLimit(2)
            Spacer()
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }
}

private struct OfflineBadge: View {
    var body: some View {
        Label("Offline — cameras only", systemImage: "wifi.slash")
            .font(.footnote.weight(.medium))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.thinMaterial, in: Capsule())
    }
}

private struct SpeedReadout: View {
    let speedKmh: Double

    var body: some View {
        VStack(spacing: 0) {
            Text("\(Int(speedKmh.rounded()))")
                .font(.system(size: 44, weight: .bold, design: .rounded))
                .monospacedDigit()
            Text("km/h")
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct ControlStack: View {
    @Binding var muted: Bool
    let onReport: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            Button {
                muted.toggle()
            } label: {
                Image(systemName: muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .font(.title2)
                    .frame(width: 56, height: 56)
                    .background(.regularMaterial, in: Circle())
            }
            .accessibilityLabel(muted ? "Unmute warnings" : "Mute warnings")

            Button(action: onReport) {
                Image(systemName: "plus")
                    .font(.title.weight(.semibold))
                    .frame(width: 68, height: 68)
                    .background(Color.accentColor, in: Circle())
                    .foregroundStyle(.white)
            }
            .accessibilityLabel("Report something ahead")
        }
    }
}

/// Deliberately six large buttons and nothing else. Anything that needs reading
/// does not belong on a screen used at 100 km/h.
private struct ReportSheet: View {
    let onPick: (String) -> Void

    private let options: [(kind: String, label: String, icon: String)] = [
        ("police", "Police", "shield.fill"),
        ("mobile_camera", "Mobile camera", "camera.fill"),
        ("crash", "Crash", "car.fill"),
        ("hazard", "Hazard", "exclamationmark.triangle.fill"),
        ("object_on_road", "Object on road", "shippingbox.fill"),
        ("stopped_vehicle", "Stopped vehicle", "car.side.fill"),
    ]

    var body: some View {
        VStack(spacing: 16) {
            Text("What did you just pass?")
                .font(.headline)
                .padding(.top, 20)

            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 12) {
                ForEach(options, id: \.kind) { option in
                    Button {
                        onPick(option.kind)
                    } label: {
                        VStack(spacing: 8) {
                            Image(systemName: option.icon).font(.title2)
                            Text(option.label)
                                .font(.caption)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 84)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)

            Spacer(minLength: 0)
        }
    }
}
