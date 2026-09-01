import SwiftUI

@main
struct RadarAUApp: App {
    var body: some Scene {
        WindowGroup {
            DriveView()
                // A driving app that dims and locks mid-trip is useless in a
                // cradle. DriveView carries the dark scheme itself.
                .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
                .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        }
    }
}
