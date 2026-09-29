// Google Live Transcribe engine — the IME-side glue, sibling of SpeechNotesVoiceEngine.java.
//
// Kept as its own file for the same reason LiveTranscribeSession.java is: SpeechNotesVoiceEngine.java
// stays byte-identical to the engine Basic voice typing has always shipped. The difference here is which
// controller is constructed — LiveTranscribeSession (on-device first, recognizer-side formatting) instead
// of VoiceController — and, because of that, where the session reports that it is over: the restart loop,
// the grey composing text, the watchdog, the wake lock, the Bluetooth SCO manager and the tonal feedback
// are identical, but IDLE now arrives from onSessionFinished() rather than from stop(), so that a stop can
// wait for the recognizer's final hypothesis before the session is torn down. See stop().
//
// Original header of the file this was derived from follows.
// Ported from SpeechNotes' Speechkeys.java — the IME-side glue of the voice engine.
// Every voice-related behavior from the original IME is recreated here:
//   - b()  -> commitText with boundary processing
//   - f()  -> grey composing text
//   - c()/d()/e() -> status callbacks (no-ops here: HeliBoard's button UI is kept unchanged)
//   - onError() -> log + full stop
//   - Y()/Z() -> start/stop (controller + BT SCO + watchdog + wake lock)
//   - startOrPauseListener() -> the mic toggle
//   - s()/I() -> RECORD_AUDIO + Google service gates
//   - y()/x() -> typed-character routing while listening
//   - r() -> boundary-aware backspace while listening
//   - Speechkeys$a -> 60s/30s watchdog
// Logic identical to the original; only names and the settings-launch target changed.
package helium314.keyboard.voice;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.CountDownTimer;
import android.os.PowerManager;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.inputmethod.InputConnection;

import androidx.core.content.ContextCompat;

/**
 * Google Live Transcribe's engine glue, hosted through {@link VoiceEngineHost}.
 *
 * Derived from {@link SpeechNotesVoiceEngine} (itself the voice parts of Speechkeys.java, with
 * HeliBoard's LatinIME as the reference host) and identical to it below the host seam — the recognizer
 * restart loop, the grey composing text, the watchdog, the Bluetooth SCO manager — except that it drives
 * {@link LiveTranscribeSession} and defers its IDLE report to
 * {@link LiveTranscribeSession#stopListening()} completing. See the file header and
 * {@link #stop()}.
 */
public class LiveTranscribeEngine implements VoiceCallback {

    private static final long WATCHDOG_MILLIS = 60000L;
    private static final long WATCHDOG_TICK = 30000L;
    private static final int WAKE_LOCK_FLAGS = PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
    private static final String WAKE_LOCK_TAG = "SpeechKeys::MyWakelockTag";

    private final Context context;
    private final VoiceEngineHost host;
    private final LiveTranscribeSession controller;
    private final BluetoothScoManager bluetoothSco;
    private final CountDownTimer watchdog;
    private PowerManager.WakeLock wakeLock;

    /** Explicit UI phase: only READY represents a recognizer that accepted speech. */
    public enum VoiceUiState { IDLE, CONNECTING, READY, RESTARTING }

    private VoiceUiState voiceUiState = VoiceUiState.IDLE;
    /** Remains true across automatic recognizer generations in one user session. */
    private boolean sessionReachedReady;

    /** Length of the currently composing region. (was Speechkeys.q) */
    private int composingLength;
    /** Current recognition language. (was Speechkeys.t) */
    private String language = "en-US";

