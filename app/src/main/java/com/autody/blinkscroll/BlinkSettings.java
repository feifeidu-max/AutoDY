package com.autody.blinkscroll;

import android.content.Context;

public final class BlinkSettings {
    public static final int DEFAULT_BLINK_GAP_MS = 1150;
    public static final int MIN_BLINK_GAP_MS = 500;
    public static final int MAX_BLINK_GAP_MS = 3000;
    public static final int BLINK_GAP_STEP_MS = 100;
    public static final int DEFAULT_FRAME_INTERVAL_MS = 60;
    public static final int MIN_FRAME_INTERVAL_MS = 40;
    public static final int MAX_FRAME_INTERVAL_MS = 300;
    public static final int FRAME_INTERVAL_STEP_MS = 10;

    private static final String PREFS_NAME = "blink_settings";
    private static final String KEY_BLINK_GAP_MS = "blink_gap_ms";
    private static final String KEY_FRAME_INTERVAL_MS = "frame_interval_ms";

    private BlinkSettings() {
    }

    public static int getBlinkGapMs(Context context) {
        int value = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_BLINK_GAP_MS, DEFAULT_BLINK_GAP_MS);
        return clampToStep(value, MIN_BLINK_GAP_MS, MAX_BLINK_GAP_MS, BLINK_GAP_STEP_MS);
    }

    public static void setBlinkGapMs(Context context, int value) {
        int clamped = clampToStep(value, MIN_BLINK_GAP_MS, MAX_BLINK_GAP_MS, BLINK_GAP_STEP_MS);
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_BLINK_GAP_MS, clamped)
                .apply();
    }

    public static int getSeriesWindowMs(Context context) {
        return getSeriesWindowMs(getBlinkGapMs(context));
    }

    public static int getSeriesWindowMs(int blinkGapMs) {
        return Math.max(1800, blinkGapMs * 2 + 500);
    }

    public static int progressToBlinkGapMs(int progress) {
        return MIN_BLINK_GAP_MS + progress * BLINK_GAP_STEP_MS;
    }

    public static int blinkGapMsToProgress(int blinkGapMs) {
        return (clampToStep(blinkGapMs, MIN_BLINK_GAP_MS, MAX_BLINK_GAP_MS, BLINK_GAP_STEP_MS)
                - MIN_BLINK_GAP_MS) / BLINK_GAP_STEP_MS;
    }

    public static int maxBlinkGapProgress() {
        return (MAX_BLINK_GAP_MS - MIN_BLINK_GAP_MS) / BLINK_GAP_STEP_MS;
    }

    public static int getFrameIntervalMs(Context context) {
        int value = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_FRAME_INTERVAL_MS, DEFAULT_FRAME_INTERVAL_MS);
        return clampToStep(value, MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS, FRAME_INTERVAL_STEP_MS);
    }

    public static void setFrameIntervalMs(Context context, int value) {
        int clamped = clampToStep(value, MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS, FRAME_INTERVAL_STEP_MS);
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_FRAME_INTERVAL_MS, clamped)
                .apply();
    }

    public static int progressToFrameIntervalMs(int progress) {
        return MIN_FRAME_INTERVAL_MS + progress * FRAME_INTERVAL_STEP_MS;
    }

    public static int frameIntervalMsToProgress(int frameIntervalMs) {
        return (clampToStep(frameIntervalMs, MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS,
                FRAME_INTERVAL_STEP_MS) - MIN_FRAME_INTERVAL_MS) / FRAME_INTERVAL_STEP_MS;
    }

    public static int maxFrameIntervalProgress() {
        return (MAX_FRAME_INTERVAL_MS - MIN_FRAME_INTERVAL_MS) / FRAME_INTERVAL_STEP_MS;
    }

    private static int clampToStep(int value, int min, int max, int step) {
        int clamped = Math.max(min, Math.min(max, value));
        int offset = clamped - min;
        int roundedOffset = Math.round(offset / (float) step) * step;
        return min + roundedOffset;
    }
}
