package com.eyad.luma;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public final class OverlayController {
    private final Context context;
    private final WindowManager windowManager;
    private final int fontSizeSp;
    private final String fontFamily;
    private final int fontColor;
    private WindowManager.LayoutParams params;
    private TextView captionText;
    private boolean attached;

    public OverlayController(Context context, int fontSizeSp, String fontFamily, int fontColor) {
        this.context = context;
        this.fontSizeSp = fontSizeSp;
        this.fontFamily = fontFamily;
        this.fontColor = fontColor;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show() {
        if (attached || !android.provider.Settings.canDrawOverlays(context)) return;
        int width = Math.max(dp(200), screenWidth() - dp(24));

        captionText = new TextView(context);
        captionText.setTextSize(fontSizeSp);
        captionText.setTextColor(fontColor);
        captionText.setTypeface(Typeface.create(fontFamily, Typeface.BOLD));
        captionText.setGravity(Gravity.CENTER);
        captionText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        captionText.setTextDirection(View.TEXT_DIRECTION_RTL);
        captionText.setMaxLines(1);
        captionText.setEllipsize(TextUtils.TruncateAt.END);
        captionText.setIncludeFontPadding(true);
        captionText.setPadding(dp(10), dp(3), dp(10), dp(3));
        captionText.setBackgroundColor(Color.TRANSPARENT);
        captionText.setShadowLayer(dp(6), 0, dp(2), 0xF0000000);
        captionText.setContentDescription("Arabic translated subtitle");
        captionText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        params = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(72);
        params.setTitle("Luma Arabic subtitle");
        try {
            windowManager.addView(captionText, params);
            attached = true;
        } catch (RuntimeException ignored) {
            attached = false;
        }
    }

    public void setState(String ignored) {
        // Status remains in the app and foreground notification, never in the subtitle overlay.
    }

    public void setCaption(String text) {
        if (captionText != null && text != null) {
            captionText.setText(text.replaceAll("\\s+", " ").trim());
        }
    }

    public void clearCaption() {
        if (captionText != null) captionText.setText("");
    }

    public void setPaused(boolean ignored) {
        // The overlay is deliberately text-only; pause/resume remains available in system controls.
    }

    public void dismiss() {
        if (attached && captionText != null) {
            try { windowManager.removeView(captionText); } catch (RuntimeException ignored) { }
        }
        attached = false;
        captionText = null;
    }

    private int screenWidth() {
        return context.getResources().getDisplayMetrics().widthPixels;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