    public LiveTranscribeEngine(Context context, VoiceEngineHost host) {
        this.context = context;
        this.host = host;
        // Build the BT SCO manager first: LiveTranscribeSession's constructor can fire
        // onError (e.g. no speech service on the device) which calls stop() on
        // this engine, and stop() touches bluetoothSco. Ordering it first makes
        // that path a safe no-op (started == false) instead of an NPE.
        this.bluetoothSco = new BluetoothScoManager(context) {
            @Override
            public void onHeadsetConnected() {
            }

            @Override
            public void onHeadsetDisconnected() {
            }

            @Override
            public void onScoConnected() {
                start();
            }

            @Override
            public void onScoDisconnected() {
                stop();
            }
        };
        this.controller = new LiveTranscribeSession(context, this, language, Boolean.TRUE);
        this.watchdog = new CountDownTimer(WATCHDOG_MILLIS, WATCHDOG_TICK) { // was Speechkeys$a
            @Override
            public void onTick(long millisUntilFinished) {
            }

            @Override
            public void onFinish() {
                if (controller.isListening()) {
                    stop();
                }
            }
        };
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            this.wakeLock = powerManager.newWakeLock(WAKE_LOCK_FLAGS, WAKE_LOCK_TAG);
        }
    }

    /** The mic toggle. (was Speechkeys.startOrPauseListener, minus the billing gate) */
    public void startOrPause() {
        if (controller.isListening()) {
            stop();
            return;
        }
        // Resolve through the controller on every mic attempt so this gate and the
        // recognizer use the same current RecognitionService decision.
        if (!hasRecordAudioPermission() || !controller.refreshRecognitionServiceAvailability()) {
            openSetup();
        } else {
            start();
        }
    }

    /** Full start: controller + BT SCO + watchdog + wake lock. (was Speechkeys.Y) */
    public synchronized void start() {
        // SCO readiness is asynchronous and can arrive after the direct mic start.
        // The active controller session is the single start authority.
        if (controller.isListening()) {
            return;
        }
        // This runs for every normal-voice entry point, including the SCO callback.
        host.stopAiVoiceForNormalVoiceStart();
        if (!controller.startListening()) {
            return;
        }
        bluetoothSco.start();
        watchdog.start();
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    /**
     * Full stop: BT SCO + controller + watchdog + wake lock. (was Speechkeys.Z)
     *
     * The one place this differs from {@link SpeechNotesVoiceEngine}, and deliberately: the session's
     * stop now writes its text after waiting a bounded time for the recognizer's final hypothesis (see
     * {@link LiveTranscribeSession#stopListening()}), so IDLE must not be reported here — reporting it
     * now would tear the session down (the host releases composing ownership and drops the engine on
     * IDLE) and the final words would be written into a session nobody owns. IDLE moves to
     * {@link #onSessionFinished()}, which the session calls once its text is in.
     */
    public synchronized void stop() {
        // Null-safe: LiveTranscribeSession's constructor can call onError (no speech
        // service) which stops this engine while controller/bluetoothSco are
        // still being initialized.
        if (bluetoothSco != null)
            bluetoothSco.stop();
        if (watchdog != null)
            watchdog.cancel();
        boolean finalizing = false;
        if (controller != null) {
            if (controller.isListening()) {
                finalizing = controller.stopListening();
                if (finalizing) {
                    // The microphone is closed but the session is still waiting for its final text. Say so:
                    // leaving the listening state up would claim the recognizer is still hearing, when the
                    // only thing left happening is the closing words being written.
                    host.onVoiceEngineClosing();
                }
            } else if (controller.isStopping()) {
                // A closing session that nobody is going to wait for any more — a cancel, or a second
                // stop. Resolve it now rather than leaving the wake lock held for a session the caller
                // has already abandoned.
                controller.finishStopNow();
            }
        }
        if (!finalizing) {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            final boolean wasReady = sessionReachedReady;
            sessionReachedReady = false;
            setVoiceUiState(VoiceUiState.IDLE, false, wasReady);
        }
    }

    /** Stop if currently listening, or if a previous stop is still writing its final text. */
    public void stopIfListening() {
        if (controller.isListening() || controller.isStopping()) {
            stop();
        }
    }

    public boolean isListening() {
        return controller.isListening();
    }

    /** Set the recognition language (both controller and text processing). (was Speechkeys.R) */
    public void setLanguage(String lang) {
        this.language = lang;
        controller.setLanguage(lang);
        // A long-lived SpeechRecognizer keeps transcribing in the language it
        // was started with even if the intent is mutated afterwards. When the
        // language is switched mid-dictation, recreate the recognizer so the
        // new language applies to the next segment — without stopping the session.
        if (controller.isListening()) {
            controller.destroyRecognizer();
            controller.restartListening(Boolean.TRUE);
        }
    }

    /** Route a typed string: buffer it in the engine while composing, else commit. (was Speechkeys.y/x) */
    public void typeText(String str) {
        if (controller.isListening()) {
            controller.typeChar(str);
        } else {
            commitText(str, 1.0f);
        }
    }

    /** Boundary-aware backspace while listening. (was Speechkeys.r) */
    public void deleteChar() {
        InputConnection currentInputConnection = host.currentInputConnection();
        if (currentInputConnection == null) {
            return;
        }
        CharSequence selectedText = currentInputConnection.getSelectedText(0);
        if (selectedText != null && selectedText.length() >= 1) {
            sendDeleteKeyEvent();
            return;
        }
        if (isEmojiAtStart(currentInputConnection.getTextBeforeCursor(2, 1).toString())) {
            currentInputConnection.deleteSurroundingText(2, 0);
            return;
        }
        int e2 = TextBoundary.deleteBoundaryLength(currentInputConnection.getTextBeforeCursor(10, 0).toString());
        if (e2 <= 0 || e2 > 10) {
            return;
        }
        currentInputConnection.deleteSurroundingText(e2, 0);
    }

    // -- VoiceCallback implementation (was the Speechkeys implements c.c.a.a methods) --

    /** A new segment begins: the host re-bases its text accounting (see VoiceCallback). */
    @Override
    public void onNewVoiceSegment() {
        if (host != null) {
            try {
                host.beginNewVoiceSegment();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** Commit stable/final text with boundary processing. (was Speechkeys.b) */
    @Override
    public void commitText(String str, float f) {
        InputConnection currentInputConnection = host.currentInputConnection();
        if (currentInputConnection == null) {
            return;
        }
        try {
            String before = currentInputConnection.getTextBeforeCursor(this.composingLength + 10, 0).toString();
            String ctxB = TextBoundary.sliceBeforeComposing(before, -(this.composingLength + 10), -this.composingLength);
            String after = currentInputConnection.getTextAfterCursor(1, 0).toString();
            String out = TextBoundary.process(str, ctxB, after, this.language);
            currentInputConnection.commitText(out, 1);
            this.composingLength = 0;
            if (this.watchdog != null) {
                this.watchdog.cancel();
            }
            this.watchdog.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Show unstable text as grey composing text. (was Speechkeys.f) */
    @Override
    public void setComposingText(String str) {
        InputConnection currentInputConnection;
        if (str == null || str.length() < 1 || (currentInputConnection = host.currentInputConnection()) == null) {
            return;
        }
        try {
            String before = currentInputConnection.getTextBeforeCursor(this.composingLength + 10, 0).toString();
            String ctxB = TextBoundary.sliceBeforeComposing(before, -(this.composingLength + 10), -this.composingLength);
            String after = currentInputConnection.getTextAfterCursor(1, 0).toString();
            String out = TextBoundary.process(str, ctxB, after, this.language);
            SpannableString spannableString = new SpannableString(out);
            spannableString.setSpan(new ForegroundColorSpan(0xFF888888), 0, out.length(), 0);
            currentInputConnection.setComposingText(spannableString, 1);
            this.composingLength = out.length();
            if (this.watchdog != null) {
                this.watchdog.cancel();
            }
            this.watchdog.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setVoiceUiState(VoiceUiState state, boolean playStartTone, boolean playStopTone) {
        // Every recognizer onReadyForSpeech must restore the READY bars, even if a
        // provider sends duplicate ready callbacks. Sound eligibility is separate.
        if (voiceUiState == state && state != VoiceUiState.READY
                && !(state == VoiceUiState.IDLE && playStopTone)) {
            return;
        }
        voiceUiState = state;
        SpeechNotesVoiceEngine.VoiceUiState hostState = SpeechNotesVoiceEngine.VoiceUiState.valueOf(state.name());
        host.onVoiceEngineStateChanged(hostState, playStartTone, playStopTone);
    }

    /** "About to reconnect" — show a static restart acknowledgement, never bars. */
    @Override
    public void onAboutToReconnect() {
        setVoiceUiState(VoiceUiState.RESTARTING, false, false);
    }

    /** "Wait, connecting" — distinguish initial setup from continuous restart. */
    @Override
    public void onConnecting() {
        setVoiceUiState(sessionReachedReady ? VoiceUiState.RESTARTING : VoiceUiState.CONNECTING,
                false, false);
    }

    /** Only LiveTranscribeSession.onReadyForSpeech reaches this callback. */
    @Override
    public void onListening() {
        final boolean playStartTone = !sessionReachedReady;
        sessionReachedReady = true;
        setVoiceUiState(VoiceUiState.READY, playStartTone, false);
    }

    /** Error: log and do a full stop. (was Speechkeys.onError) */
    @Override
    public void onError(int i) {
        LiveTranscribeSession.errorString(i);
        stop();
    }

    /**
     * The session has written its final text and is over: report IDLE, which is what tears the engine
     * down in the host. This is the deferred half of {@link #stop()} — see the note there.
     */
    @Override
    public void onSessionFinished() {
        if (watchdog != null) {
            watchdog.cancel();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        final boolean wasReady = sessionReachedReady;
        sessionReachedReady = false;
        setVoiceUiState(VoiceUiState.IDLE, false, wasReady);
    }

    /**
     * RMS meter. Unused in HeliBoard, whose voice bars animate on a fixed cycle; forwarded to the host
     * here so Dictate's live indicator can move with the voice instead. (was Speechkeys.onRmsChanged)
     */
    @Override
    public void onRmsChanged(float f) {
        host.onVoiceRmsChanged(f);
    }

    // -- helpers (ported from Speechkeys) --

    /** RECORD_AUDIO permission check. (was Speechkeys.s) */
    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(context, "android.permission.RECORD_AUDIO")
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Open setup when permission/service missing. (was Speechkeys.z -> LauncherActivity) */
    private void openSetup() {
        host.openVoiceSetup();
    }

    /** Send a single backspace key event. (was Speechkeys.K(67)) */
    private void sendDeleteKeyEvent() {
        InputConnection currentInputConnection = host.currentInputConnection();
        if (currentInputConnection == null) {
            return;
        }
        android.view.KeyEvent down = new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL);
        android.view.KeyEvent up = new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL);
        currentInputConnection.sendKeyEvent(down);
        currentInputConnection.sendKeyEvent(up);
    }

    /** Emoji surrogate-pair check. (was com.speechlogger.customprototypes.a.c) */
    private static boolean isEmojiAtStart(String str) {
        return str != null && str.length() == 2 && isEmojiCodePoint(Character.codePointAt(str, 0));
    }

    private static boolean isEmojiCodePoint(int i) {
        return i >= 127744 && i <= 129433;
    }
}
