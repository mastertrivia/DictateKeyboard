// Google Live Transcribe session controller.
//
// This file is the Live Transcribe variant of VoiceController.java, kept deliberately separate so that
// VoiceController.java can stay byte-identical to the working Speech-Notes engine the Basic voice typing
// provider ships (proved with `diff -r` against the archive the user supplied). Same session state
// machine, same restart loop, same timers and error recovery — the only differences are the recognizer
// intent and the on-device preference, both taken verbatim from the decompiled Live Transcribe app:
//
//   com/google/audio/hearing/visualization/accessibility/asr/offline/SodaSpeechSession.java (cxa.h())
//     "android.speech.extra.PREFER_OFFLINE"                 = true   // on-device model first
//     "com.google.recognition.extra.ENABLE_TEXT_FORMATTING" = true   // recognizer punctuates + capitalises
//     "com.google.recognition.extra.REQUEST_SODA_EVENTS"    = true   // end-pointing / stability events
//
// and the live-transcription audio lifecycle from the same class (cxa.b()/g(): one long-lived session,
// continuous audio, restart at every end-of-utterance rather than one utterance per tap), which is what
// the ported restart loop already does.
package helium314.keyboard.voice;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Google Live Transcribe's recognizer session — the sibling of {@link VoiceController}.
 *
 * The two are separate classes on purpose: {@code VoiceController} stays byte-identical to the engine
 * Basic voice typing has always shipped, and this one carries the Live Transcribe intent (on-device
 * first) plus the same session state machine. A Live Transcribe dictation therefore behaves exactly
 * like a basic one in the keyboard — grey provisional text while speaking, one commit at the end,
 * continuous rollover at every pause instead of one utterance per tap.
 */
public class LiveTranscribeSession implements RecognitionListener {
    /** Error codes that surface to the IME as errors (0,3,9). */
    public static final List<Integer> A = Arrays.asList(0, 3, 9);

    /**
     * How long a stop may wait for the recognizer's final hypothesis after the microphone is closed.
     *
     * Both halves of that sentence matter. It is a **ceiling, not a delay**: the wait ends the instant
     * the final callback arrives, which for an on-device model is normally a few hundred milliseconds,
     * so the timer below fires only when there is genuinely no final coming — a dead network, or a
     * recognizer that was already tearing down. And the wait is only entered at all when unstable text
     * is on screen worth improving; {@link #stopListening()} takes a no-wait path when there is nothing
     * to wait for.
     */
    private static final long FINALIZE_TIMEOUT_MS = 800L;

    /**
     * Settling delay before restarting after a network error.
     *
     * This replaces a genuine 500 ms busy-wait (`while (now < now + 500) {}`) that spun the
     * recognizer's callback thread — the keyboard's main thread — on every repeat network error. The
     * pause itself is kept, because the recognizer does need a moment to settle; only the spinning is
     * gone, so the keyboard stays responsive through a flaky spell instead of freezing in half-second
     * jumps.
     */
    private static final long NETWORK_RETRY_DELAY_MS = 500L;

    private String recognitionService = "default";

    /**
     * Google Live Transcribe parity: the on-device model is preferred over the network.
     *
     * Live Transcribe (the installed app, inspected: it ships no ASR engine of its own and reaches the
     * same `com.google.android.tts` Speech Services through `android.speech.SpeechRecognizer`) is
     * on-device-first. `EXTRA_PREFER_OFFLINE` is the public switch for exactly that and has existed since
     * API 23, so it works on Android 11 — unlike `createOnDeviceSpeechRecognizer()`, which is API 31+.
     *
     * Cleared by {@link #dropPreferOffline()} when the recognizer reports that the local model cannot
     * serve the request, so an undownloaded language still dictates over the network instead of failing.
     */
    private boolean preferOffline = true;
    private ComponentName recognitionComponent = null;
    private Activity activity;
    private Context context;
    private Intent recognitionIntent;
    private VoiceCallback callback;
    private TextTrim textTrim;
    private ConnectToRecognizerRunnable connectRunnable;
    private AudioManager audioManager;
    private String language;
    private boolean normalizePunctuation = true;
    private boolean muteAudio;
    private boolean listening;
    private boolean speechBegan;
    private CountDownTimer noSpeechTimer;
    private CountDownTimer forceRestartTimer;
    private SpeechRecognizer recognizer = null;
    private int musicVolume = -1;
    private int systemVolume = -1;
    private String composingText = "";
    private String committedText = "";
    private String lastCallback = "";
    private int rmsRepeatCount = 0;
    private float lastRms = 0.0f;
    private String pendingTypedChar = "";
    /** Explicit recognizer lifecycle. isListening() remains the compatibility session flag. */
    public enum RecognitionState { CONNECTING, READY, SPEAKING, RESTARTING, STOPPED }

