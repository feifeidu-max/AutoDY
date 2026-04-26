package com.autody.blinkscroll;

import android.content.Context;

public final class BlinkSettings {
    public static final int MODE_MOUTH = 0;
    public static final int MODE_SOUND = 1;
    public static final int DEFAULT_DETECTION_MODE = MODE_MOUTH;
    public static final int DEFAULT_MOUTH_INTERVAL_MS = 1000;
    public static final int MIN_MOUTH_INTERVAL_MS = 1000;
    public static final int MAX_MOUTH_INTERVAL_MS = 5000;
    public static final int MOUTH_INTERVAL_STEP_MS = 250;

    public static final int DEFAULT_BLINK_GAP_MS = 1150;
    public static final int MIN_BLINK_GAP_MS = 500;
    public static final int MAX_BLINK_GAP_MS = 3000;
    public static final int BLINK_GAP_STEP_MS = 100;
    public static final int DEFAULT_FRAME_INTERVAL_MS = 60;
    public static final int MIN_FRAME_INTERVAL_MS = 40;
    public static final int MAX_FRAME_INTERVAL_MS = 300;
    public static final int FRAME_INTERVAL_STEP_MS = 10;
    public static final int DEFAULT_SOUND_THRESHOLD = 2500;
    public static final int MIN_SOUND_THRESHOLD = 100;
    public static final int MAX_SOUND_THRESHOLD = 10000;
    public static final int SOUND_THRESHOLD_STEP = 100;
    public static final int DEFAULT_SOUND_INTERVAL_MS = 20;
    public static final int MIN_SOUND_INTERVAL_MS = 10;
    public static final int MAX_SOUND_INTERVAL_MS = 2000;
    public static final int SOUND_INTERVAL_STEP_MS = 10;

    private static final String PREFS_NAME = "blink_settings";
    private static final String KEY_DETECTION_MODE = "detection_mode";
    private static final String KEY_MOUTH_INTERVAL_MS = "mouth_interval_ms";
    private static final String KEY_BLINK_GAP_MS = "blink_gap_ms";
    private static final String KEY_FRAME_INTERVAL_MS = "frame_interval_ms";
    private static final String KEY_SOUND_THRESHOLD = "sound_threshold";
    private static final String KEY_SOUND_INTERVAL_MS = "sound_interval_ms";

    private BlinkSettings() {
    }

    public static int getDetectionMode(Context context) {
        int value = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_DETECTION_MODE, DEFAULT_DETECTION_MODE);
        return value == MODE_SOUND ? MODE_SOUND : MODE_MOUTH;
    }

    public static void setDetectionMode(Context context, int mode) {
        int value = mode == MODE_SOUND ? MODE_SOUND : MODE_MOUTH;
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_DETECTION_MODE, value)
                .apply();
    }

    public static String getDetectionModeLabel(Context context) {
        return getDetectionMode(context) == MODE_SOUND ? "声音识别" : "张嘴识别";
    }

    public static int getMouthIntervalMs(Context context) {
        int value = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_MOUTH_INTERVAL_MS, DEFAULT_MOUTH_INTERVAL_MS);
        return clampToStep(value, MIN_MOUTH_INTERVAL_MS, MAX_MOUTH_INTERVAL_MS,
                MOUTH_INTERVAL_STEP_MS);
    }

    public static void setMouthIntervalMs(Context context, int value) {
        int clamped = clampToStep(value, MIN_MOUTH_INTERVAL_MS, MAX_MOUTH_INTERVAL_MS,
                MOUTH_INTERVAL_STEP_MS);
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_MOUTH_INTERVAL_MS, clamped)
                .apply();
    }

    public static int progressToMouthIntervalMs(int progress) {
        return MIN_MOUTH_INTERVAL_MS + progress * MOUTH_INTERVAL_STEP_MS;
    }

    public static int mouthIntervalMsToProgress(int intervalMs) {
        return (clampToStep(intervalMs, MIN_MOUTH_INTERVAL_MS, MAX_MOUTH_INTERVAL_MS,
                MOUTH_INTERVAL_STEP_MS) - MIN_MOUTH_INTERVAL_MS) / MOUTH_INTERVAL_STEP_MS;
    }

    public static int maxMouthIntervalProgress() {
        return (MAX_MOUTH_INTERVAL_MS - MIN_MOUTH_INTERVAL_MS) / MOUTH_INTERVAL_STEP_MS;
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

    public static int getSoundThreshold(Context context) {
        int value = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_SOUND_THRESHOLD, DEFAULT_SOUND_THRESHOLD);
        return clampToStep(value, MIN_SOUND_THRESHOLD, MAX_SOUND_THRESHOLD, SOUND_THRESHOLD_STEP);
    }

    public static void setSoundThreshold(Context context, int value) {
        int clamped = clampToStep(value, MIN_SOUND_THRESHOLD, MAX_SOUND_THRESHOLD,
                SOUND_THRESHOLD_STEP);
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SOUND_THRESHOLD, clamped)
                .apply();
    }

    public static int progressToSoundThreshold(int progress) {
        return MIN_SOUND_THRESHOLD + progress * SOUND_THRESHOLD_STEP;
    }

    public static int soundThresholdToProgress(int threshold) {
        return (clampToStep(threshold, MIN_SOUND_THRESHOLD, MAX_SOUND_THRESHOLD,
                SOUND_THRESHOLD_STEP) - MIN_SOUND_THRESHOLD) / SOUND_THRESHOLD_STEP;
    }

    public static int maxSoundThresholdProgress() {
        return (MAX_SOUND_THRESHOLD - MIN_SOUND_THRESHOLD) / SOUND_THRESHOLD_STEP;
    }

    public static int getSoundIntervalMs(Context context) {
        int value = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_SOUND_INTERVAL_MS, DEFAULT_SOUND_INTERVAL_MS);
        return clampToStep(value, MIN_SOUND_INTERVAL_MS, MAX_SOUND_INTERVAL_MS,
                SOUND_INTERVAL_STEP_MS);
    }

    public static void setSoundIntervalMs(Context context, int value) {
        int clamped = clampToStep(value, MIN_SOUND_INTERVAL_MS, MAX_SOUND_INTERVAL_MS,
                SOUND_INTERVAL_STEP_MS);
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SOUND_INTERVAL_MS, clamped)
                .apply();
    }

    public static int progressToSoundIntervalMs(int progress) {
        return MIN_SOUND_INTERVAL_MS + progress * SOUND_INTERVAL_STEP_MS;
    }

    public static int soundIntervalMsToProgress(int value) {
        return (clampToStep(value, MIN_SOUND_INTERVAL_MS, MAX_SOUND_INTERVAL_MS,
                SOUND_INTERVAL_STEP_MS) - MIN_SOUND_INTERVAL_MS) / SOUND_INTERVAL_STEP_MS;
    }

    public static int maxSoundIntervalProgress() {
        return (MAX_SOUND_INTERVAL_MS - MIN_SOUND_INTERVAL_MS) / SOUND_INTERVAL_STEP_MS;
    }

    private static int clampToStep(int value, int min, int max, int step) {
        int clamped = Math.max(min, Math.min(max, value));
        int offset = clamped - min;
        int roundedOffset = Math.round(offset / (float) step) * step;
        return min + roundedOffset;
    }
}
