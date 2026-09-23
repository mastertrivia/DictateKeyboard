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

    /** Called on every voice start so the host can stop any other voice feature it runs. */
    void stopAiVoiceForNormalVoiceStart();

    /** Open the host's settings (permission or speech service missing). */
    void openVoiceSetup();
}
