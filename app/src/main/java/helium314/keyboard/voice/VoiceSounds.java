// Voice typing start/stop indicator sounds.
//
// Neither the Desh keyboard nor the original SpeechNotes app plays these beeps in
// code — on both, the tones come from the platform's speech-recognition service
// (Google's recognizer beeps on each recognition cycle, including internal
// restarts, which is why the old behavior sounded random). To get the two
// *distinct, reliable* sounds Desh shows (start/listening + stop/closing), we
// play them ourselves, strictly tied to the actual engine listening state
// (LatinIME.onVoiceEngineStateChanged), not to the mic tap.
package helium314.keyboard.voice;

import android.media.AudioManager;
import android.media.ToneGenerator;

/** Plays the two voice indicator tones. Instances are intentionally not released:
 *  the IME lives for the process lifetime, and releasing right after startTone()
 *  can cut the tone short. */
public final class VoiceSounds {

    private static final int STREAM = AudioManager.STREAM_SYSTEM;
    private static final int VOLUME = 70; // 0..100
    private static final int DURATION_MS = 160;

    private static volatile ToneGenerator toneGenerator;

    private VoiceSounds() {
    }

    /** "Listening" tone — played once when the engine confirms the listening state. */
    public static void playListeningStart() {
        play(ToneGenerator.TONE_PROP_BEEP);
    }

    /** "Stop/closing" tone — played once when the voice session actually stops. */
    public static void playListeningStop() {
        play(ToneGenerator.TONE_PROP_BEEP2);
    }

    private static void play(int toneType) {
        try {
            ToneGenerator tg = toneGenerator;
            if (tg == null) {
                synchronized (VoiceSounds.class) {
                    tg = toneGenerator;
                    if (tg == null) {
                        tg = new ToneGenerator(STREAM, VOLUME);
                        toneGenerator = tg;
                    }
                }
            }
            tg.startTone(toneType, DURATION_MS);
        } catch (Throwable ignored) {
            // never let a sound failure break voice typing
        }
    }
}
