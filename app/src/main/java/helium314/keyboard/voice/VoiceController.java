// Ported from SpeechNotes' c.c.a.b — the complete voice session controller.
// This is the whole engine: SpeechRecognizer session, state machine, partial/stable text
// handling, restart loop, timers, error recovery. Logic identical to the original;
// only names were made readable.
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

/** The SpeechNotes voice session controller (was c.c.a.b). */
public class VoiceController implements RecognitionListener {
    /** Error codes that surface to the IME as errors (0,3,9). */
    public static final List<Integer> A = Arrays.asList(0, 3, 9);

    private String recognitionService = "default";
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
        @Override public void onReadyForSpeech(Bundle params) { if (current()) VoiceController.this.onReadyForSpeech(params); }
        @Override public void onBeginningOfSpeech() { if (current()) VoiceController.this.onBeginningOfSpeech(); }
        @Override public void onRmsChanged(float rmsdB) { if (current()) VoiceController.this.onRmsChanged(rmsdB); }
        @Override public void onBufferReceived(byte[] buffer) { if (current()) VoiceController.this.onBufferReceived(buffer); }
        @Override public void onEndOfSpeech() { if (current()) VoiceController.this.onEndOfSpeech(); }
        @Override public void onError(int error) { if (current()) VoiceController.this.onError(error); }
        @Override public void onResults(Bundle results) { if (current()) VoiceController.this.onResults(results); }
        @Override public void onPartialResults(Bundle partialResults) { if (current()) VoiceController.this.onPartialResults(partialResults); }
        @Override public void onEvent(int eventType, Bundle params) { if (current()) VoiceController.this.onEvent(eventType, params); }
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
            if (!VoiceController.this.isCurrentGeneration(generation)) return;
            if (VoiceController.this.recognitionService.equals("no_service")
                    || VoiceController.this.recognitionService.equals("no_google_service")) {
                VoiceController.this.callback.onError(-2);
                return;
            }
            if (VoiceController.this.recognizer == null) {
                ComponentName component = null;
                if (!VoiceController.this.recognitionService.equals("default")
                        && !VoiceController.this.recognitionService.equals("no_google_service")
                        && !VoiceController.this.recognitionService.equals("no_service")) {
                    component = ComponentName.unflattenFromString(VoiceController.this.recognitionService);
                }
                VoiceController.this.recognitionComponent = component;
                VoiceController.this.recognizer = SpeechRecognizer.createSpeechRecognizer(this.context, component);
            }
            VoiceController.this.recognizer.setRecognitionListener(new SessionRecognitionListener(generation));
            try {
                VoiceController.this.recognizer.startListening(VoiceController.this.recognitionIntent);
            } catch (Exception unused) {
                VoiceController.this.callback.onError(-3);
            }
        }
    }

    public VoiceController(Context context, VoiceCallback callback, String language, Boolean muteAudio) {
        this.context = context;
        this.language = language;
        this.callback = callback;
        this.connectRunnable = new ConnectToRecognizerRunnable(context);
        this.audioManager = (AudioManager) context.getSystemService("audio");
        this.muteAudio = muteAudio.booleanValue();
        SmartLog.b(false);
        Intent intent = new Intent("android.speech.action.RECOGNIZE_SPEECH");
        this.recognitionIntent = intent;
        intent.putExtra("android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE", true);
        this.recognitionIntent.putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form");
        this.recognitionIntent.putExtra("android.speech.extra.MAX_RESULTS", 1);
        this.recognitionIntent.putExtra("android.speech.extra.PARTIAL_RESULTS", true);
        this.recognitionIntent.putExtra("calling_package", this.context.getPackageName());
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
    }

    /** Destroy the recognizer, then restart listening if the flag is set. (was l(Boolean)) */
    public void destroyAndRestart(Boolean bool) {
        SmartLog.a(VoiceController.class.getName(), "destroyAndRestart");
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

    private static void busyWait(Long l) {
        while (System.currentTimeMillis() < Long.valueOf(System.currentTimeMillis()).longValue() + l.longValue()) {
        }
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
        SmartLog.a(VoiceController.class.getName(), "restartListening");
        if (!restart.booleanValue() || !this.listening) return;
        cancelTimers();
        this.committedText = "";
        this.composingText = "";
        this.pendingTypedChar = "";
        this.speechBegan = false;
        this.stableTextProcessed = false;
        setState(RecognitionState.CONNECTING);
        this.connectRunnable.connect(++this.sessionGeneration);
    }

    /** Destroy the recognizer; flush leftover composing text as a commit first. (was m()) */
    public void destroyRecognizer() {
        SmartLog.a(VoiceController.class.getName(), "destroyRecognizer");
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
        SmartLog.a(VoiceController.class.getName(), "onBeginningOfSpeech");
        this.speechBegan = true;
        setState(RecognitionState.SPEAKING);
        cancelTimers();
        this.lastCallback = "onBeginningOfSpeech";
    }

    @Override
    public void onBufferReceived(byte[] bArr) {
        SmartLog.a(VoiceController.class.getName(), "onBufferReceived");
    }

    @Override
    public void onEndOfSpeech() {
        SmartLog.a(VoiceController.class.getName(), "onEndOfSpeech");
        if (this.lastCallback.equals("onResults")) {
            destroyAndRestart(Boolean.valueOf(this.listening));
        }
        if (this.listening) {
            this.callback.onConnecting();
        }
        this.lastCallback = "onEndOfSpeech";
        this.uiState = 0;
    }

    @Override
    public void onError(int i) {
        String errorString = errorString(i);
        SmartLog.a(VoiceController.class.getName(), "onError: " + errorString);
        if (this.listening) {
            this.callback.onConnecting();
        }
        if (A.indexOf(Integer.valueOf(i)) != -1) {
            this.callback.onError(i);
        }
        if (this.composingText.length() > 0) {
            processStableText(this.composingText, 1.0f);
        }
        if (this.lastCallback.equalsIgnoreCase("onError - network")) {
            busyWait(500L);
        }
        if (i != 7) {
            destroyAndRestart(Boolean.valueOf(this.listening));
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
        SmartLog.a(VoiceController.class.getName(), "onEvent");
    }

    @Override
    public void onPartialResults(Bundle bundle) {
        List<String> results = bundle.getStringArrayList("results_recognition");
        if (results == null || results.isEmpty()) return;
        String text = results.get(0);
        SmartLog.a(VoiceController.class.getName(), "onPartialResults: " + text);
        this.speechBegan = true;
        setState(RecognitionState.SPEAKING);
        startNoSpeechTimer(this.sessionGeneration);
        if (!this.normalizePunctuation) text = normalizePunctuation(text);
        this.composingText = text;
        this.callback.setComposingText(text);
        this.lastCallback = "onPartialResults";
    }

    @Override
    public void onReadyForSpeech(Bundle bundle) {
        SmartLog.a(VoiceController.class.getName(), "onReadyForSpeech");
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
        if (TextUtils.isEmpty(finalText)) {
            // This callback is generation-guarded by SessionRecognitionListener. Preserve the
            // current session's provisional editor text before restartListening clears it.
            if (this.composingText.length() > 0) processStableText(this.composingText, confidence);
            this.committedText = "";
            this.lastCallback = "onResults";
            restartListening(Boolean.valueOf(this.listening));
            return;
        }
        SmartLog.a(VoiceController.class.getName(), "onResults: " + finalText);
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
            destroyAndRestart(Boolean.valueOf(this.listening));
        }
        this.lastRms = f;
    }

    /** Whether the controller is actively listening. (was q()) */
    public Boolean isListening() {
        return Boolean.valueOf(this.listening);
    }

    /** Set mute-audio-during-listening flag. (was v(boolean)) */
    public void setMuteAudio(boolean z) {
        this.muteAudio = z;
    }

    /** Set the recognition language. (was w(String)) */
    public void setLanguage(String str) {
        this.language = str;
        this.recognitionIntent.putExtra("android.speech.extra.LANGUAGE", str);
        this.recognitionIntent.putExtra("android.speech.extra.LANGUAGE_PREFERENCE", this.language);
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
        SmartLog.a(VoiceController.class.getName(), "startListening");
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

    /** Stop listening and commit any provisional editor composition. (was z()) */
    public void stopListening() {
        SmartLog.a(VoiceController.class.getName(), "stopListening");
        this.listening = false;
        ++this.sessionGeneration;
        setState(RecognitionState.STOPPED);
        cancelTimers();
        if (this.composingText.length() > 0) {
            processStableText(this.composingText, 1.0f);
        }
        this.composingText = "";
        this.committedText = "";
        this.pendingTypedChar = "";
        SpeechRecognizer speechRecognizer = this.recognizer;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
            } catch (Exception ignored) {
            }
        }
        AudioStreamHelper.restoreMusicVolume(this.audioManager, this.musicVolume);
        AudioStreamHelper.restoreSystemVolume(this.audioManager, this.systemVolume);
    }
}