    private RecognitionState recognitionState = RecognitionState.STOPPED;
    /** Monotonically increasing token invalidates callbacks/timers from old recognizers. */
    private long sessionGeneration;
    private int uiState = 0;
    /** A stable segment was already committed this session. (was the note app's t flag) */
    private boolean stableTextProcessed;

    /**
     * A stop is in flight: the microphone has been asked to close, and the session is waiting — for a
     * bounded time — for the recognizer's final hypothesis before writing text. `listening` deliberately
     * stays true for the duration, because the generation guard in {@link SessionRecognitionListener}
     * reads it: clearing it would discard the very callback this wait exists to receive.
     * {@link #isListening()} reports the caller-facing answer instead, so a caller never thinks a closing
     * session is still running.
     */
    private boolean stopping;
    /** Guarantees the session ends exactly once, whichever path reaches the end first. */
    private boolean stopCompleted;
    private CountDownTimer finalizeTimer;
    private CountDownTimer retryDelayTimer;
    /** Tap-to-stop timestamp, so the finalize wait is measured rather than assumed. */
    private long stopStartedAtMs;

    private boolean isCurrentGeneration(long generation) {
        return listening && generation == sessionGeneration;
    }

    private void setState(RecognitionState state) {
        recognitionState = state;
    }

    /** Listener bound to one recognizer generation; stale platform callbacks are ignored. */
    private final class SessionRecognitionListener implements RecognitionListener {
        private final long generation;

        SessionRecognitionListener(long generation) {
            this.generation = generation;
        }

        private boolean current() { return isCurrentGeneration(generation); }
        @Override public void onReadyForSpeech(Bundle params) { if (current()) LiveTranscribeSession.this.onReadyForSpeech(params); }
        @Override public void onBeginningOfSpeech() { if (current()) LiveTranscribeSession.this.onBeginningOfSpeech(); }
        @Override public void onRmsChanged(float rmsdB) { if (current()) LiveTranscribeSession.this.onRmsChanged(rmsdB); }
        @Override public void onBufferReceived(byte[] buffer) { if (current()) LiveTranscribeSession.this.onBufferReceived(buffer); }
        @Override public void onEndOfSpeech() { if (current()) LiveTranscribeSession.this.onEndOfSpeech(); }
        @Override public void onError(int error) { if (current()) LiveTranscribeSession.this.onError(error); }
        @Override public void onResults(Bundle results) { if (current()) LiveTranscribeSession.this.onResults(results); }
        @Override public void onPartialResults(Bundle partialResults) { if (current()) LiveTranscribeSession.this.onPartialResults(partialResults); }
        @Override public void onEvent(int eventType, Bundle params) { if (current()) LiveTranscribeSession.this.onEvent(eventType, params); }
    }

    /** Timers bind their finish work to the recognizer generation that armed them. */
    private void startNoSpeechTimer(final long generation) {
        if (noSpeechTimer != null) noSpeechTimer.cancel();
        noSpeechTimer = new CountDownTimer(4000L, 4000L) {
            @Override public void onTick(long millisUntilFinished) { }
            @Override public void onFinish() {
                if (!isCurrentGeneration(generation)) return;
                callback.onAboutToReconnect();
                setState(RecognitionState.RESTARTING);
                startForceRestartTimer(generation);
            }
        }.start();
    }

    private void startForceRestartTimer(final long generation) {
        if (forceRestartTimer != null) forceRestartTimer.cancel();
        final long delay = Build.VERSION.SDK_INT >= 23 ? 900L : 2000L;
        forceRestartTimer = new CountDownTimer(delay, delay) {
            @Override public void onTick(long millisUntilFinished) { }
            @Override public void onFinish() {
                if (!isCurrentGeneration(generation)) return;
                if (speechBegan || Build.VERSION.SDK_INT < 23) {
                    destroyAndRestart(Boolean.TRUE);
                } else {
                    restartListening(Boolean.TRUE);
                }
            }
        }.start();
    }

    /** Connects one generation to the recognizer. */
    private class ConnectToRecognizerRunnable implements Runnable {
        Context context;
        long generation;

        public ConnectToRecognizerRunnable(Context context) {
            this.context = context;
        }

        void connect(long generation) {
            this.generation = generation;
            run();
        }

