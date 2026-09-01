import RadarKit
import SwiftUI

// MARK: - Search

struct SearchSheet: View {
    @ObservedObject var model: DriveModel
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.white.opacity(0.7))

                TextField(
                    "",
                    text: Binding(
                        get: { model.searchQuery },
                        set: { model.onSearchQueryChanged($0) }
                    ),
                    prompt: Text("Search an address").foregroundStyle(.white.opacity(0.45))
                )
                .font(.system(size: 19))
                .foregroundStyle(.white)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($focused)

                if model.searching {
                    ProgressView().controlSize(.small)
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .radarGlassCapsule(interactive: false)
            .padding(20)

            if model.searchResults.isEmpty, model.searchQuery.count >= 3, !model.searching {
                Text("Nothing found")
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.5))
                    .padding(.horizontal, 24)
            }

            List(model.searchResults) { place in
                Button {
                    model.pickDestination(place)
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(place.name)
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(.white)
                        if let address = place.address {
                            Text(address)
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.55))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .listRowBackground(Color.clear)
                .listRowSeparatorTint(.white.opacity(0.12))
            }
            // The list is content, not chrome, so it gets no glass of its own —
            // glass on a scrolling list is the fastest way to make it unreadable.
            .listStyle(.plain)
            .scrollContentBackground(.hidden)

            Spacer(minLength: 0)
        }
        .onAppear { focused = true }
    }
}

// MARK: - Route preview

struct RoutePreviewSheet: View {
    @ObservedObject var model: DriveModel

    private var fastest: Double {
        model.routeChoices.map(\.option.durationS).min() ?? 0
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(model.destination?.name ?? "Building a route…")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)

            if let address = model.destination?.address {
                Text(address)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.55))
                    .padding(.top, 4)
            }

            Spacer().frame(height: 16)

            if model.routeChoices.isEmpty {
                HStack(spacing: 12) {
                    ProgressView().controlSize(.small)
                    Text("Working out the way there")
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.7))
                }
            } else {
                GlassGroup(spacing: 10) {
                    VStack(spacing: 8) {
                        ForEach(model.routeChoices) { choice in
                            RouteChoiceRow(
                                choice: choice,
                                isSelected: choice.id == model.selectedRoute,
                                extraSeconds: choice.option.durationS - fastest
                            ) {
                                model.selectRoute(choice.id)
                            }
                        }
                    }
                }
            }

            Spacer(minLength: 16)

            GlassGroup(spacing: 12) {
                HStack(spacing: 12) {
                    GlassActionButton(title: "Cancel") { model.endNavigation() }
                    GlassActionButton(
                        title: "Start",
                        systemImage: "location.fill",
                        tint: RadarGlass.route,
                        enabled: !model.routeChoices.isEmpty
                    ) {
                        model.startNavigation()
                    }
                }
            }
        }
        .padding(24)
    }
}

/// One alternative.
///
/// The camera and hazard count is the reason this picker exists: comparing
/// routes on time alone throws away the one axis this app knows about, and
/// "three minutes longer, two fewer cameras" is a trade a driver can actually
/// make.
private struct RouteChoiceRow: View {
    let choice: RouteChoice
    let isSelected: Bool
    let extraSeconds: Double
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(alignment: .lastTextBaseline, spacing: 8) {
                        Text(RouteTracker.formatDuration(choice.option.durationS))
                            .font(.system(size: 20, weight: .bold, design: .rounded))
                            .monospacedDigit()
                        Text(RouteTracker.formatDistance(choice.option.distanceM))
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.6))
                        if extraSeconds >= 60 {
                            Text("+\(Int(extraSeconds / 60)) min")
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.5))
                        }
                    }
                    Text(choice.threatSummary ?? "Nothing on this one")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(
                            choice.threatSummary == nil
                                ? RadarGlass.affirm : RadarGlass.caution
                        )
                    if let traffic = RouteTracker.describeTraffic(choice.option) {
                        Text(traffic)
                            .font(.caption2)
                            .foregroundStyle(Color(red: 0.95, green: 0.6, blue: 0.29))
                    }
                }
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.headline)
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .radarGlassPanel(cornerRadius: 16, tint: isSelected ? RadarGlass.route : nil)
    }
}

// MARK: - Reporting

/// Deliberately six large buttons and nothing else. Anything that needs reading
/// does not belong on a screen used at 100 km/h.
struct ReportSheet: View {
    let onPick: (String) -> Void

    private struct Option: Identifiable {
        let id: String
        let label: String
        let icon: String
    }

    private let options = [
        Option(id: "police", label: "Police", icon: "shield.fill"),
        Option(id: "mobile_camera", label: "Mobile camera", icon: "camera.fill"),
        Option(id: "crash", label: "Crash", icon: "car.fill"),
        Option(id: "hazard", label: "Hazard", icon: "exclamationmark.triangle.fill"),
        Option(id: "object_on_road", label: "Object on road", icon: "shippingbox.fill"),
        Option(id: "stopped_vehicle", label: "Stopped vehicle", icon: "car.side.fill"),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("What did you just pass?")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)

            GlassGroup(spacing: 12) {
                LazyVGrid(
                    columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3),
                    spacing: 10
                ) {
                    ForEach(options) { option in
                        Button { onPick(option.id) } label: {
                            VStack(spacing: 8) {
                                Image(systemName: option.icon).font(.title2)
                                Text(option.label)
                                    .font(.caption)
                                    .multilineTextAlignment(.center)
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 88)
                            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.white)
                        .radarGlassPanel(cornerRadius: 18)
                    }
                }
            }

            Spacer(minLength: 0)
        }
        .padding(24)
    }
}

// MARK: - Threat detail

struct ThreatSheet: View {
    let threat: Threat
    @ObservedObject var model: DriveModel

    /// Only community reports can be voted on. A government feed is not
    /// something a driver confirms or denies.
    private var isCommunityReport: Bool { threat.id.hasPrefix("community:") }

    private var provenance: String {
        if threat.isCamera { return "From published camera data" }
        if isCommunityReport { return "Reported by a driver" }
        return "From a live traffic feed"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(AlertEngine.label(threat))
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)

            Text(provenance)
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.6))
                .padding(.top, 8)

            if let limit = AlertEngine.postedLimit(threat) {
                Text("Posted limit \(limit) km/h")
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.6))
                    .padding(.top, 6)
            }

            if isCommunityReport {
                Text("Is it still there?")
                    .font(.system(size: 15))
                    .foregroundStyle(.white)
                    .padding(.top, 22)

                GlassGroup(spacing: 12) {
                    HStack(spacing: 12) {
                        GlassActionButton(
                            title: "Still there",
                            systemImage: "checkmark",
                            tint: RadarGlass.affirm
                        ) {
                            Task { await model.vote(on: threat, confirm: true) }
                        }
                        GlassActionButton(
                            title: "Gone",
                            systemImage: "xmark",
                            tint: RadarGlass.warning
                        ) {
                            Task { await model.vote(on: threat, confirm: false) }
                        }
                    }
                }
                .padding(.top, 12)
            }

            Spacer(minLength: 0)
        }
        .padding(24)
    }
}
