import SwiftUI

@main
struct RadarAUApp: App {
    var body: some Scene {
        WindowGroup {
            DriveView()
                // A driving app that dims and locks mid-trip is useless in a
                // cradle, and the dark map is what you want at night anyway.
                .preferredColorScheme(.dark)
                .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
                .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        }
    }
}
