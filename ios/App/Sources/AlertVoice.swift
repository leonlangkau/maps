import AVFoundation
import Foundation
import RadarKit

/// Speaks warnings over whatever the driver is listening to.
///
/// The audio session is the fiddly part: the app has to duck music rather than
/// stop it, has to work over CarPlay and Bluetooth, and must not steal the
/// session permanently when it has nothing to say.
final class AlertVoice {
    private let synthesizer = AVSpeechSynthesizer()
    private var sessionActive = false

    init() {
        configureSession()
    }

    private func configureSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(
                .playback,
                mode: .voicePrompt,
                options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers, .allowBluetooth]
            )
        } catch {
            // Without a session we simply stay silent rather than crash mid-drive.
        }
    }

    func announce(_ announcement: Announcement) {
        switch announcement.level {
        case .chime:
            playChime()
        case .speak:
            speak(announcement.spokenText)
        }
    }

    private func speak(_ text: String) {
        activateSession()
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "en-AU")
            ?? AVSpeechSynthesisVoice(language: "en-GB")
        // Slightly faster than default: a warning that takes three seconds to
        // deliver at 100 km/h has cost you eighty metres.
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 1.05
        utterance.postUtteranceDelay = 0.1
        synthesizer.speak(utterance)
    }

    private func playChime() {
        activateSession()
        // 1057 is the short system "tock". A distinct tone file can replace this
        // later; the point is that a chime is not a sentence.
        AudioServicesPlaySystemSound(1057)
    }

    private func activateSession() {
        guard !sessionActive else { return }
        try? AVAudioSession.sharedInstance().setActive(true)
        sessionActive = true
    }

    /// Hand the audio session back when the drive ends, so music un-ducks.
    func release() {
        guard sessionActive else { return }
        try? AVAudioSession.sharedInstance().setActive(
            false, options: .notifyOthersOnDeactivation
        )
        sessionActive = false
    }
}