        // The explicit mic attempt has already refreshed the service; restarts retain
        // that resolved service for the active voice session.
        @Override
        public void run() {
            SmartLog.a("ConnectToRecognizerRunnable", "run");
            if (!LiveTranscribeSession.this.isCurrentGeneration(generation)) return;
            if (LiveTranscribeSession.this.recognitionService.equals("no_service")
                    || LiveTranscribeSession.this.recognitionService.equals("no_google_service")) {
                LiveTranscribeSession.this.callback.onError(-2);
                return;
            }
            if (LiveTranscribeSession.this.recognizer == null) {
                ComponentName component = null;
                if (!LiveTranscribeSession.this.recognitionService.equals("default")
                        && !LiveTranscribeSession.this.recognitionService.equals("no_google_service")
                        && !LiveTranscribeSession.this.recognitionService.equals("no_service")) {
                    component = ComponentName.unflattenFromString(LiveTranscribeSession.this.recognitionService);
                }
                LiveTranscribeSession.this.recognitionComponent = component;
                LiveTranscribeSession.this.recognizer = SpeechRecognizer.createSpeechRecognizer(this.context, component);
            }
            LiveTranscribeSession.this.recognizer.setRecognitionListener(new SessionRecognitionListener(generation));
            try {
                LiveTranscribeSession.this.recognizer.startListening(LiveTranscribeSession.this.recognitionIntent);
            } catch (Exception unused) {
                LiveTranscribeSession.this.callback.onError(-3);
            }
        }
    }

    public LiveTranscribeSession(Context context, VoiceCallback callback, String language, Boolean muteAudio) {
        this.context = context;
        this.language = language;
        this.callback = callback;
        this.connectRunnable = new ConnectToRecognizerRunnable(context);
        this.audioManager = (AudioManager) context.getSystemService("audio");
        this.muteAudio = muteAudio.booleanValue();
        SmartLog.b(false);
        // Google Live Transcribe's recognizer intent, extra for extra and in its own order. Decompiled
        // from the installed app:
        //   com/google/audio/hearing/visualization/accessibility/asr/offline/SodaSpeechSession.java
        //   (obfuscated `cxa`, method `h()`), line 109 of the decompiled listing — one chained
        //   construction:
        //
        //     new Intent("android.speech.action.RECOGNIZE_SPEECH")
        //         .putExtra("calling_package", getPackageName())
        //         .putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form")
        //         .putExtra("android.speech.extra.LANGUAGE", <language>)
        //         .putExtra("android.speech.extra.MAX_RESULTS", 1)
        //         .putExtra("android.speech.extra.PREFER_OFFLINE", true)
        //         .putExtra("android.speech.extra.PARTIAL_RESULTS", true)
        //         .putExtra("com.google.recognition.extra.ENABLE_TEXT_FORMATTING", true)
        //         .putExtra("com.google.recognition.extra.REQUEST_SODA_EVENTS", true)
        //         .putExtra("android.speech.extra.DICTATION_MODE", true)
        //
        // What each one buys, in the decompiled app's own terms:
        //   PREFER_OFFLINE          the on-device model is used when it can serve the language;
        //   ENABLE_TEXT_FORMATTING   the recognizer punctuates and capitalises, so no second model pass;
        //   REQUEST_SODA_EVENTS      SODA's own end-pointing / stability events, which is what lets the
        //                            text settle continuously instead of only at the end of an utterance.
        //
        // Two deliberate omissions, both recorded rather than dropped silently:
        //   1. `com.google.recognition.extra.REQUEST_DIARIZATION` is added by Live Transcribe only in its
        //      own speaker-label modes (`this.p` == 2 or 3). Dictate has no speaker field to write labels
        //      into, so asking for diarization would put speaker tags into dictated text.
        //   2. `android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE` is set by VoiceController (the
        //      Speech-Notes engine) and is not part of Live Transcribe's request, so it is not set here.
        //      That is the one intent difference between the two engines beyond the three extras above.
        Intent intent = new Intent("android.speech.action.RECOGNIZE_SPEECH");
        this.recognitionIntent = intent;
        intent.putExtra("calling_package", this.context.getPackageName());
        this.recognitionIntent.putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form");
        this.recognitionIntent.putExtra("android.speech.extra.MAX_RESULTS", 1);
        if (this.preferOffline) {
            this.recognitionIntent.putExtra("android.speech.extra.PREFER_OFFLINE", true);
        }
        this.recognitionIntent.putExtra("android.speech.extra.PARTIAL_RESULTS", true);
        this.recognitionIntent.putExtra("com.google.recognition.extra.ENABLE_TEXT_FORMATTING", true);
        this.recognitionIntent.putExtra("com.google.recognition.extra.REQUEST_SODA_EVENTS", true);
        this.recognitionIntent.putExtra("android.speech.extra.DICTATION_MODE", true);
        setLanguage(language);
        // Service selection is intentionally deferred to each explicit microphone attempt.
    }

