package com.eyad.luma;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.WindowManager;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.util.Locale;

public final class CaptionService extends Service {
    public static final String ACTION_START = "com.eyad.luma.action.START";
    public static final String ACTION_STOP = "com.eyad.luma.action.STOP";
    public static final String ACTION_PAUSE_TOGGLE = "com.eyad.luma.action.PAUSE_TOGGLE";
    public static final String ACTION_STATUS = "com.eyad.luma.action.STATUS";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_TARGET_TAG = "target_tag";
    public static final String EXTRA_TARGET_LABEL = "target_label";
    public static final String EXTRA_FONT_SIZE = "font_size";
    public static final String EXTRA_FONT_FAMILY = "font_family";
    public static final String EXTRA_FONT_COLOR = "font_color";
    public static final String EXTRA_STATUS_TEXT = "status_text";
    public static final String EXTRA_RUNNING = "running";

    private static final String CHANNEL_ID = "luma_caption_session";
    private static final int NOTIFICATION_ID = 42;
    private static final int AUDIO_RATE = 16_000;
    private static final int AUDIO_FRAME_SAMPLES = 1_024;
    private static final int AUDIO_RECORD_BUFFER_SECONDS = 4;
    private static final long PARTIAL_TRANSLATION_INTERVAL_MS = 350L;
    private static final long FINAL_RETRY_DELAY_MS = 750L;
    private static final int MIN_PARTIAL_WORDS = 1;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable partialTranslationRunnable = this::translatePendingPartial;
    private final Runnable translationRetryRunnable = () -> {
        translationRetryScheduled = false;
        dispatchQueuedTranslation();
    };
    private final CaptionFlowQueue translationQueue = new CaptionFlowQueue();

