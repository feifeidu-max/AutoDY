package com.autody.blinkscroll;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

public final class DebugLog {
    public static final String ACTION_LOGS_CHANGED = "com.autody.blinkscroll.LOGS_CHANGED";

    private static final String PREFS_NAME = "debug_log";
    private static final String KEY_LINES = "lines";
    private static final int MAX_LINES = 180;
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private DebugLog() {
    }

    public static synchronized void add(Context context, String message) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String existing = prefs.getString(KEY_LINES, "");
        ArrayDeque<String> lines = new ArrayDeque<>();

        if (!existing.isEmpty()) {
            String[] split = existing.split("\\n");
            for (String line : split) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }

        lines.add(TIME_FORMAT.format(new Date()) + "  " + message);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }

        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }

        prefs.edit().putString(KEY_LINES, builder.toString()).apply();
        Intent intent = new Intent(ACTION_LOGS_CHANGED).setPackage(appContext.getPackageName());
        appContext.sendBroadcast(intent);
    }

    public static synchronized String read(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LINES, "No logs yet.");
    }

    public static synchronized void clear(Context context) {
        Context appContext = context.getApplicationContext();
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LINES)
                .apply();
        Intent intent = new Intent(ACTION_LOGS_CHANGED).setPackage(appContext.getPackageName());
        appContext.sendBroadcast(intent);
    }
}
