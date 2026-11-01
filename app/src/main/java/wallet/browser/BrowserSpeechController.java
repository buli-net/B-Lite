package wallet.browser;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.WebView;

import androidx.core.content.ContextCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Supplies the Web Speech Recognition API to Android WebView pages that do not implement it.
 * Speech is recognized by Android's configured speech-recognition service.
 */
final class BrowserSpeechController {
    private static final int REQUEST_MICROPHONE = 8406;
    private static final String BRIDGE_NAME = "NativeSpeechBridge";
    private static final Set<String> ORIGIN_RULES = Collections.singleton("*");
    private static final long CONTINUOUS_RESTART_DELAY_MS = 250L;
    private static final long SINGLE_SHOT_LISTEN_WINDOW_MS = 15000L;
    private static final long POSSIBLE_SILENCE_MS = 3000L;
    private static final long COMPLETE_SILENCE_MS = 5000L;

    private final BrowserActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<WebView> attachedWebViews =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<WebView> documentStartWebViews =
            Collections.newSetFromMap(new WeakHashMap<>());

    private String polyfillScript;
    private boolean messageListenerAvailable;
    private boolean documentStartAvailable;

    private SpeechRecognizer recognizer;
    private WebView ownerWebView;
    private JavaScriptReplyProxy ownerReplyProxy;
    private String recognitionId;
    private String language;
    private boolean continuous;
    private boolean interimResults;
    private boolean stopRequested;
    private boolean startedEventSent;
    private boolean audioStarted;
    private int maxAlternatives;
    private int emptySessionRetries;
    private long singleShotDeadlineAt;

    private PendingStart pendingStart;

    BrowserSpeechController(BrowserActivity activity) {
        this.activity = activity;
        messageListenerAvailable =
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        documentStartAvailable =
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
    }