    private volatile boolean stopping;
    private volatile boolean paused;
    private volatile AudioRecord audioRecord;
    private Thread captureThread;
    private MediaProjection projection;
    private OverlayController overlay;
    private Translator translator;
    private String targetTag = "ar";
    private String targetLabel = "Arabic";
    private int fontSizeSp = 22;
    private String fontFamily = "sans-serif";
    private int fontColor = 0xFFF4F7F6;
    private long lastPartialTranslationAt;
    private long latestCaptionSequence = -1L;
    private long sessionGeneration;
    private String lastSubmittedPartialText = "";
    private boolean translatorReady;
    private boolean translationUnavailable;
    private boolean translationInFlight;
    private boolean translationRetryScheduled;
    private boolean translationFailureBlocked;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession("Subtitles stopped");
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE_TOGGLE.equals(action)) {
            togglePause();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && captureThread == null) {
            startSession(intent);
        }
        return START_NOT_STICKY;
    }

    private void startSession(Intent intent) {
        stopping = false;
        paused = false;
        sessionGeneration++;
        translationQueue.clear();
        latestCaptionSequence = -1L;
        lastPartialTranslationAt = 0L;
        lastSubmittedPartialText = "";
        translatorReady = false;
        translationUnavailable = false;
        translationInFlight = false;
        translationRetryScheduled = false;
        translationFailureBlocked = false;
        mainHandler.removeCallbacks(translationRetryRunnable);
        targetTag = "ar";
        targetLabel = "Arabic";
        fontSizeSp = intent.getIntExtra(EXTRA_FONT_SIZE, 22);
        fontFamily = intent.getStringExtra(EXTRA_FONT_FAMILY);
        if (!"sans-serif".equals(fontFamily) && !"serif".equals(fontFamily)
                && !"monospace".equals(fontFamily)) fontFamily = "sans-serif";
        fontColor = intent.getIntExtra(EXTRA_FONT_COLOR, 0xFFF4F7F6);
        if (!isSupportedFontColor(fontColor)) fontColor = 0xFFF4F7F6;

        try {
            startForeground(NOTIFICATION_ID, makeNotification("Starting local captions…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultData == null) throw new IllegalStateException("Android screen-capture permission was not returned.");
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("Android did not grant playback capture.");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    stopSession("System playback-capture permission ended");
                }
            }, mainHandler);

            overlay = new OverlayController(this, fontSizeSp, fontFamily, fontColor);
            overlay.show();
            if (!android.provider.Settings.canDrawOverlays(this)) {
                throw new IllegalStateException("Allow Luma to display over other apps, then start again.");
            }
            prepareTranslator();
            sendStatus("Listening for English media audio", true);
            updateNotification("Listening for capturable media audio");
            captureThread = new Thread(this::captureAndTranscribe, "luma-playback-captions");
            captureThread.start();
        } catch (Exception exception) {
            String message = safeMessage(exception, "Could not start playback capture.");
            sendStatus(message, false);
            stopSession(message);
        }
    }

    private void captureAndTranscribe() {
        Model model = null;
        Recognizer recognizer = null;
        AudioRecord record = null;
        long noSignalSince = System.currentTimeMillis();
        boolean noSignalAnnounced = false;
        try {
            model = new Model(ModelInstaller.modelDirectory(this).getAbsolutePath());
            recognizer = new Recognizer(model, AUDIO_RATE);
            AudioPlaybackCaptureConfiguration captureConfig = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            int minimum = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) minimum = AUDIO_RATE * 2;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Playback audio permission was revoked. Grant it again and restart captions.");
            }
            record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    // Four seconds of native buffering absorbs short recognizer/GC stalls.
                    // Every positive read count is still passed to Vosk below.
                    .setBufferSizeInBytes(Math.max(minimum * 2,
                            AUDIO_RATE * 2 * AUDIO_RECORD_BUFFER_SECONDS))
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Android could not initialize the playback audio stream.");
            }
            audioRecord = record;
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Playback audio did not start. Check the source app and try again.");
            }
            sendStatus("Listening · English speech is processed on this device", true);
            short[] samples = new short[AUDIO_FRAME_SAMPLES];
            while (!stopping) {
                if (paused) {
                    Thread.sleep(80L);
                    continue;
                }
                int count = record.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (count < 0) {
                    if (stopping || paused) continue;
                    if (count == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw new IllegalStateException("The playback stream ended. Start a new caption session.");
                    }
                    throw new IllegalStateException("Playback audio read failed (" + count + "). Stop and restart captions.");
                }
                if (count == 0) {
                    continue;
                }
                long energy = 0;
                for (int i = 0; i < count; i++) energy += Math.abs((int) samples[i]);
                int average = (int) (energy / count);
                long now = System.currentTimeMillis();
                if (average > 45) {
                    noSignalSince = now;
                    noSignalAnnounced = false;
                } else if (!noSignalAnnounced && now - noSignalSince > 9_000L) {
                    noSignalAnnounced = true;
                    setOverlayState("NO AUDIO YET · SOURCE MAY BE MUTED OR BLOCKED");
                    sendStatus("No playback audio detected. The video may be paused/muted, or its app may block capture.", true);
                }

                boolean isFinal = recognizer.acceptWaveForm(samples, count);
                String json = isFinal ? recognizer.getResult() : recognizer.getPartialResult();
                String key = isFinal ? "text" : "partial";
                String text = new JSONObject(json).optString(key, "").trim();
                if (!text.isEmpty()) publishCaption(text, isFinal);
            }
            if (recognizer != null) {
                String last = new JSONObject(recognizer.getFinalResult()).optString("text", "").trim();
                if (!last.isEmpty()) publishCaption(last, true);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            String message = safeMessage(exception, "Playback capture could not continue.");
            mainHandler.post(() -> {
                if (!stopping) {
                    setOverlayState("CAPTURE UNAVAILABLE");
                    sendStatus(message, true);
                }
            });
        } finally {
            audioRecord = null;
            if (record != null) {
                try { if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop(); }
                catch (RuntimeException ignored) { }
                record.release();
            }
            if (recognizer != null) recognizer.close();
            if (model != null) model.close();
            if (!stopping) mainHandler.post(() -> stopSession("Playback stream ended"));
        }
    }

    private void publishCaption(String text, boolean isFinal) {
        mainHandler.post(() -> {
            if (stopping || overlay == null) return;
            if ("en".equals(targetTag) || "en-US".equals(targetTag)) {
                translationQueue.clear();
                mainHandler.removeCallbacks(partialTranslationRunnable);
                overlay.clearCaption();
                setOverlayState("CHOOSE A TRANSLATION LANGUAGE");
            } else if (translationUnavailable) {
                overlay.clearCaption();
                setOverlayState("TRANSLATION UNAVAILABLE · " + targetLabel.toUpperCase(Locale.ROOT));
            } else if (translator != null) {
                queueTranslation(text, isFinal);
                if (!translatorReady) {
                    setOverlayState("PREPARING " + targetLabel.toUpperCase(Locale.ROOT) + " TRANSLATION");
                }
            } else {
                overlay.clearCaption();
                setOverlayState("PREPARING " + targetLabel.toUpperCase(Locale.ROOT) + " TRANSLATION");
            }
        });
    }

    private void queueTranslation(String text, boolean isFinal) {
        if (text == null || text.trim().isEmpty()) return;
        String clean = text.trim();
        if (!isFinal && (wordCount(clean) < MIN_PARTIAL_WORDS
                || clean.equals(lastSubmittedPartialText)
                || clean.equals(translationQueue.latestPartialText()))) return;

        long sequence = translationQueue.offer(clean, isFinal);
        if (sequence < 0L) return;
        latestCaptionSequence = sequence;
        if (isFinal) {
            lastSubmittedPartialText = "";
            mainHandler.removeCallbacks(partialTranslationRunnable);
            if (overlay != null) overlay.clearCaption();
            dispatchQueuedTranslation();
        } else {
            schedulePartialTranslation();
        }
    }

    private void translatePendingPartial() {
        dispatchQueuedTranslation();
    }

    private void schedulePartialTranslation() {
        long now = android.os.SystemClock.uptimeMillis();
        long delay = Math.max(0L, PARTIAL_TRANSLATION_INTERVAL_MS - (now - lastPartialTranslationAt));
        mainHandler.removeCallbacks(partialTranslationRunnable);
        mainHandler.postDelayed(partialTranslationRunnable, delay);
    }

    private void dispatchQueuedTranslation() {
        if (stopping || overlay == null || !translatorReady || translator == null
                || translationInFlight || translationRetryScheduled || translationFailureBlocked
                || translationUnavailable) return;

        CaptionFlowQueue.Update update;
        if (translationQueue.hasFinals()) {
            mainHandler.removeCallbacks(partialTranslationRunnable);
            update = translationQueue.poll();
        } else {
            if (!translationQueue.hasPending()) return;
            long now = android.os.SystemClock.uptimeMillis();
            long delay = Math.max(0L, PARTIAL_TRANSLATION_INTERVAL_MS - (now - lastPartialTranslationAt));
            if (delay > 0L) {
                mainHandler.removeCallbacks(partialTranslationRunnable);
                mainHandler.postDelayed(partialTranslationRunnable, delay);
                return;
            }
            update = translationQueue.poll();
            if (update == null) return;
            lastSubmittedPartialText = update.text;
            lastPartialTranslationAt = now;
        }
        if (update != null) startTranslation(update);
    }

    private void startTranslation(CaptionFlowQueue.Update update) {
        if (update == null || update.text.trim().isEmpty() || translator == null || translationInFlight) return;
        final long activeSession = sessionGeneration;
        translationInFlight = true;
        setOverlayState(update.isFinal
                ? "FINAL TRANSLATION · " + targetLabel.toUpperCase(Locale.ROOT)
                : "LIVE TRANSLATION · " + targetLabel.toUpperCase(Locale.ROOT));
        try {
            translator.translate(update.text)
                .addOnSuccessListener(translated -> {
                    if (activeSession != sessionGeneration) return;
                    translationInFlight = false;
                    if (!stopping && overlay != null && update.sequence == latestCaptionSequence) {
                        String oneLine = translated == null ? "" : translated.replace('\n', ' ').replace('\r', ' ').trim();
                        if (!oneLine.isEmpty()) overlay.setCaption(oneLine);
                        else if (update.isFinal) overlay.clearCaption();
                        setOverlayState("TRANSLATION · " + targetLabel.toUpperCase(Locale.ROOT));
                    }
                    dispatchQueuedTranslation();
                })
                .addOnFailureListener(error -> handleTranslationFailure(update, activeSession, error));
        } catch (RuntimeException exception) {
            handleTranslationFailure(update, activeSession, exception);
        }
    }

    private void handleTranslationFailure(CaptionFlowQueue.Update update, long activeSession, Exception error) {
        if (activeSession != sessionGeneration) return;
        translationInFlight = false;
        boolean current = !stopping && overlay != null && update.sequence == latestCaptionSequence;
        if (update.isFinal) {
            translationQueue.requeueFirst(update);
            if (update.attempts == 0) {
                translationRetryScheduled = true;
                if (current) setOverlayState("RETRYING ARABIC TRANSLATION");
                mainHandler.postDelayed(translationRetryRunnable, FINAL_RETRY_DELAY_MS);
                return;
            }
            translationFailureBlocked = true;
            if (!stopping && overlay != null) {
                overlay.clearCaption();
                setOverlayState("TRANSLATION UNAVAILABLE · " + targetLabel.toUpperCase(Locale.ROOT));
                sendStatus("Arabic translation failed twice. Final phrases remain queued until this session ends; check the model before starting another session.", true);
            }
            return;
        }
        if (current) setOverlayState("TRANSLATION · " + targetLabel.toUpperCase(Locale.ROOT));
        dispatchQueuedTranslation();
    }

    private int wordCount(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }

    private boolean isSupportedFontColor(int color) {
        return color == 0xFFF4F7F6 || color == 0xFFFFD54F
                || color == 0xFF80DEEA || color == 0xFFA5D6A7;
    }

    private void prepareTranslator() {
        if ("en".equals(targetTag) || "en-US".equals(targetTag)) {
            translatorReady = true;
            return;
        }
        String source = TranslateLanguage.fromLanguageTag("en");
        String target = TranslateLanguage.fromLanguageTag(targetTag);
        if (source == null || target == null) {
            translationUnavailable = true;
            setOverlayState("ENGLISH CAPTIONS · TARGET LANGUAGE UNSUPPORTED");
            return;
        }
        translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build());
        DownloadConditions conditions = new DownloadConditions.Builder().requireWifi().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    translatorReady = true;
                    if (!stopping && overlay != null) {
                        setOverlayState("TRANSLATION · " + targetLabel.toUpperCase(Locale.ROOT));
                        sendStatus("On-device translation model ready", true);
                        dispatchQueuedTranslation();
                    }
                })
                .addOnFailureListener(error -> {
                    translationUnavailable = true;
                    if (!stopping && overlay != null) {
                        overlay.clearCaption();
                        setOverlayState("TRANSLATION NOT READY · " + targetLabel.toUpperCase(Locale.ROOT));
                        sendStatus("Translation model is not ready. The overlay will not fall back to showing the original; connect to Wi-Fi and prepare the model before restarting.", true);
                    }
                });
    }

    private void togglePause() {
        if (stopping || captureThread == null) return;
        paused = !paused;
        AudioRecord current = audioRecord;
        try {
            if (current != null) {
                if (paused && current.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) current.stop();
                else if (!paused && current.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) current.startRecording();
            }
        } catch (RuntimeException exception) {
            sendStatus("Could not resume the playback stream. Stop and start captions again.", true);
        }
        if (overlay != null) overlay.setPaused(paused);
        sendStatus(paused ? "Captions paused" : "Listening for English media audio", true);
        updateNotification(paused ? "Captions paused" : "Listening for capturable media audio");
    }

    private void stopSession(String reason) {
        if (stopping) return;
        stopping = true;
        paused = false;
        sessionGeneration++;
        mainHandler.removeCallbacks(partialTranslationRunnable);
        mainHandler.removeCallbacks(translationRetryRunnable);
        translationQueue.clear();
        latestCaptionSequence = -1L;
        translationInFlight = false;
        translationRetryScheduled = false;
        AudioRecord current = audioRecord;
        if (current != null) {
            try { if (current.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) current.stop(); }
            catch (RuntimeException ignored) { }
        }
        if (overlay != null) {
            overlay.dismiss();
            overlay = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (RuntimeException ignored) { }
            projection = null;
        }
        if (translator != null) {
            try { translator.close(); } catch (RuntimeException ignored) { }
            translator = null;
        }
        sendStatus(reason, false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void setOverlayState(String state) {
        mainHandler.post(() -> {
            if (overlay != null && !stopping) overlay.setState(state);
        });
    }

    private void sendStatus(String text, boolean running) {
        getSharedPreferences("luma_preferences", MODE_PRIVATE).edit()
                .putBoolean("session_running", running)
                .putString("session_status", text)
                .apply();
        Intent status = new Intent(ACTION_STATUS).setPackage(getPackageName());
        status.putExtra(EXTRA_STATUS_TEXT, text);
        status.putExtra(EXTRA_RUNNING, running);
        sendBroadcast(status);
    }

    private Notification makeNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent pause = new Intent(this, CaptionService.class).setAction(ACTION_PAUSE_TOGGLE);
        PendingIntent pausePending = PendingIntent.getService(this, 2, pause,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, CaptionService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 3, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_luma)
                .setContentTitle("Luma · floating subtitles")
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, paused ? "Resume" : "Pause", pausePending)
                .addAction(0, "Stop", stopPending)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, makeNotification(text));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Floating subtitles",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visible status and controls for the active playback caption session.");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private String safeMessage(Exception exception, String fallback) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) return fallback;
        return message;
    }

    @Override public void onDestroy() {
        stopSession("Subtitles stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
