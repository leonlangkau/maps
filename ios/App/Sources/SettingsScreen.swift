import RadarKit
import SwiftUI

/// The whole settings surface.
///
/// Organised by what the driver cares about rather than by data model: the two
/// things people install this for sit at the top, cameras next, road conditions
/// after that, and the noisy stuff they will probably want quiet is grouped
/// under a heading that says so.
struct SettingsScreen: View {
    @ObservedObject var model: DriveModel
    @Environment(\.dismiss) private var dismiss

    private var settings: AlertSettings { model.settings }

    /// The kind rows grouped for display, computed once rather than tracked
    /// while the list scrolls.
    private var groups: [(name: String, kinds: [(kind: String, title: String)])] {
        var ordered: [(String, [(String, String)])] = []
        for entry in AlertSettings.editableKinds {
            if let last = ordered.last, last.0 == entry.group {
                ordered[ordered.count - 1].1.append((entry.kind, entry.title))
            } else {
                ordered.append((entry.group, [(entry.kind, entry.title)]))
            }
        }
        return ordered.map { (name: $0.0, kinds: $0.1.map { (kind: $0.0, title: $0.1) }) }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Overall") {
                    Toggle("Spoken warnings", isOn: Binding(
                        get: { !settings.muted },
                        set: { var next = settings; next.muted = !$0; model.update(settings: next) }
                    ))

                    Toggle("Flash the screen", isOn: Binding(
                        get: { settings.flashEnabled },
                        set: { var next = settings; next.flashEnabled = $0; model.update(settings: next) }
                    ))

                    LabelledSlider(
                        title: "Extra warning on my own road",
                        value: Binding(
                            get: { settings.sameRoadLeadMultiplier },
                            set: { var n = settings; n.sameRoadLeadMultiplier = $0; model.update(settings: n) }
                        ),
                        range: 1...2.5,
                        format: { "\(Int(($0 * 100).rounded()))%" }
                    )
                    Text(
                        "Something on the road you are already on is warned about this much "
                        + "earlier than something merely in front of you."
                    )
                    .font(.caption2)
                    .foregroundStyle(.secondary)

                    LabelledSlider(
                        title: "Stay quiet below",
                        value: Binding(
                            get: { settings.minSpeedKmh },
                            set: { var n = settings; n.minSpeedKmh = $0; model.update(settings: n) }
                        ),
                        range: 0...60,
                        format: { $0 < 1 ? "Off" : "\(Int($0.rounded())) km/h" }
                    )
                }

                ForEach(groups, id: \.name) { group in
                    Section(group.name) {
                        ForEach(group.kinds, id: \.kind) { entry in
                            KindEditor(
                                title: entry.title,
                                kind: settings.forKind(entry.kind),
                                onChange: { updated in
                                    var next = settings
                                    next.kinds[entry.kind] = updated
                                    model.update(settings: next)
                                }
                            )
                        }
                    }
                }

                Section {
                    Button {
                        model.plantTestCamera()
                        dismiss()
                    } label: {
                        Label("Plant a test camera 600 m ahead", systemImage: "camera.badge.ellipsis")
                    }
                    if !model.testThreats.isEmpty {
                        Button(role: .destructive) {
                            model.clearTestCameras()
                        } label: {
                            Label("Remove the test camera", systemImage: "trash")
                        }
                    }
                } header: {
                    Text("Testing")
                } footer: {
                    Text(
                        "Drops a pretend speed camera on the road ahead so you can hear "
                        + "and see a real warning without a backend. Drive towards it."
                    )
                }

                Section("This phone") {
                    LabeledContent("Cameras stored", value: "\(model.cameras.count)")
                    LabeledContent(
                        "Live hazards",
                        value: model.connected ? "Connected" : "Offline"
                    )
                }

                Section {
                    Text(
                        "Camera locations come from state government open data. Live hazards "
                        + "come from the road authorities. This app never detects or interferes "
                        + "with any enforcement signal."
                    )
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Alerts")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

/// One threat kind. Three controls, because those are the three questions a
/// driver actually has: do I want it, how loud, and how early.
private struct KindEditor: View {
    let title: String
    let kind: KindSettings
    let onChange: (KindSettings) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Toggle(title, isOn: Binding(
                get: { kind.enabled },
                set: { var next = kind; next.enabled = $0; onChange(next) }
            ))
            .font(.body.weight(.medium))

            if kind.enabled {
                HStack(spacing: 8) {
                    OptionChip(
                        title: "Voice",
                        systemImage: "speaker.wave.2.fill",
                        isOn: kind.voice
                    ) {
                        var next = kind; next.voice.toggle(); onChange(next)
                    }
                    OptionChip(
                        title: "Flash",
                        systemImage: "bolt.fill",
                        isOn: kind.flash
                    ) {
                        var next = kind; next.flash.toggle(); onChange(next)
                    }
                }

                LabelledSlider(
                    title: "Warn me",
                    value: Binding(
                        get: { kind.leadSeconds },
                        set: { var next = kind; next.leadSeconds = $0; onChange(next) }
                    ),
                    range: 5...90,
                    format: { "\(Int($0.rounded())) s ahead" }
                )

                LabelledSlider(
                    title: "Or anywhere within",
                    value: Binding(
                        get: { kind.radiusM },
                        set: { var next = kind; next.radiusM = $0; onChange(next) }
                    ),
                    range: 100...1500,
                    format: { "\(Int(($0 / 50).rounded()) * 50) m" }
                )
            }
        }
        .padding(.vertical, 4)
    }
}

private struct OptionChip: View {
    let title: String
    let systemImage: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.caption)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .foregroundStyle(isOn ? .white : .secondary)
        .radarGlassCapsule(tint: isOn ? RadarGlass.route : nil)
    }
}

private struct LabelledSlider: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let format: (Double) -> String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(title)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(format(value))
                    .font(.subheadline.weight(.semibold))
                    .monospacedDigit()
            }
            Slider(value: $value, in: range)
        }
    }
}
