// Ported from SpeechNotes' c.c.a.a (the 7-method contract the IME implements).
// Logic identical to the original; only names were made readable.
package helium314.keyboard.voice;

/** Contract between the SpeechNotes voice controller and the IME host. */
public interface VoiceCallback {
    /** Commit stable/final text into the editor. (was b(String, float)) */
    void commitText(String text, float confidence);

    /**
     * A fresh dictation segment is starting (new recognizer generation): any text the user made
     * permanent since the last one — a keyboard touch committing the grey region, plus manual
     * typing — is settled and must not be revised or removed by the new segment.
     */
    void onNewVoiceSegment();

    /** "About to reconnect" status. (was c()) */
    void onAboutToReconnect();

    /** "Wait, connecting" status. (was d()) */
    void onConnecting();

    /** "Listening..." status. (was e()) */
    void onListening();

    /** Show unstable text as grey composing text. (was f(String)) */
    void setComposingText(String text);

    /** Recognition error occurred. (was onError(int)) */
    void onError(int errorCode);

    /**
     * The session is over and its text has been written.
     *
     * A default method on purpose: this exists for the Live Transcribe session, whose stop waits a
     * bounded time for the recognizer's final hypothesis before writing text, and therefore cannot
     * report that it has finished at the moment it was asked to stop. The Speech-Notes engine finishes
     * synchronously and reports its state the way it always has, so it needs nothing here — and adding a
     * required method would have forced a change into a file that is kept byte-identical to the engine
     * Basic voice typing ships.
     */
    default void onSessionFinished() {
    }

    /** RMS level of the microphone. (was onRmsChanged(float)) */
    void onRmsChanged(float rms);
}
