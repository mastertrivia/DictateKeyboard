// Host seam for the ported voice engine. The engine was written against HeliBoard's IME class
// (LatinIME); hosting it inside another keyboard means providing exactly these four operations.
// The engine's own logic is untouched — this interface only names the surface it already used.
package helium314.keyboard.voice;

import android.view.inputmethod.InputConnection;

/** Everything the voice engine needs from its host IME (was the LatinIME surface it used). */
public interface VoiceEngineHost {

    /** The live input connection, or null when no editor is focused. */
    InputConnection currentInputConnection();

    /** Engine state change; the host plays the start/stop tones and updates its own UI. */
    void onVoiceEngineStateChanged(SpeechNotesVoiceEngine.VoiceUiState state,
            boolean playStartTone, boolean playStopTone);

    /**
     * Microphone level while the engine is listening, in the recognizer's own dB
     * (`RecognitionListener.onRmsChanged`).
     *
     * The engine has always received this and thrown it away — HeliBoard draws its voice bars on a fixed
     * animation instead — so this is the one addition the host seam needed: with it, the host's live
     * indicator can move with the speaker's voice rather than on a timer, which is the whole point of a
     * level meter. Nothing below the seam changes: the value is only read on its way past.
     */
    void onVoiceRmsChanged(float rmsDb);

    /**
     * The microphone has been closed and the session is still writing its last words.
     *
     * Not every engine has this stage: {@code SpeechNotesVoiceEngine} finishes the instant it is asked to
     * stop, so its session ends at the same moment the microphone does. The Live Transcribe engine's stop
     * waits a bounded time for the recognizer's final hypothesis (see
     * {@code LiveTranscribeSession.stopListening()}), and during that wait the microphone is closed while
     * the bar would otherwise still be showing a listening state. This is the host's chance to name that
     * stage honestly instead of claiming the recognizer is still hearing.
     */
    void onVoiceEngineClosing();

    /** Called on every voice start so the host can stop any other voice feature it runs. */
    void stopAiVoiceForNormalVoiceStart();

    /** Open the host's settings (permission or speech service missing). */
    void openVoiceSetup();

    /**
     * The engine starts a new dictation segment. The host re-bases its text accounting so the next
     * cancel/stop can only ever remove what this new segment itself wrote.
     */
    void beginNewVoiceSegment();
}