    /** Cancel both timers. (was A()) */
    private void cancelTimers() {
        CountDownTimer countDownTimer = this.forceRestartTimer;
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        CountDownTimer countDownTimer2 = this.noSpeechTimer;
        if (countDownTimer2 != null) {
            countDownTimer2.cancel();
        }
        CountDownTimer retry = this.retryDelayTimer;
        if (retry != null) {
            retry.cancel();
            this.retryDelayTimer = null;
        }
        // The finalize timer is deliberately NOT cancelled here: it is the guarantee that a closing
        // session ends. It is cancelled in completeStop, and only there.
    }

    /** Destroy the recognizer, then restart listening if the flag is set. (was l(Boolean)) */
    public void destroyAndRestart(Boolean bool) {
        SmartLog.a(LiveTranscribeSession.class.getName(), "destroyAndRestart");
        destroyRecognizer();
        if (bool.booleanValue()) {
            restartListening(bool);
        }
    }

    /** Process a stable hypothesis and hand it to the IME for commit. (was n(String, float)) */
    private void processStableText(String str, float f) {
        String str2;
        this.composingText = "";
        this.stableTextProcessed = true;
        while (str.startsWith(" ")) {
            str = str.substring(1);
        }
        if (!this.normalizePunctuation) {
            str = normalizePunctuation(str);
        }
        if (this.pendingTypedChar.equals("")) {
            str2 = this.textTrim.formatSpoken(str);
        } else {
            str2 = TextTrim.trimEnd(str) + this.pendingTypedChar;
            this.pendingTypedChar = "";
        }
        this.callback.commitText(str2, f);
    }

    /** Human-readable error message. (was o(int)) */
    public static String errorString(int i) {
        switch (i) {
            case 0:
                return "Language not supported error";
            case 1:
                return "Network timeout";
            case 2:
                return "Network error";
            case 3:
                return "Audio recording error";
            case 4:
                return "error from server";
            case 5:
                return "Client side error";
            case 6:
                return "No mSpeech input";
            case 7:
                return "No match";
            case 8:
                return "RecognitionService busy";
            case 9:
                return "Insufficient permissions";
            default:
                return "Didn't understand, please try again.";
        }
    }

    /** Resolve which RecognitionService to use (Google preferred). (was p()) */
    private String resolveRecognitionService() {
        PackageManager packageManager;
        Intent intent;
        Activity activity = this.activity;
        String string = Settings.Secure.getString(activity != null ? activity.getContentResolver() : this.context.getContentResolver(), "voice_recognition_service");
        if (string != null && string.indexOf("google") != -1) {
            return "default";
        }
        Activity activity2 = this.activity;
        if (activity2 != null) {
            packageManager = activity2.getPackageManager();
            intent = new Intent("android.speech.RecognitionService");
        } else {
            packageManager = this.context.getPackageManager();
            intent = new Intent("android.speech.RecognitionService");
        }
        List<ResolveInfo> queryIntentServices = packageManager.queryIntentServices(intent, 0);
        if (queryIntentServices.size() == 0) {
            return "no_service";
        }
        if (queryIntentServices.size() == 1) {
            if (queryIntentServices.get(0).toString().indexOf("google") == -1) {
                return "no_google_service";
            }
            return queryIntentServices.get(0).serviceInfo.packageName + "/" + queryIntentServices.get(0).serviceInfo.name;
        }
        String str = "";
        for (ResolveInfo resolveInfo : queryIntentServices) {
            if (resolveInfo.toString().indexOf("google") != -1) {
                str = resolveInfo.serviceInfo.packageName + "/" + resolveInfo.serviceInfo.name;
                if ("com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService".equals(str)) {
                    return "com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService";
                }
            }
        }
        return !str.equals("") ? str : "no_google_service";
    }

    private static String lowerFirst(String str) {
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }

