package com.autody.blinkscroll;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 42;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshLogs();
        }
    };
    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            refreshLogs();
            uiHandler.postDelayed(this, 1000);
        }
    };

    private TextView statusView;
    private TextView blinkGapValueView;
    private TextView frameIntervalValueView;
    private TextView logView;
    private Button startButton;
    private boolean startAfterPermission;
    private boolean logReceiverRegistered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        DebugLog.add(this, "MainActivity opened");
        refreshStatus();
        refreshLogs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerLogReceiver();
        uiHandler.removeCallbacks(periodicRefresh);
        uiHandler.post(periodicRefresh);
        refreshStatus();
        refreshLogs();
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(periodicRefresh);
        unregisterLogReceiver();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        DebugLog.add(this, "Permission result: camera=" + hasCameraPermission()
                + " notification=" + hasNotificationPermission());
        if (requestCode == REQUEST_PERMISSIONS && startAfterPermission) {
            startAfterPermission = false;
            if (hasCameraPermission()) {
                startDetection();
            }
        }
        refreshStatus();
    }

    private View buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(247, 248, 250));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = dp(24);
        content.setPadding(padding, dp(32), padding, dp(32));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("AutoDY");
        title.setTextColor(Color.rgb(17, 24, 39));
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("\u4e09\u8fde\u7728\u773c\uff0c\u4e0a\u6ed1\u5237\u4e0b\u4e00\u6761");
        subtitle.setTextColor(Color.rgb(75, 85, 99));
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.setMargins(0, dp(8), 0, dp(24));
        content.addView(subtitle, subtitleParams);

        statusView = panelText(15, false);
        content.addView(statusView, matchWrap());

        addSettingsPanel(content);

        startButton = primaryButton("\u5f00\u59cb\u8bc6\u522b");
        startButton.setOnClickListener(view -> {
            DebugLog.add(this, "Start button tapped");
            if (!hasCameraPermission() || !hasNotificationPermission()) {
                startAfterPermission = true;
                requestNeededPermissions();
            } else {
                startDetection();
            }
        });
        content.addView(startButton, buttonParams(dp(24)));

        Button stopButton = secondaryButton("\u505c\u6b62\u8bc6\u522b");
        stopButton.setOnClickListener(view -> stopDetection());
        content.addView(stopButton, buttonParams(dp(12)));

        Button accessibilityButton = secondaryButton("\u5f00\u542f\u65e0\u969c\u788d\u670d\u52a1");
        accessibilityButton.setOnClickListener(view -> {
            DebugLog.add(this, "Open accessibility settings");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        content.addView(accessibilityButton, buttonParams(dp(12)));

        Button testSwipeButton = secondaryButton("\u6d4b\u8bd5\u4e0a\u6ed1");
        testSwipeButton.setOnClickListener(view -> testSwipe());
        content.addView(testSwipeButton, buttonParams(dp(12)));

        TextView logTitle = new TextView(this);
        logTitle.setText("\u8fd0\u884c\u65e5\u5fd7");
        logTitle.setTextColor(Color.rgb(17, 24, 39));
        logTitle.setTextSize(18);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams logTitleParams = matchWrap();
        logTitleParams.setMargins(0, dp(28), 0, dp(10));
        content.addView(logTitle, logTitleParams);

        logView = panelText(12, true);
        logView.setMinHeight(dp(220));
        logView.setTextIsSelectable(true);
        content.addView(logView, matchWrap());

        Button copyButton = secondaryButton("\u590d\u5236\u65e5\u5fd7");
        copyButton.setOnClickListener(view -> copyLogs());
        content.addView(copyButton, buttonParams(dp(12)));

        Button clearButton = secondaryButton("\u6e05\u7a7a\u65e5\u5fd7");
        clearButton.setOnClickListener(view -> {
            DebugLog.clear(this);
            DebugLog.add(this, "Logs cleared");
            refreshLogs();
        });
        content.addView(clearButton, buttonParams(dp(12)));

        TextView note = new TextView(this);
        note.setText("\u8c03\u6574\u540e\u4f1a\u7acb\u5373\u4fdd\u5b58\u5e76\u5f71\u54cd\u8bc6\u522b\u3002\u5e27\u95f4\u9694\u8d8a\u5c0f\u8d8a\u7075\u654f\uff0c\u4f46\u4e5f\u66f4\u8017\u7535\u3002\u5982\u679c\u4e09\u8fde\u7728\u773c\u6ca1\u53cd\u5e94\uff0c\u8bf7\u590d\u5236\u6700\u540e\u51e0\u5341\u884c\u65e5\u5fd7\u53d1\u7ed9\u6211\u3002");
        note.setTextColor(Color.rgb(107, 114, 128));
        note.setTextSize(13);
        note.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.setMargins(0, dp(20), 0, 0);
        content.addView(note, noteParams);

        return scrollView;
    }

    private void addSettingsPanel(LinearLayout content) {
        TextView settingsTitle = new TextView(this);
        settingsTitle.setText("\u8bc6\u522b\u53c2\u6570");
        settingsTitle.setTextColor(Color.rgb(17, 24, 39));
        settingsTitle.setTextSize(18);
        settingsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(22), 0, dp(10));
        content.addView(settingsTitle, titleParams);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.setBackground(makePanelBackground());
        content.addView(panel, matchWrap());

        blinkGapValueView = settingLabel();
        panel.addView(blinkGapValueView, matchWrap());

        SeekBar blinkGapSeekBar = new SeekBar(this);
        blinkGapSeekBar.setMax(BlinkSettings.maxBlinkGapProgress());
        blinkGapSeekBar.setProgress(BlinkSettings.blinkGapMsToProgress(
                BlinkSettings.getBlinkGapMs(this)));
        blinkGapSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    BlinkSettings.setBlinkGapMs(
                            MainActivity.this,
                            BlinkSettings.progressToBlinkGapMs(progress)
                    );
                    refreshStatus();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                DebugLog.add(MainActivity.this, "Blink gap setting="
                        + BlinkSettings.getBlinkGapMs(MainActivity.this) + "ms"
                        + " series=" + BlinkSettings.getSeriesWindowMs(MainActivity.this) + "ms");
            }
        });
        panel.addView(blinkGapSeekBar, matchWrap());

        TextView blinkGapHint = settingHint(
                "\u8d8a\u5c0f\u8981\u6c42\u4e09\u8fde\u8d8a\u5feb\uff0c\u8bef\u89e6\u66f4\u5c11\uff1b\u8d8a\u5927\u8d8a\u5bb9\u6613\u89e6\u53d1\u3002"
        );
        LinearLayout.LayoutParams blinkHintParams = matchWrap();
        blinkHintParams.setMargins(0, 0, 0, dp(16));
        panel.addView(blinkGapHint, blinkHintParams);

        frameIntervalValueView = settingLabel();
        panel.addView(frameIntervalValueView, matchWrap());

        SeekBar frameIntervalSeekBar = new SeekBar(this);
        frameIntervalSeekBar.setMax(BlinkSettings.maxFrameIntervalProgress());
        frameIntervalSeekBar.setProgress(BlinkSettings.frameIntervalMsToProgress(
                BlinkSettings.getFrameIntervalMs(this)));
        frameIntervalSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    BlinkSettings.setFrameIntervalMs(
                            MainActivity.this,
                            BlinkSettings.progressToFrameIntervalMs(progress)
                    );
                    refreshStatus();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                DebugLog.add(MainActivity.this, "Frame interval setting="
                        + BlinkSettings.getFrameIntervalMs(MainActivity.this) + "ms");
            }
        });
        panel.addView(frameIntervalSeekBar, matchWrap());

        panel.addView(settingHint(
                "\u8d8a\u5c0f\u8d8a\u7075\u654f\u4f46\u66f4\u8017\u7535\uff1b\u8d8a\u5927\u8d8a\u7701\u7535\uff0c\u4f46\u5feb\u901f\u7728\u773c\u53ef\u80fd\u6f0f\u8bc6\u522b\u3002"
        ), matchWrap());

        refreshSettingLabels();
    }

    private void startDetection() {
        DebugLog.add(this, "Starting BlinkDetectionService");
        Intent intent = new Intent(this, BlinkDetectionService.class).setAction(BlinkDetectionService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        refreshStatus();
    }

    private void stopDetection() {
        DebugLog.add(this, "Stopping BlinkDetectionService");
        stopService(new Intent(this, BlinkDetectionService.class));
        refreshStatus();
    }

    private void testSwipe() {
        boolean ready = BlinkAccessibilityService.isReady();
        boolean dispatched = BlinkAccessibilityService.swipeUpFromCenter();
        DebugLog.add(this, "Manual swipe test ready=" + ready + " dispatched=" + dispatched);
        Toast.makeText(this,
                dispatched ? "\u5df2\u53d1\u9001\u4e0a\u6ed1\u624b\u52bf" : "\u65e0\u969c\u788d\u670d\u52a1\u672a\u8fde\u63a5",
                Toast.LENGTH_SHORT).show();
    }

    private void requestNeededPermissions() {
        List<String> permissions = new ArrayList<>();
        if (!hasCameraPermission()) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (!hasNotificationPermission()) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        DebugLog.add(this, "Request permissions: " + permissions);
        requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    private boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isAccessibilityEnabled() {
        ComponentName expected = new ComponentName(this, BlinkAccessibilityService.class);
        String expectedFull = expected.flattenToString();
        String expectedShort = expected.flattenToShortString();
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabledServices == null) {
            return false;
        }

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            String service = splitter.next();
            if (service.equalsIgnoreCase(expectedFull) || service.equalsIgnoreCase(expectedShort)) {
                return true;
            }
        }
        return false;
    }

    private void refreshStatus() {
        if (statusView == null) {
            return;
        }

        String camera = hasCameraPermission() ? "\u5df2\u5141\u8bb8" : "\u672a\u5141\u8bb8";
        String notification = hasNotificationPermission() ? "\u5df2\u5141\u8bb8" : "\u672a\u5141\u8bb8";
        String accessibility = isAccessibilityEnabled() ? "\u5df2\u5f00\u542f" : "\u672a\u5f00\u542f";
        String service = BlinkDetectionService.isRunning() ? "\u8bc6\u522b\u4e2d" : "\u672a\u8fd0\u884c";
        int blinkGapMs = BlinkSettings.getBlinkGapMs(this);
        int seriesWindowMs = BlinkSettings.getSeriesWindowMs(blinkGapMs);
        int frameIntervalMs = BlinkSettings.getFrameIntervalMs(this);

        statusView.setText(
                "\u76f8\u673a\u6743\u9650\uff1a" + camera
                        + "\n\u901a\u77e5\u6743\u9650\uff1a" + notification
                        + "\n\u65e0\u969c\u788d\u670d\u52a1\uff1a" + accessibility
                        + "\n\u540e\u53f0\u8bc6\u522b\uff1a" + service
                        + "\n\u7728\u773c\u95f4\u9694\u7a97\u53e3\uff1a" + blinkGapMs + "ms"
                        + "\n\u4e09\u8fde\u603b\u7a97\u53e3\uff1a" + seriesWindowMs + "ms"
                        + "\n\u5e27\u5904\u7406\u95f4\u9694\uff1a" + frameIntervalMs + "ms"
        );

        if (startButton != null) {
            startButton.setText(BlinkDetectionService.isRunning()
                    ? "\u91cd\u65b0\u542f\u52a8\u8bc6\u522b"
                    : "\u5f00\u59cb\u8bc6\u522b");
        }
        refreshSettingLabels();
    }

    private void refreshLogs() {
        if (logView != null) {
            logView.setText(DebugLog.read(this));
        }
    }

    private void refreshSettingLabels() {
        if (blinkGapValueView != null) {
            int blinkGapMs = BlinkSettings.getBlinkGapMs(this);
            blinkGapValueView.setText("\u7728\u773c\u95f4\u9694\u7a97\u53e3\uff1a"
                    + blinkGapMs + "ms\uff08\u4e09\u8fde\u603b\u7a97\u53e3 "
                    + BlinkSettings.getSeriesWindowMs(blinkGapMs) + "ms\uff09");
        }
        if (frameIntervalValueView != null) {
            frameIntervalValueView.setText("\u5e27\u5904\u7406\u95f4\u9694\uff1a"
                    + BlinkSettings.getFrameIntervalMs(this) + "ms");
        }
    }

    private void copyLogs() {
        String logs = DebugLog.read(this);
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboardManager.setPrimaryClip(ClipData.newPlainText("AutoDY logs", logs));
        Toast.makeText(this, "\u65e5\u5fd7\u5df2\u590d\u5236", Toast.LENGTH_SHORT).show();
    }

    private void registerLogReceiver() {
        if (logReceiverRegistered) {
            return;
        }

        IntentFilter filter = new IntentFilter(DebugLog.ACTION_LOGS_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
        logReceiverRegistered = true;
    }

    private void unregisterLogReceiver() {
        if (!logReceiverRegistered) {
            return;
        }

        unregisterReceiver(logReceiver);
        logReceiverRegistered = false;
    }

    private TextView panelText(int textSize, boolean monospace) {
        TextView textView = new TextView(this);
        textView.setTextSize(textSize);
        textView.setTextColor(Color.rgb(31, 41, 55));
        textView.setLineSpacing(0, 1.15f);
        textView.setPadding(dp(16), dp(14), dp(16), dp(14));
        textView.setBackground(makePanelBackground());
        if (monospace) {
            textView.setTypeface(Typeface.MONOSPACE);
        }
        return textView;
    }

    private TextView settingLabel() {
        TextView textView = new TextView(this);
        textView.setTextSize(15);
        textView.setTextColor(Color.rgb(17, 24, 39));
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        return textView;
    }

    private TextView settingHint(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(12);
        textView.setTextColor(Color.rgb(107, 114, 128));
        textView.setLineSpacing(0, 1.2f);
        return textView;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setBackground(makeButtonBackground(Color.rgb(37, 99, 235)));
        button.setMinHeight(dp(52));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(16);
        button.setTextColor(Color.rgb(31, 41, 55));
        button.setBackground(makeButtonBackground(Color.WHITE));
        button.setMinHeight(dp(52));
        return button;
    }

    private GradientDrawable makeButtonBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
        if (color == Color.WHITE) {
            drawable.setStroke(dp(1), Color.rgb(209, 213, 219));
        }
        return drawable;
    }

    private GradientDrawable makePanelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.WHITE);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), Color.rgb(229, 231, 235));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams buttonParams(int topMargin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, topMargin, 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
