// Ported from SpeechNotes' c.c.a.a (the 7-method contract the IME implements).
// Logic identical to the original; only names were made readable.
package helium314.keyboard.voice;

/** Contract between the SpeechNotes voice controller and the IME host. */
public interface VoiceCallback {
    /** Commit stable/final text into the editor. (was b(String, float)) */
    void commitText(String text, float confidence);

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

    /** RMS level of the microphone. (was onRmsChanged(float)) */
    void onRmsChanged(float rms);
}