    void attach(WebView webView) {
        if (webView == null || attachedWebViews.contains(webView)) return;
        if (!messageListenerAvailable) return;

        String script = loadPolyfillScript();
        if (script == null || script.isEmpty()) return;

        try {
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, ORIGIN_RULES,
                    (view, message, sourceOrigin, isMainFrame, replyProxy) ->
                            handleMessage(view, message, sourceOrigin, isMainFrame, replyProxy));
            attachedWebViews.add(webView);
        } catch (RuntimeException ignored) {
            return;
        }
        if (documentStartAvailable) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, script, ORIGIN_RULES);
                documentStartWebViews.add(webView);
            } catch (RuntimeException ignored) { }
        }
    }

    void onPageStarted(WebView webView) {
        if (webView == null || documentStartWebViews.contains(webView)
                || !attachedWebViews.contains(webView)) return;
        String script = loadPolyfillScript();
        if (script == null || script.isEmpty()) return;
        try {
            webView.evaluateJavascript(script, null);
        } catch (RuntimeException ignored) { }
    }

    private String loadPolyfillScript() {
        if (polyfillScript != null) return polyfillScript;
        try (InputStream input = activity.getAssets().open("browser_speech_polyfill.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            polyfillScript = output.toString(StandardCharsets.UTF_8.name());
            return polyfillScript;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private void handleMessage(WebView view, WebMessageCompat message, Uri sourceOrigin,
            boolean isMainFrame, JavaScriptReplyProxy replyProxy) {
        if (view == null || message == null || replyProxy == null
                || message.getType() != WebMessageCompat.TYPE_STRING) return;

        String messageText = message.getData();
        if (messageText == null || messageText.length() > 4096) return;

        String origin = normalizeHttpsOrigin(sourceOrigin);
        String pageOrigin = normalizeHttpsOrigin(Uri.parse(
                view.getUrl() == null ? "" : view.getUrl()));
        if (origin == null || !origin.equals(pageOrigin) || view.getParent() == null
                || activity.isFinishing() || activity.isDestroyed()) return;

        try {
            JSONObject command = new JSONObject(messageText);
            String type = command.optString("type", "");
            String id = command.optString("id", "");
            if (id.isEmpty() || id.length() > 128) return;

            if ("start".equals(type)) {
                startRecognition(view, origin, id, command, replyProxy);
            } else if ("stop".equals(type)) {
                stopRecognition(view, id);
            } else if ("abort".equals(type)) {
                abortRecognition(view, id, true);
            }
        } catch (JSONException ignored) { }
    }

    private void startRecognition(WebView view, String origin, String id, JSONObject command,
            JavaScriptReplyProxy replyProxy) {
        if (ownerWebView != null || pendingStart != null) {
            sendError(replyProxy, id, "busy", "Speech recognition is already active");
            return;
        }
        PendingStart pending = new PendingStart(view, origin, id, command, replyProxy);
        if (!activity.isWebsiteSpeechMicrophoneAllowed(origin)) {
            pendingStart = pending;
            activity.requestWebsiteSpeechMicrophonePermission(origin, allowed -> {
                if (pendingStart != pending) return;
                pendingStart = null;
                if (!allowed) {
                    sendError(pending.replyProxy, pending.id, "not-allowed",
                            "Website microphone permission was not granted");
                } else {
                    continueStart(pending);
                }
            });
            return;
        }
        continueStart(pending);
    }

    private void continueStart(PendingStart pending) {
        if (pending.view.getParent() == null || activity.isFinishing() || activity.isDestroyed()) {
            sendError(pending.replyProxy, pending.id, "aborted", "The page is no longer active");
            return;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingStart = pending;
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_MICROPHONE);
            return;
        }
        beginRecognition(pending.view, pending.origin, pending.id,
                pending.command, pending.replyProxy);
    }

    boolean onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_MICROPHONE) return false;

        PendingStart pending = pendingStart;
        pendingStart = null;
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (pending == null) return true;
        if (!granted) {
            sendError(pending.replyProxy, pending.id, "not-allowed",
                    "Microphone permission was not granted");
        } else if (pending.view.getParent() != null && !activity.isFinishing()
                && !activity.isDestroyed()) {
            beginRecognition(pending.view, pending.origin, pending.id,
                    pending.command, pending.replyProxy);
        } else {
            sendError(pending.replyProxy, pending.id, "aborted",
                    "The page is no longer active");
        }
        return true;
    }

    private void beginRecognition(WebView view, String origin, String id, JSONObject command,
            JavaScriptReplyProxy replyProxy) {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            sendError(replyProxy, id, "service-not-allowed",
                    "No speech recognition service is available");
            return;
        }
        if (view.getParent() == null || !origin.equals(normalizeHttpsOrigin(
                Uri.parse(view.getUrl() == null ? "" : view.getUrl())))) {
            sendError(replyProxy, id, "aborted", "The page is no longer active");
            return;
        }

        ownerWebView = view;
        ownerReplyProxy = replyProxy;
        recognitionId = id;
        language = command.optString("lang", Locale.getDefault().toLanguageTag());
        if (language == null || language.trim().isEmpty() || language.length() > 64) {
            language = Locale.getDefault().toLanguageTag();
        }
        continuous = command.optBoolean("continuous", false);
        interimResults = command.optBoolean("interimResults", false);
        maxAlternatives = Math.max(1, Math.min(5, command.optInt("maxAlternatives", 1)));
        stopRequested = false;
        startedEventSent = false;
        audioStarted = false;
        emptySessionRetries = 0;
        singleShotDeadlineAt = SystemClock.elapsedRealtime()
                + SINGLE_SHOT_LISTEN_WINDOW_MS;

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    if (!isCurrentRecognizer()) return;
                    if (!startedEventSent) {
                        startedEventSent = true;
                        sendEvent("start", new JSONObject());
                    }
                    if (!audioStarted) {
                        audioStarted = true;
                        sendEvent("audiostart", new JSONObject());
                    }
                }

                @Override public void onBeginningOfSpeech() {
                    if (isCurrentRecognizer()) sendEvent("speechstart", new JSONObject());
                }

                @Override public void onRmsChanged(float rmsdB) { }

                @Override public void onBufferReceived(byte[] buffer) { }

                @Override public void onEndOfSpeech() {
                    if (isCurrentRecognizer()) sendEvent("speechend", new JSONObject());
                }

                @Override public void onError(int error) {
                    if (!isCurrentRecognizer()) return;
                    boolean emptyRecognition = error == SpeechRecognizer.ERROR_NO_MATCH
                            || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                    boolean retryContinuous = continuous && emptyRecognition
                            && emptySessionRetries < 2;
                    boolean retrySingleShot = !continuous && !stopRequested
                            && emptyRecognition
                            && SystemClock.elapsedRealtime() < singleShotDeadlineAt;
                    if (!stopRequested && (retryContinuous || retrySingleShot)) {
                        emptySessionRetries++;
                        endAudioSegment();
                        scheduleContinuousRestart();
                        return;
                    }
                    finishWithError(mapError(error), "Speech recognition failed");
                }

                @Override public void onResults(Bundle results) {
                    if (!isCurrentRecognizer()) return;
                    JSONArray alternatives = extractAlternatives(results);
                    if (alternatives.length() == 0) {
                        boolean retryContinuous = continuous && !stopRequested
                                && emptySessionRetries < 2;
                        boolean retrySingleShot = !continuous && !stopRequested
                                && SystemClock.elapsedRealtime() < singleShotDeadlineAt;
                        if (retryContinuous || retrySingleShot) {
                            emptySessionRetries++;
                            sendEvent("nomatch", new JSONObject());
                            endAudioSegment();
                            scheduleContinuousRestart();
                            return;
                        }
                        sendEvent("nomatch", new JSONObject());
                        finishNormally();
                        return;
                    }

                    JSONObject detail = new JSONObject();
                    try {
                        detail.put("alternatives", alternatives);
                        detail.put("isFinal", true);
                    } catch (JSONException ignored) { }
                    sendEvent("result", detail);
                    emptySessionRetries = 0;

                    if (continuous && !stopRequested) {
                        endAudioSegment();
                        scheduleContinuousRestart();
                    } else {
                        finishNormally();
                    }
                }

                @Override public void onPartialResults(Bundle partialResults) {
                    if (!isCurrentRecognizer() || !interimResults) return;
                    JSONArray alternatives = extractAlternatives(partialResults);
                    if (alternatives.length() == 0) return;
                    JSONObject detail = new JSONObject();
                    try {
                        detail.put("alternatives", alternatives);
                        detail.put("isFinal", false);
                    } catch (JSONException ignored) { }
                    sendEvent("result", detail);
                }

                @Override public void onEvent(int eventType, Bundle params) { }

                private boolean isCurrentRecognizer() {
                    return recognizer != null && ownerWebView == view
                            && id.equals(recognitionId);
                }
            });

            recognizer.startListening(buildRecognizerIntent());
        } catch (RuntimeException ex) {
            finishWithError("service-not-allowed", "Speech recognition could not be started");
        }
    }

    private Intent buildRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interimResults);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxAlternatives);
        // Keep short pauses from ending a voice-search session too early.
        // Recognition services may ignore these optional timing hints.
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                POSSIBLE_SILENCE_MS);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                COMPLETE_SILENCE_MS);
        return intent;
    }

    private JSONArray extractAlternatives(Bundle results) {
        JSONArray alternatives = new JSONArray();
        if (results == null) return alternatives;
        ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts == null) return alternatives;
        float[] confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        for (int i = 0; i < texts.size() && i < maxAlternatives; i++) {
            String transcript = texts.get(i);
            if (transcript == null) continue;
            JSONObject alternative = new JSONObject();
            try {
                alternative.put("transcript", transcript);
                float score = confidence != null && i < confidence.length ? confidence[i] : -1f;
                alternative.put("confidence", score < 0f ? 0f : Math.min(1f, score));
                alternatives.put(alternative);
            } catch (JSONException ignored) { }
        }
        return alternatives;
    }

    private void scheduleContinuousRestart() {
        handler.postDelayed(() -> {
            if (recognizer == null || ownerWebView == null || stopRequested) return;
            if (!continuous && SystemClock.elapsedRealtime() >= singleShotDeadlineAt) {
                sendEvent("nomatch", new JSONObject());
                finishNormally();
                return;
            }
            if (ownerWebView.getParent() == null || activity.isFinishing()
                    || activity.isDestroyed()) {
                abortRecognition(ownerWebView, recognitionId, true);
                return;
            }
            audioStarted = false;
            try {
                recognizer.startListening(buildRecognizerIntent());
            } catch (RuntimeException ex) {
                finishWithError("service-not-allowed",
                        "Speech recognition could not continue");
            }
        }, CONTINUOUS_RESTART_DELAY_MS);
    }

    private void stopRecognition(WebView view, String id) {
        if (cancelPendingStart(view, id, false)) return;
        if (!isOwner(view, id)) return;
        stopRequested = true;
        handler.removeCallbacksAndMessages(null);
        try {
            if (recognizer != null) recognizer.stopListening();
            else finishNormally();
        } catch (RuntimeException ignored) {
            finishNormally();
        }
    }

    private void abortRecognition(WebView view, String id, boolean reportEvent) {
        if (cancelPendingStart(view, id, reportEvent)) return;
        if (!isOwner(view, id)) return;
        SpeechRecognizer oldRecognizer = recognizer;
        recognizer = null;
        JavaScriptReplyProxy oldReply = ownerReplyProxy;
        String oldId = recognitionId;
        boolean hadAudioStarted = audioStarted;
        ownerWebView = null;
        ownerReplyProxy = null;
        recognitionId = null;
        audioStarted = false;
        handler.removeCallbacksAndMessages(null);

        if (oldRecognizer != null) {
            try { oldRecognizer.cancel(); } catch (RuntimeException ignored) { }
            try { oldRecognizer.destroy(); } catch (RuntimeException ignored) { }
        }
        if (reportEvent) {
            JSONObject detail = new JSONObject();
            try {
                detail.put("error", "aborted");
                detail.put("message", "Speech recognition was aborted");
            } catch (JSONException ignored) { }
            postToPage(oldReply, oldId, "error", detail);
            if (hadAudioStarted) postToPage(oldReply, oldId, "audioend", new JSONObject());
            postToPage(oldReply, oldId, "end", new JSONObject());
        }
    }

    private boolean cancelPendingStart(WebView view, String id, boolean reportError) {
        PendingStart pending = pendingStart;
        if (pending == null || pending.view != view || !pending.id.equals(id)) return false;
        pendingStart = null;
        if (reportError) {
            sendError(pending.replyProxy, pending.id, "aborted",
                    "Speech recognition was aborted");
        } else {
            postToPage(pending.replyProxy, pending.id, "end", new JSONObject());
        }
        return true;
    }

    private boolean isOwner(WebView view, String id) {
        return ownerWebView == view && recognitionId != null && recognitionId.equals(id);
    }

    private void endAudioSegment() {
        if (audioStarted) {
            audioStarted = false;
            sendEvent("audioend", new JSONObject());
        }
    }

    private void finishNormally() {
        endAudioSegment();
        sendEvent("end", new JSONObject());
        releaseSession();
    }

    private void finishWithError(String error, String message) {
        JSONObject detail = new JSONObject();
        try {
            detail.put("error", error);
            detail.put("message", message);
        } catch (JSONException ignored) { }
        sendEvent("error", detail);
        endAudioSegment();
        sendEvent("end", new JSONObject());
        releaseSession();
    }

    private void releaseSession() {
        handler.removeCallbacksAndMessages(null);
        SpeechRecognizer oldRecognizer = recognizer;
        recognizer = null;
        ownerWebView = null;
        ownerReplyProxy = null;
        recognitionId = null;
        stopRequested = false;
        startedEventSent = false;
        audioStarted = false;
        continuous = false;
        interimResults = false;
        if (oldRecognizer != null) {
            try { oldRecognizer.destroy(); } catch (RuntimeException ignored) { }
        }
    }

    private void sendEvent(String event, JSONObject detail) {
        JavaScriptReplyProxy reply = ownerReplyProxy;
        String id = recognitionId;
        if (reply == null || id == null) return;
        JSONObject message = new JSONObject();
        try {
            message.put("id", id);
            message.put("event", event);
            message.put("detail", detail == null ? new JSONObject() : detail);
            reply.postMessage(message.toString());
        } catch (JSONException | RuntimeException ignored) { }
    }

    private void sendError(JavaScriptReplyProxy reply, String id, String error, String message) {
        if (reply == null || id == null) return;
        JSONObject detail = new JSONObject();
        try {
            detail.put("error", error);
            detail.put("message", message);
        } catch (JSONException ignored) { }
        postToPage(reply, id, "error", detail);
        postToPage(reply, id, "end", new JSONObject());
    }

    private void postToPage(JavaScriptReplyProxy reply, String id, String event, JSONObject detail) {
        JSONObject message = new JSONObject();
        try {
            message.put("id", id);
            message.put("event", event);
            message.put("detail", detail == null ? new JSONObject() : detail);
            reply.postMessage(message.toString());
        } catch (JSONException | RuntimeException ignored) { }
    }

    private static String mapError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "audio-capture";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "not-allowed";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                return "network";
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "no-speech";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
                return "busy";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "language-not-supported";
            case SpeechRecognizer.ERROR_CLIENT:
                return "client";
            default:
                return "service-not-allowed";
        }
    }

    private static String normalizeHttpsOrigin(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getHost().trim().isEmpty()) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        return "https://" + host + (port >= 0 && port != 443 ? ":" + port : "");
    }

    void onNavigationStarted(WebView webView) {
        if (pendingStart != null && pendingStart.view == webView) {
            PendingStart old = pendingStart;
            pendingStart = null;
            sendError(old.replyProxy, old.id, "aborted", "The page navigated");
        }
        if (ownerWebView == webView && recognitionId != null) {
            abortRecognition(webView, recognitionId, true);
        }
    }

    void onTabHidden(WebView webView) {
        if (pendingStart != null && pendingStart.view == webView) {
            PendingStart old = pendingStart;
            pendingStart = null;
            sendError(old.replyProxy, old.id, "aborted", "The page is no longer active");
        }
        if (ownerWebView == webView && recognitionId != null) {
            abortRecognition(webView, recognitionId, true);
        }
    }

    void onWebViewDestroyed(WebView webView) {
        onTabHidden(webView);
        attachedWebViews.remove(webView);
        documentStartWebViews.remove(webView);
    }

    void onPause() {
        if (ownerWebView != null && recognitionId != null) {
            abortRecognition(ownerWebView, recognitionId, true);
        }
    }

    void destroy() {
        if (pendingStart != null) {
            PendingStart old = pendingStart;
            pendingStart = null;
            sendError(old.replyProxy, old.id, "aborted", "Browser was closed");
        }
        if (ownerWebView != null && recognitionId != null) {
            abortRecognition(ownerWebView, recognitionId, false);
        }
        handler.removeCallbacksAndMessages(null);
        attachedWebViews.clear();
        documentStartWebViews.clear();
    }

    private static final class PendingStart {
        final WebView view;
        final String origin;
        final String id;
        final JSONObject command;
        final JavaScriptReplyProxy replyProxy;

        PendingStart(WebView view, String origin, String id, JSONObject command,
                JavaScriptReplyProxy replyProxy) {
            this.view = view;
            this.origin = origin;
            this.id = id;
            this.command = command;
            this.replyProxy = replyProxy;
        }
    }
}
