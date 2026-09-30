package com.eyad.luma;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import android.media.projection.MediaProjectionManager;

public final class MainActivity extends Activity {
    private static final int REQ_MEDIA_PROJECTION = 101;
    private static final int REQ_AUDIO_PERMISSION = 102;
    private static final int REQ_NOTIFICATION_PERMISSION = 103;
    private static final String PREFS = "luma_preferences";
    private static final String KEY_FONT_SIZE = "font_size";
    private static final String KEY_FONT_FAMILY = "font_family";
    private static final String KEY_FONT_COLOR = "font_color";
    private static final String KEY_RUNNING = "session_running";
    private static final String KEY_STATUS = "session_status";

    private static final String[] FONT_FAMILIES = {"sans-serif", "serif", "monospace"};
    private static final String[] FONT_LABELS = {"SANS", "SERIF", "MONO"};
    private static final String[] COLOR_LABELS = {"WHITE", "GOLD", "CYAN", "MINT"};
    private static final int[] FONT_COLORS = {0xFFF4F7F6, 0xFFFFD54F, 0xFF80DEEA, 0xFFA5D6A7};
    private SharedPreferences preferences;
    private TextView statusLine;
    private TextView modelState;
    private TextView overlayState;
    private TextView permissionState;
    private TextView translationNote;
    private TextView startLabel;
    private Button modelButton;
    private Button startButton;
    private ProgressBar downloadProgress;
    private final TextView[] sizeChoices = new TextView[3];
    private final TextView[] familyChoices = new TextView[3];
    private final TextView[] colorChoices = new TextView[4];
    private int selectedFontSize = 22;
    private String selectedFontFamily = FONT_FAMILIES[0];
    private int selectedFontColor = FONT_COLORS[0];
    private boolean modelDownloadActive;
    private boolean pendingStart;
    private boolean projectionRequestInFlight;
    private boolean overlaySettingsOpened;
    private boolean notificationPermissionAsked;
    private boolean receiverRegistered;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!CaptionService.ACTION_STATUS.equals(intent.getAction())) return;
            String status = intent.getStringExtra(CaptionService.EXTRA_STATUS_TEXT);
            boolean running = intent.getBooleanExtra(CaptionService.EXTRA_RUNNING, false);
            preferences.edit().putBoolean(KEY_RUNNING, running).putString(KEY_STATUS, status).apply();
            refreshUi();
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        selectedFontSize = preferences.getInt(KEY_FONT_SIZE, 22);
        selectedFontFamily = preferences.getString(KEY_FONT_FAMILY, FONT_FAMILIES[0]);
        if (!isSupportedFontFamily(selectedFontFamily)) selectedFontFamily = FONT_FAMILIES[0];
        selectedFontColor = preferences.getInt(KEY_FONT_COLOR, FONT_COLORS[0]);
        if (!isSupportedFontColor(selectedFontColor)) selectedFontColor = FONT_COLORS[0];
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(11, 17, 23));
        window.setNavigationBarColor(Color.rgb(11, 17, 23));
        window.setNavigationBarContrastEnforced(false);
        window.setStatusBarContrastEnforced(false);
        buildScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        registerStatusReceiver();
        refreshUi();
        if (pendingStart && overlaySettingsOpened && !projectionRequestInFlight) {
            overlaySettingsOpened = false;
            if (Settings.canDrawOverlays(this)) {
                continueStartFlow();
            } else {
                pendingStart = false;
                setStatus("Overlay access was not enabled. You can grant it and try again.", false);
            }
        }
    }

    @Override protected void onPause() {
        if (receiverRegistered) {
            try { unregisterReceiver(statusReceiver); } catch (IllegalArgumentException ignored) { }
            receiverRegistered = false;
        }
        super.onPause();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(Color.rgb(11, 17, 23));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(18), dp(22), dp(34));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(brandRow, new LinearLayout.LayoutParams(-1, dp(42)));
        TextView brandDot = text("L", 15, 0xFF0B1716, true);
        brandDot.setGravity(Gravity.CENTER);
        brandDot.setBackground(roundRect(0xFF66E0C2, 0x00000000, dp(11)));
        brandRow.addView(brandDot, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView brand = text("  LUMA", 14, 0xFFEAF1EF, true);
        brand.setLetterSpacing(0.14f);
        brandRow.addView(brand);
        TextView version = text("  BETA  ·  ON-DEVICE", 9, 0xFF9CA9A7, true);
        version.setLetterSpacing(0.08f);
        version.setBackground(roundRect(0xFF172127, 0xFF29363C, dp(10)));
        version.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(-2, -2);
        versionLp.leftMargin = dp(10);
        brandRow.addView(version, versionLp);

        TextView headline = text("Subtitles that\nstay with you.", 32, 0xFFF1F6F4, true);
        headline.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams headlineLp = new LinearLayout.LayoutParams(-1, -2);
        headlineLp.topMargin = dp(27);
        page.addView(headline, headlineLp);
        TextView intro = text("Live translated captions over your video — speech recognition stays on-device.", 15, 0xFF9BA9A8, false);
        intro.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(-1, -2);
        introLp.topMargin = dp(10);
        introLp.bottomMargin = dp(22);
        page.addView(intro, introLp);

        LinearLayout sourceCard = card();
        page.addView(sourceCard, cardMargins(0, 0, 0, 14));
        addEyebrow(sourceCard, "AUDIO SOURCE");
        LinearLayout sourceRow = new LinearLayout(this);
        sourceRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sourceRowLp = new LinearLayout.LayoutParams(-1, -2);
        sourceRowLp.topMargin = dp(9);
        sourceCard.addView(sourceRow, sourceRowLp);
        TextView sourceIcon = text("≋", 22, 0xFF66E0C2, true);
        sourceIcon.setGravity(Gravity.CENTER);
        sourceIcon.setBackground(roundRect(0xFF1E3133, 0x00000000, dp(12)));
        sourceRow.addView(sourceIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout sourceCopy = new LinearLayout(this);
        sourceCopy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sourceCopyLp = new LinearLayout.LayoutParams(0, -2, 1f);
        sourceCopyLp.leftMargin = dp(12);
        sourceRow.addView(sourceCopy, sourceCopyLp);
        sourceCopy.addView(text("Media playback", 16, 0xFFF0F5F3, true));
        TextView sourceCaption = text("English audio · captured when allowed", 12, 0xFF91A09F, false);
        LinearLayout.LayoutParams sourceCaptionLp = new LinearLayout.LayoutParams(-1, -2);
        sourceCaptionLp.topMargin = dp(3);
        sourceCopy.addView(sourceCaption, sourceCaptionLp);
        TextView androidTen = text("ANDROID 10+", 9, 0xFF9DEBD7, true);
        androidTen.setLetterSpacing(0.04f);
        androidTen.setPadding(dp(8), dp(6), dp(8), dp(6));
        androidTen.setBackground(roundRect(0xFF1C3031, 0x00000000, dp(8)));
        sourceRow.addView(androidTen);
        TextView sourceExplain = text("Some video apps block playback capture. Luma never switches to microphone audio without telling you.", 12, 0xFF82918F, false);
        sourceExplain.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams sourceExplainLp = new LinearLayout.LayoutParams(-1, -2);
        sourceExplainLp.topMargin = dp(12);
        sourceCard.addView(sourceExplain, sourceExplainLp);

        LinearLayout languageCard = card();
        page.addView(languageCard, cardMargins(0, 0, 0, 14));
        addEyebrow(languageCard, "TRANSLATION");
        TextView languageTitle = text("English speech → Arabic subtitles", 16, 0xFFF0F5F3, true);
        LinearLayout.LayoutParams languageTitleLp = new LinearLayout.LayoutParams(-1, -2);
        languageTitleLp.topMargin = dp(9);
        languageCard.addView(languageTitle, languageTitleLp);
        translationNote = text("Arabic text only appears in the transparent subtitle line. The translation model downloads over Wi‑Fi and runs on-device.", 12, 0xFF879693, false);
        translationNote.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = dp(5);
        languageCard.addView(translationNote, noteLp);

        LinearLayout styleCard = card();
        page.addView(styleCard, cardMargins(0, 0, 0, 14));
        addEyebrow(styleCard, "CAPTION SIZE");
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sizeRowLp = new LinearLayout.LayoutParams(-1, -2);
        sizeRowLp.topMargin = dp(10);
        styleCard.addView(sizeRow, sizeRowLp);
        String[] sizeLabels = {"COMPACT", "STANDARD", "LARGE"};
        int[] sizes = {18, 22, 26};
        for (int i = 0; i < sizeLabels.length; i++) {
            final int selected = sizes[i];
            TextView choice = text(sizeLabels[i], 10, 0xFF9BA9A8, true);
            choice.setGravity(Gravity.CENTER);
            choice.setPadding(dp(6), dp(12), dp(6), dp(12));
            choice.setBackground(roundRect(0xFF182127, 0xFF29363C, dp(11)));
            choice.setClickable(true);
            choice.setFocusable(true);
            LinearLayout.LayoutParams choiceLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (i > 0) choiceLp.leftMargin = dp(7);
            sizeRow.addView(choice, choiceLp);
            sizeChoices[i] = choice;
            choice.setOnClickListener(v -> {
                selectedFontSize = selected;
                preferences.edit().putInt(KEY_FONT_SIZE, selected).apply();
                updateSizeChoices();
            });
        }
        updateSizeChoices();

        TextView typefaceTitle = text("CAPTION TYPEFACE", 10, 0xFF9BA9A8, true);
        LinearLayout.LayoutParams typefaceTitleLp = new LinearLayout.LayoutParams(-1, -2);
        typefaceTitleLp.topMargin = dp(15);
        styleCard.addView(typefaceTitle, typefaceTitleLp);
        LinearLayout familyRow = new LinearLayout(this);
        familyRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams familyRowLp = new LinearLayout.LayoutParams(-1, -2);
        familyRowLp.topMargin = dp(8);
        styleCard.addView(familyRow, familyRowLp);
        for (int i = 0; i < FONT_FAMILIES.length; i++) {
            final String family = FONT_FAMILIES[i];
            TextView choice = text(FONT_LABELS[i], 10, 0xFF9BA9A8, true);
            choice.setGravity(Gravity.CENTER);
            choice.setPadding(dp(6), dp(12), dp(6), dp(12));
            choice.setTypeface(Typeface.create(family, Typeface.BOLD));
            choice.setBackground(roundRect(0xFF182127, 0xFF29363C, dp(11)));
            choice.setClickable(true);
            choice.setFocusable(true);
            LinearLayout.LayoutParams choiceLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (i > 0) choiceLp.leftMargin = dp(7);
            familyRow.addView(choice, choiceLp);
            familyChoices[i] = choice;
            choice.setOnClickListener(v -> {
                selectedFontFamily = family;
                preferences.edit().putString(KEY_FONT_FAMILY, family).apply();
                updateTypefaceChoices();
            });
        }
        updateTypefaceChoices();

        TextView colorTitle = text("CAPTION COLOR", 10, 0xFF9BA9A8, true);
        LinearLayout.LayoutParams colorTitleLp = new LinearLayout.LayoutParams(-1, -2);
        colorTitleLp.topMargin = dp(15);
        styleCard.addView(colorTitle, colorTitleLp);
        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams colorRowLp = new LinearLayout.LayoutParams(-1, -2);
        colorRowLp.topMargin = dp(8);
        styleCard.addView(colorRow, colorRowLp);
        for (int i = 0; i < COLOR_LABELS.length; i++) {
            final int color = FONT_COLORS[i];
            TextView choice = text(COLOR_LABELS[i], 9, color, true);
            choice.setGravity(Gravity.CENTER);
            choice.setPadding(dp(3), dp(12), dp(3), dp(12));
            choice.setBackground(roundRect(0xFF182127, 0xFF29363C, dp(11)));
            choice.setClickable(true);
            choice.setFocusable(true);
            LinearLayout.LayoutParams choiceLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (i > 0) choiceLp.leftMargin = dp(6);
            colorRow.addView(choice, choiceLp);
            colorChoices[i] = choice;
            choice.setOnClickListener(v -> {
                selectedFontColor = color;
                preferences.edit().putInt(KEY_FONT_COLOR, color).apply();
                updateColorChoices();
            });
        }
        updateColorChoices();

        LinearLayout modelCard = card();
        page.addView(modelCard, cardMargins(0, 0, 0, 14));
        addEyebrow(modelCard, "LOCAL SPEECH MODEL");
        TextView modelTitle = text("English · offline recognition", 16, 0xFFF0F5F3, true);
        LinearLayout.LayoutParams modelTitleLp = new LinearLayout.LayoutParams(-1, -2);
        modelTitleLp.topMargin = dp(9);
        modelCard.addView(modelTitle, modelTitleLp);
        modelState = text("Small US English model · about 40 MB download", 12, 0xFF8F9D9A, false);
        LinearLayout.LayoutParams modelStateLp = new LinearLayout.LayoutParams(-1, -2);
        modelStateLp.topMargin = dp(4);
        modelCard.addView(modelState, modelStateLp);
        modelButton = button("Download speech model · 40 MB", false);
        LinearLayout.LayoutParams modelButtonLp = new LinearLayout.LayoutParams(-1, dp(48));
        modelButtonLp.topMargin = dp(12);
        modelCard.addView(modelButton, modelButtonLp);
        modelButton.setOnClickListener(v -> downloadModel());
        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setMax(100);
        downloadProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, dp(4));
        progressLp.topMargin = dp(8);
        modelCard.addView(downloadProgress, progressLp);

        LinearLayout accessCard = card();
        page.addView(accessCard, cardMargins(0, 0, 0, 17));
        addEyebrow(accessCard, "NEEDED TO FLOAT OVER OTHER APPS");
        overlayState = text("Overlay access · not yet enabled", 14, 0xFFEAF1EF, true);
        LinearLayout.LayoutParams permissionLp = new LinearLayout.LayoutParams(-1, -2);
        permissionLp.topMargin = dp(11);
        accessCard.addView(overlayState, permissionLp);
        permissionState = text("Playback audio permission · required", 13, 0xFFB7C2C0, true);
        LinearLayout.LayoutParams audioPermissionLp = new LinearLayout.LayoutParams(-1, -2);
        audioPermissionLp.topMargin = dp(7);
        accessCard.addView(permissionState, audioPermissionLp);
        TextView permissionCopy = text("Android asks you to allow drawing over apps and to approve playback capture each session.", 12, 0xFF879693, false);
        permissionCopy.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams permissionCopyLp = new LinearLayout.LayoutParams(-1, -2);
        permissionCopyLp.topMargin = dp(4);
        accessCard.addView(permissionCopy, permissionCopyLp);

        startButton = button("Start floating subtitles", true);
        page.addView(startButton, new LinearLayout.LayoutParams(-1, dp(56)));
        startLabel = text("", 12, 0xFF9AA9A6, false);
        startLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams startLabelLp = new LinearLayout.LayoutParams(-1, -2);
        startLabelLp.topMargin = dp(11);
        startLabelLp.bottomMargin = dp(12);
        page.addView(startLabel, startLabelLp);
        startButton.setOnClickListener(v -> onStartPressed());

        LinearLayout privacyCard = new LinearLayout(this);
        privacyCard.setOrientation(LinearLayout.VERTICAL);
        privacyCard.setPadding(dp(15), dp(13), dp(15), dp(13));
        privacyCard.setBackground(roundRect(0xFF111A20, 0xFF1E2A30, dp(15)));
        page.addView(privacyCard, cardMargins(0, 0, 0, 0));
        addEyebrow(privacyCard, "PRIVATE BY DESIGN");
        TextView privacy = text("Speech recognition is local after the English model is downloaded. Luma does not save or upload captured audio. Translation language packs are downloaded from Google ML Kit over Wi‑Fi and then run on-device.", 12, 0xFF879693, false);
        privacy.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams privacyLp = new LinearLayout.LayoutParams(-1, -2);
        privacyLp.topMargin = dp(6);
        privacyCard.addView(privacy, privacyLp);

        statusLine = text("Ready when you are.", 12, 0xFF9DEBD7, false);
        statusLine.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(17);
        page.addView(statusLine, statusLp);
        setContentView(scroll);
        refreshUi();
    }

    private void onStartPressed() {
        if (preferences.getBoolean(KEY_RUNNING, false)) {
            sendServiceAction(CaptionService.ACTION_STOP);
            return;
        }
        pendingStart = true;
        continueStartFlow();
    }

    private void continueStartFlow() {
        if (!pendingStart || isFinishing() || projectionRequestInFlight) return;
        if (!ModelInstaller.isInstalled(this)) {
            if (!modelDownloadActive) downloadModel();
            setStatus("First, download the local English speech model (about 40 MB).", false);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_PERMISSION);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermissionSettings();
            setStatus("Allow Luma to display over other apps, then return here.", false);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionAsked
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION_PERMISSION);
            return;
        }
        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projectionRequestInFlight = true;
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    private void openOverlayPermissionSettings() {
        overlaySettingsOpened = true;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (RuntimeException exception) {
            overlaySettingsOpened = false;
            setStatus("Open Android Settings → Special app access → Display over other apps for Luma.", false);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_MEDIA_PROJECTION) return;
        projectionRequestInFlight = false;
        if (resultCode != RESULT_OK || data == null) {
            pendingStart = false;
            setStatus("Playback capture was not approved. You can try again whenever you’re ready.", false);
            refreshUi();
            return;
        }
        pendingStart = false;
        String tag = "ar";
        String label = "Arabic";
        Intent service = new Intent(this, CaptionService.class).setAction(CaptionService.ACTION_START);
        service.putExtra(CaptionService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptionService.EXTRA_RESULT_DATA, data);
        service.putExtra(CaptionService.EXTRA_TARGET_TAG, tag);
        service.putExtra(CaptionService.EXTRA_TARGET_LABEL, label);
        service.putExtra(CaptionService.EXTRA_FONT_SIZE, selectedFontSize);
        service.putExtra(CaptionService.EXTRA_FONT_FAMILY, selectedFontFamily);
        service.putExtra(CaptionService.EXTRA_FONT_COLOR, selectedFontColor);
        startForegroundService(service);
        setStatus("Starting playback capture…", true);
        refreshUi();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setStatus("Audio access ready. Android will still ask you to approve each capture session.", true);
                continueStartFlow();
            } else {
                pendingStart = false;
                setStatus("Playback capture needs Android’s audio permission to read the media stream.", false);
            }
        } else if (requestCode == REQ_NOTIFICATION_PERMISSION) {
            notificationPermissionAsked = true;
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                setStatus("Notifications are off. The session can still run; Android will show its foreground-service status in system task controls.", false);
            }
            continueStartFlow();
        }
        refreshUi();
    }

    private void downloadModel() {
        if (modelDownloadActive || ModelInstaller.isInstalled(this)) {
            refreshUi();
            return;
        }
        modelDownloadActive = true;
        downloadProgress.setVisibility(View.VISIBLE);
        modelButton.setEnabled(false);
        modelButton.setText("Downloading local model…");
        setStatus("Downloading the small English model from Vosk. This is a one-time download.", false);
        Thread worker = new Thread(() -> {
            try {
                ModelInstaller.downloadAndInstall(this, percent -> runOnUiThread(() -> {
                    downloadProgress.setProgress(percent);
                    modelButton.setText("Downloading local model · " + percent + "%");
                }));
                runOnUiThread(() -> {
                    modelDownloadActive = false;
                    downloadProgress.setVisibility(View.GONE);
                    modelButton.setEnabled(true);
                    setStatus("English speech model ready · recognition runs locally.", true);
                    refreshUi();
                    if (pendingStart) continueStartFlow();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    modelDownloadActive = false;
                    downloadProgress.setVisibility(View.GONE);
                    modelButton.setEnabled(true);
                    modelButton.setText("Retry speech model download · 40 MB");
                    setStatus("Model download failed: " + safeMessage(exception), false);
                    refreshUi();
                });
            }
        }, "luma-model-download");
        worker.start();
    }

    private void refreshUi() {
        if (statusLine == null) return;
        boolean modelReady = ModelInstaller.isInstalled(this);
        boolean overlayAllowed = Settings.canDrawOverlays(this);
        boolean audioAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean running = preferences.getBoolean(KEY_RUNNING, false);
        modelState.setText(modelReady ? "Ready on this device · no speech audio is sent to a server" : "Small US English model · about 40 MB download");
        modelButton.setText(modelDownloadActive ? "Downloading local model…" : modelReady ? "English model ready  ✓" : "Download speech model · 40 MB");
        modelButton.setEnabled(!modelDownloadActive && !modelReady);
        overlayState.setText(overlayAllowed ? "Overlay access · enabled" : "Overlay access · not yet enabled");
        overlayState.setText(overlayAllowed ? "✓  Overlay access enabled" : "Overlay access · required");
        permissionState.setText(audioAllowed ? "✓  Playback audio permission enabled" : "Playback audio permission · required");
        translationNote.setText("Arabic is translated on-device; the overlay shows only the translated subtitle. Its model downloads over Wi‑Fi.");
        startButton.setText(running ? "Stop floating subtitles" : modelReady ? "Start floating subtitles" : "Get ready · download speech model");
        startLabel.setText(running
                ? preferences.getString(KEY_STATUS, "Listening for English media audio")
                : "English input only in this MVP · source apps can block capture.");
        startLabel.setTextColor(running ? 0xFF9DEBD7 : 0xFF82918F);
        updateSizeChoices();
        updateTypefaceChoices();
        updateColorChoices();
        if (!modelReady && !modelDownloadActive) downloadProgress.setVisibility(View.GONE);
    }

    private void updateSizeChoices() {
        if (sizeChoices[0] == null) return;
        int[] sizes = {18, 22, 26};
        for (int i = 0; i < sizes.length; i++) {
            boolean selected = sizes[i] == selectedFontSize;
            sizeChoices[i].setTextColor(selected ? 0xFF9DEBD7 : 0xFF9BA9A8);
            sizeChoices[i].setBackground(roundRect(selected ? 0xFF203A38 : 0xFF182127,
                    selected ? 0xFF4B9A87 : 0xFF29363C, dp(11)));
        }
    }

    private void updateTypefaceChoices() {
        if (familyChoices[0] == null) return;
        for (int i = 0; i < FONT_FAMILIES.length; i++) {
            boolean selected = FONT_FAMILIES[i].equals(selectedFontFamily);
            familyChoices[i].setTextColor(selected ? 0xFF9DEBD7 : 0xFF9BA9A8);
            familyChoices[i].setBackground(roundRect(selected ? 0xFF203A38 : 0xFF182127,
                    selected ? 0xFF4B9A87 : 0xFF29363C, dp(11)));
        }
    }

    private void updateColorChoices() {
        if (colorChoices[0] == null) return;
        for (int i = 0; i < FONT_COLORS.length; i++) {
            boolean selected = FONT_COLORS[i] == selectedFontColor;
            colorChoices[i].setBackground(roundRect(selected ? 0xFF203A38 : 0xFF182127,
                    selected ? 0xFF4B9A87 : 0xFF29363C, dp(11)));
        }
    }

    private boolean isSupportedFontFamily(String family) {
        for (String supported : FONT_FAMILIES) if (supported.equals(family)) return true;
        return false;
    }

    private boolean isSupportedFontColor(int color) {
        for (int supported : FONT_COLORS) if (supported == color) return true;
        return false;
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStatusReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(CaptionService.ACTION_STATUS);
        // Android 10–12 lack the exported-state overload; Android 13+ uses an explicitly private receiver.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, filter);
        receiverRegistered = true;
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, CaptionService.class).setAction(action);
        startService(intent);
    }

    private void setStatus(String message, boolean persist) {
        if (statusLine != null) statusLine.setText(message);
        if (persist) preferences.edit().putString(KEY_STATUS, message).apply();
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? "please check your connection and storage" : message;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(15), dp(16), dp(15));
        layout.setBackground(roundRect(0xFF131D23, 0xFF243138, dp(17)));
        return layout;
    }

    private void addEyebrow(LinearLayout parent, String value) {
        TextView eyebrow = text(value, 10, 0xFF83A39C, true);
        eyebrow.setLetterSpacing(0.1f);
        parent.addView(eyebrow, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(true);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        button.setTextColor(primary ? 0xFF09201B : 0xFFDBE7E4);
        button.setBackground(roundRect(primary ? 0xFF66E0C2 : 0xFF213037,
                primary ? 0xFF66E0C2 : 0xFF34454A, dp(14)));
        button.setMinHeight(dp(46));
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private GradientDrawable roundRect(int fill, int stroke, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radius);
        if (Color.alpha(stroke) != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private LinearLayout.LayoutParams cardMargins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