    /**
     * Restarts the recognizer after [delayMs] without blocking the calling thread. (was an inline
     * busy-wait in onError)
     *
     * The generation guard makes this a no-op if the session ended or rolled over while the delay was
     * running, so a deferred restart can never resurrect a session that has already closed.
     */
    private void scheduleDestroyAndRestartAfter(final long delayMs, final long generation) {
        if (retryDelayTimer != null) retryDelayTimer.cancel();
        retryDelayTimer = new CountDownTimer(delayMs, delayMs) {
            @Override public void onTick(long millisUntilFinished) { }
            @Override public void onFinish() {
                if (!isCurrentGeneration(generation)) return;
                destroyAndRestart(Boolean.valueOf(listening));
            }
        }.start();
    }

    /** Punctuation normalization (dead path in this build, l is always true). (was t(String)) */
    public static String normalizePunctuation(String str) {
        if (str != null && str.trim().length() > 2) {
            if (str.endsWith(".") || str.endsWith("?") || str.endsWith("!")) {
                str = str.substring(0, str.length() - 1);
            }
            if (str.length() < 2) {
                return str;
            }
            String[] strArr = {". ", "? ", "! "};
            for (int i = 0; i < 3; i++) {
                String str2 = strArr[i];
                if (str.contains(str2)) {
                    String[] split = str.split(Pattern.quote(str2));
                    for (int i2 = 1; i2 < split.length; i2++) {
                        String str3 = split[i2];
                        while (str3.startsWith(" ")) {
                            str3 = str3.substring(1);
                        }
                        if (!str3.startsWith("I ") && !str3.startsWith("I'") && str3.length() > 1) {
                            str3 = lowerFirst(str3);
                        }
                        split[i2] = str3;
                    }
                    str = TextUtils.join(" ", split);
                }
            }
            String str4 = ", ";
            do {
                str = str.replace(str4, " ");
                str4 = "  ";
            } while (str.contains("  "));
        }
        return str;
    }

    /** Start a new recognizer generation for this still-active voice session. */
    public void restartListening(Boolean restart) {
        SmartLog.a(LiveTranscribeSession.class.getName(), "restartListening");
        if (!restart.booleanValue() || !this.listening || this.stopping) return;
        cancelTimers();
        this.committedText = "";
        this.composingText = "";
        this.pendingTypedChar = "";
        this.speechBegan = false;
        this.stableTextProcessed = false;
        // New segment: anything that became permanent in the field since the last one (a keyboard
        // touch committing the grey text, plus the user's manual typing) is settled text — the new
        // grey region must start after it and never revise or remove it.
        try {
            this.callback.onNewVoiceSegment();
        } catch (Throwable ignored) {
        }
        setState(RecognitionState.CONNECTING);
        this.connectRunnable.connect(++this.sessionGeneration);
    }

