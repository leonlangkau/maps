package au.radar.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import au.radar.core.Announcement
import au.radar.core.AnnouncementLevel
import java.util.Locale

/**
 * Speaks warnings over whatever the driver is listening to.
 *
 * The audio attributes are the fiddly part: warnings must duck music rather
 * than stop it, and must route to the car over Bluetooth and Android Auto.
 * USAGE_ASSISTANCE_NAVIGATION_GUIDANCE is what tells the system this is a
 * navigation prompt and should be treated like one.
 */
class AlertVoice(context: Context) {

    private var ready = false
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

    private val engine = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            // Australian English first so road names and "kilometres" land right.
            val result = tts.setLanguage(Locale("en", "AU"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.language = Locale.UK
            }
            // Slightly faster than default: a warning that takes three seconds
            // to deliver at 100 km/h has already cost eighty metres.
            tts.setSpeechRate(1.05f)
        }
    }

    private val tts: TextToSpeech get() = engine

    init {
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    fun announce(announcement: Announcement) {
        when (announcement.level) {
            AnnouncementLevel.CHIME -> tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            AnnouncementLevel.SPEAK -> speak(announcement.spokenText)
        }
    }

    private fun speak(text: String) {
        if (!ready) return
        // QUEUE_FLUSH, not QUEUE_ADD: a backlog of warnings is worse than none,
        // because the one being read is no longer the one you are approaching.
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }

    /**
     * Turn-by-turn instructions, which bypass the alert engine's suppression:
     * a maneuver is timed to the road, not rationed like a hazard warning.
     */
    fun speakNavigation(text: String) {
        speak(text)
    }

    fun release() {
        tts.stop()
        tts.shutdown()
        tone.release()
    }
}