    /** Destroy the recognizer; flush leftover composing text as a commit first. (was m()) */
    public void destroyRecognizer() {
        SmartLog.a(LiveTranscribeSession.class.getName(), "destroyRecognizer");
        cancelTimers();
        String str = this.composingText;
        if (str != null && str.length() > 0) {
            processStableText(this.composingText, 0.5f);
            this.composingText = "";
        }
        SpeechRecognizer speechRecognizer = this.recognizer;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception unused) {
            }
        }
        this.recognizer = null;
    }

    @Override
    public void onBeginningOfSpeech() {
        SmartLog.a(LiveTranscribeSession.class.getName(), "onBeginningOfSpeech");
        this.speechBegan = true;
        setState(RecognitionState.SPEAKING);
        cancelTimers();
        this.lastCallback = "onBeginningOfSpeech";
    }

    @Override
    public void onBufferReceived(byte[] bArr) {
        SmartLog.a(LiveTranscribeSession.class.getName(), "onBufferReceived");
    }

    @Override
    public void onEndOfSpeech() {
        SmartLog.a(LiveTranscribeSession.class.getName(), "onEndOfSpeech");
        if (this.stopping) {
            // Speech is closed, but the session is closing too: the only thing still worth waiting for
            // is the final hypothesis, which this callback does not carry. Keep waiting for it.
            return;
        }
        if (this.lastCallback.equals("onResults")) {
            destroyAndRestart(Boolean.valueOf(this.listening));
        }
        if (this.listening) {
            this.callback.onConnecting();
        }
        this.lastCallback = "onEndOfSpeech";
        this.uiState = 0;
    }

    /**
     * Drops the on-device preference after the recognizer could not serve the request from the local
     * model, so the existing restart goes to the network instead of failing the same way again. Returns
     * true only the first time, which is what keeps the retry to a single attempt.
     */
    private boolean dropPreferOffline() {
        if (!this.preferOffline) return false;
        this.preferOffline = false;
        this.recognitionIntent.removeExtra("android.speech.extra.PREFER_OFFLINE");
        SmartLog.a(LiveTranscribeSession.class.getName(), "dropPreferOffline: retrying with the network model");
        return true;
    }

    @Override
    public void onError(int i) {
        String errorString = errorString(i);
        SmartLog.a(LiveTranscribeSession.class.getName(), "onError: " + errorString);
        // Live Transcribe parity, safety half: if the on-device model was the reason for the failure
        // (language not available / not supported / client-side refusal), give it up once and let the
        // restart below go to the network. Guarded so this can never become a retry loop.
        if (i == 11 || i == 12 || i == 5) {
            dropPreferOffline();
        }
        if (this.listening) {
            this.callback.onConnecting();
        }
        if (A.indexOf(Integer.valueOf(i)) != -1) {
            this.callback.onError(i);
        }
        if (this.stopping || this.stopCompleted) {
            // The session is already closing and this error means the final hypothesis is not coming.
            // End it now with the text that exists rather than making the user wait out the timeout.
            // (Reporting the error above can itself have ended the session — the engine stops on error —
            // hence the stopCompleted half of this guard.)
            completeStop(null, 1.0f);
            return;
        }
        if (this.composingText.length() > 0) {
            processStableText(this.composingText, 1.0f);
        }
        boolean afterNetworkError = this.lastCallback.equalsIgnoreCase("onError - network");
        if (i != 7) {
            if (afterNetworkError) {
                // Same settling pause as before, spent without holding the main thread (see
                // NETWORK_RETRY_DELAY_MS) and abandoned if the session moves on meanwhile.
                scheduleDestroyAndRestartAfter(NETWORK_RETRY_DELAY_MS, this.sessionGeneration);
            } else {
                destroyAndRestart(Boolean.valueOf(this.listening));
            }
        } else {
            restartListening(Boolean.valueOf(this.listening));
        }
        if (i == 2) {
            this.lastCallback = "onError - network";
        } else {
            this.lastCallback = "onError - general";
        }
    }

    @Override
    public void onEvent(int i, Bundle bundle) {
        SmartLog.a(LiveTranscribeSession.class.getName(), "onEvent");
    }

    @Override
    public void onPartialResults(Bundle bundle) {
        List<String> results = bundle.getStringArrayList("results_recognition");
        if (results == null || results.isEmpty()) return;
        String text = results.get(0);
        SmartLog.a(LiveTranscribeSession.class.getName(), "onPartialResults: " + text);
        this.speechBegan = true;
        setState(RecognitionState.SPEAKING);
        if (!this.stopping) startNoSpeechTimer(this.sessionGeneration);
        if (!this.normalizePunctuation) text = normalizePunctuation(text);
        this.composingText = text;
        this.callback.setComposingText(text);
        this.lastCallback = "onPartialResults";
    }

    @Override
    public void onReadyForSpeech(Bundle bundle) {
        SmartLog.a(LiveTranscribeSession.class.getName(), "onReadyForSpeech");
        if (this.stopping) return;
        setState(RecognitionState.READY);
        // The indicator represents a recognizer that has actually accepted the request.
        this.callback.onListening();
        this.uiState = 2;
        startNoSpeechTimer(this.sessionGeneration);
        this.lastCallback = "onReadyForSpeech";
        this.lastRms = 0.0f;
    }

    @Override
    public void onResults(Bundle bundle) {
        List<String> results = bundle.getStringArrayList("results_recognition");
        cancelTimers();
        float confidence = 1.0f;
        float[] confidences = bundle.getFloatArray("confidence_scores");
        if (confidences != null && confidences.length > 0) confidence = confidences[0];
        String finalText = results == null || results.isEmpty() ? null : results.get(0);
        if (this.stopping) {
            // The most accurate hypothesis a recognizer produces is the final one, and a stop is
            // exactly when it is available: the partial on screen is the unstable version of this same
            // sentence. Commit the final, not the partial — this is the difference between the last
            // words being right and the last words being whatever the interim guess happened to be.
            completeStop(TextUtils.isEmpty(finalText) ? null : finalText, confidence);
            return;
        }
        if (TextUtils.isEmpty(finalText)) {
            // This callback is generation-guarded by SessionRecognitionListener. Preserve the
            // current session's provisional editor text before restartListening clears it.
            if (this.composingText.length() > 0) processStableText(this.composingText, confidence);
            this.committedText = "";
            this.lastCallback = "onResults";
            restartListening(Boolean.valueOf(this.listening));
            return;
        }
        SmartLog.a(LiveTranscribeSession.class.getName(), "onResults: " + finalText);
        // The editor currently holds composingText as provisional text; committing the final
        // replaces it, so a non-empty final is committed exactly once rather than duplicated.
        processStableText(finalText, confidence);
        this.committedText = "";
        this.lastCallback = "onResults";
        restartListening(Boolean.valueOf(this.listening));
    }

    @Override
    public void onRmsChanged(float f) {
        this.callback.onRmsChanged(f);
        if (f >= -2.1d || this.lastRms != f) {
            this.rmsRepeatCount = 0;
        } else {
            this.rmsRepeatCount++;
            this.callback.onRmsChanged(0.0f);
        }
        if (this.rmsRepeatCount > 30) {
            this.rmsRepeatCount = 0;
            if (!this.stopping) destroyAndRestart(Boolean.valueOf(this.listening));
        }
        this.lastRms = f;
    }

    /**
     * Whether this session is still the user's live dictation. (was q())
     *
     * Deliberately not the raw `listening` flag: once a stop begins the session is closing, and callers
     * must see that immediately — the engine uses this to decide whether to tear down or to keep the
     * closing session alive, and the host uses it to decide whether a tap means start or stop.
     */
    public Boolean isListening() {
        return Boolean.valueOf(this.listening && !this.stopping);
    }

    /** True once {@link #stopListening()} began and the final hypothesis is still outstanding. */
    public synchronized boolean isStopping() {
        return this.stopping;
    }

    /** Set mute-audio-during-listening flag. (was v(boolean)) */
    public void setMuteAudio(boolean z) {
        this.muteAudio = z;
    }

    /**
     * Set the recognition language. (was w(String))
     *
     * Live Transcribe sets `android.speech.extra.LANGUAGE` and nothing else (`cxa` line 109, the `this.j`
     * field). VoiceController also sets `LANGUAGE_PREFERENCE`; that extra is not part of the Live
     * Transcribe request, so it is not set here — `EXTRA_LANGUAGE` is the documented one and adding a
     * second, undocumented spelling of the same thing only gives a recognizer another way to disagree.
     */
    public void setLanguage(String str) {
        this.language = str;
        this.recognitionIntent.putExtra("android.speech.extra.LANGUAGE", str);
        this.textTrim = new TextTrim(this.language, Boolean.valueOf(this.normalizePunctuation));
    }

    /** Type a character while listening: buffer it if composing, else commit directly. (was x(String)) */
    public void typeChar(String str) {
        if (this.composingText.length() > 0) {
            this.pendingTypedChar = str;
        } else {
            this.callback.commitText(str, 1.0f);
        }
    }

    /** Resolve the selected service again for each user mic attempt. */
    public synchronized boolean refreshRecognitionServiceAvailability() {
        this.recognitionService = resolveRecognitionService();
        this.recognitionComponent = null;
        return !this.recognitionService.equals("no_google_service")
                && !this.recognitionService.equals("no_service");
    }

    /** Start listening. (was y()) */
    public synchronized boolean startListening() {
        SmartLog.a(LiveTranscribeSession.class.getName(), "startListening");
        if (this.stopping) {
            // A new dictation arrived inside the previous session's closing window. Resolve the old
            // session's text now — do not make the user wait out the timeout — but deliberately do not
            // report the session finished: the host would tear the session down on that report, and this
            // start would land in a session nobody owns. The engine is still the owner, so it simply
            // continues into the new segment with the previous text already settled.
            completeStop(null, 1.0f, false);
        }
        if (this.listening) {
            return true;
        }
        if (!refreshRecognitionServiceAvailability()) {
            this.callback.onError(-1);
            return false;
        }
        if (this.recognizer != null) {
            try {
                this.recognizer.destroy();
            } catch (Exception ignored) {
            }
            this.recognizer = null;
        }
        this.listening = true;
        this.stopping = false;
        this.stopCompleted = false;
        this.speechBegan = false;
        restartListening(Boolean.TRUE);
        if (AudioStreamHelper.getMusicVolume(this.audioManager) > 0) {
            this.musicVolume = AudioStreamHelper.getMusicVolume(this.audioManager);
        }
        if (AudioStreamHelper.getSystemVolume(this.audioManager) > 0) {
            this.systemVolume = AudioStreamHelper.getSystemVolume(this.audioManager);
        }
        if (this.muteAudio) {
            AudioStreamHelper.muteStreams(this.audioManager);
        }
        this.callback.onConnecting();
        this.uiState = 0;
        return true;
    }

    /**
     * Closes the session gracefully. (was z())
     *
     * The original committed the partial that happened to be on screen and then invalidated the
     * recognizer with a generation bump — so the final hypothesis, which is the recognizer's most
     * accurate output and is produced precisely at the end of speech, was thrown away every single time
     * the user stopped. The last words of a dictation were therefore always the unstable version of
     * themselves. This asks the recognizer to close, then waits a strictly bounded time for that final
     * before writing text, falling back to the partial only if it never arrives.
     *
     * @return true when the completion is still outstanding and will be reported through
     *         {@link VoiceCallback#onSessionFinished()}; false when the session is already over, so the
     *         caller can finish its teardown immediately as it always did.
     */
    public synchronized boolean stopListening() {
        SmartLog.a(LiveTranscribeSession.class.getName(), "stopListening");
        if (this.stopping) return true;
        if (!this.listening) {
            // Nothing is running, so there is no final to wait for and no generation to invalidate.
            return false;
        }
        this.stopping = true;
        this.stopStartedAtMs = System.currentTimeMillis();
        cancelTimers();
        setState(RecognitionState.STOPPED);
        SpeechRecognizer speechRecognizer = this.recognizer;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
            } catch (Exception ignored) {
            }
        }
        if (this.composingText.length() == 0) {
            // Nothing unstable on screen means nothing for a final to improve, so there is no reason to
            // make this stop any slower than it used to be. This is the common case for a tap that
            // follows a pause: the rollover already committed everything and the screen is empty.
            completeStop(null, 1.0f);
            return false;
        }
        startFinalizeTimer();
        return true;
    }

    /** Bounded wait for the final hypothesis after a stop. Fires only if it never arrives. */
    private void startFinalizeTimer() {
        if (finalizeTimer != null) finalizeTimer.cancel();
        finalizeTimer = new CountDownTimer(FINALIZE_TIMEOUT_MS, FINALIZE_TIMEOUT_MS) {
            @Override public void onTick(long millisUntilFinished) { }
            @Override public void onFinish() {
                SmartLog.a(LiveTranscribeSession.class.getName(),
                        "finalize: no final in " + FINALIZE_TIMEOUT_MS + " ms, using the last partial");
                completeStop(null, 1.0f);
            }
        }.start();
    }

    /**
     * Completes a closing session immediately, without waiting for a final that may never come.
     *
     * Called when a caller has already decided this session is over — a cancel, or a second stop — so
     * that the closing state can never outlive the interest in it and hold a wake lock open.
     */
    public synchronized void finishStopNow() {
        if (this.stopping) completeStop(null, 1.0f);
    }

    private void completeStop(String finalText, float confidence) {
        completeStop(finalText, confidence, true);
    }

    /**
     * Ends the session exactly once and writes its text.
     *
     * [finalText] is the recognizer's final hypothesis when one arrived; null means it never did, in
     * which case the last partial is used so that the words the user actually spoke are not discarded.
     * [notifyHost] is false only on the seamless-restart path, where reporting the session finished
     * would make the host tear down a session that is about to continue — see {@link #startListening()}.
     */
    private void completeStop(String finalText, float confidence, boolean notifyHost) {
        if (this.stopCompleted) return;
        this.stopCompleted = true;
        if (finalizeTimer != null) {
            finalizeTimer.cancel();
            finalizeTimer = null;
        }
        this.listening = false;
        this.stopping = false;
        ++this.sessionGeneration;   // any late callback from the closed recognizer is now stale
        setState(RecognitionState.STOPPED);
        String text = finalText != null ? finalText : this.composingText;
        if (text != null && text.length() > 0) {
            processStableText(text, confidence);
        }
        if (this.stopStartedAtMs > 0L) {
            SmartLog.a(LiveTranscribeSession.class.getName(),
                    "finalize: " + (finalText != null ? "final text" : "last partial")
                            + " written " + (System.currentTimeMillis() - this.stopStartedAtMs) + " ms after the stop");
        }
        this.composingText = "";
        this.committedText = "";
        this.pendingTypedChar = "";
        SpeechRecognizer speechRecognizer = this.recognizer;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {
            }
        }
        this.recognizer = null;
        AudioStreamHelper.restoreMusicVolume(this.audioManager, this.musicVolume);
        AudioStreamHelper.restoreSystemVolume(this.audioManager, this.systemVolume);
        this.stopStartedAtMs = 0L;
        if (notifyHost) {
            // The text above is already committed, so the host still holds composing ownership while it
            // is written and only releases it on this report. Ordering matters: notify after committing.
            this.callback.onSessionFinished();
        }
    }
}
