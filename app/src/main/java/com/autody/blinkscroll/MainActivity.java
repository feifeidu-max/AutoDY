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
    private TextView modeValueView;
    private TextView mouthIntervalValueView;
    private Button mouthModeButton;
    private Button soundModeButton;
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
                + " microphone=" + hasMicrophonePermission()
                + " notification=" + hasNotificationPermission());
        if (requestCode == REQUEST_PERMISSIONS && startAfterPermission) {
            startAfterPermission = false;
            if (hasNeededModePermission()) {
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
        subtitle.setText("\u5f20\u5634\u6216\u54cd\u6307\u89e6\u53d1\u4e0a\u6ed1");
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
            if (!hasNeededModePermission() || !hasNotificationPermission()) {
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
        note.setText("\u5207\u6362\u6a21\u5f0f\u540e\u9700\u8981\u91cd\u65b0\u70b9\u51fb\u5f00\u59cb\u8bc6\u522b\u3002\u5982\u679c\u5f20\u5634\u6216\u54cd\u6307\u6ca1\u53cd\u5e94\uff0c\u8bf7\u590d\u5236\u6700\u540e\u51e0\u5341\u884c\u65e5\u5fd7\u53d1\u7ed9\u6211\u3002");
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

        modeValueView = settingLabel();
        panel.addView(modeValueView, matchWrap());

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams modeRowParams = matchWrap();
        modeRowParams.setMargins(0, dp(8), 0, dp(16));
        panel.addView(modeRow, modeRowParams);

        mouthModeButton = secondaryButton("\u5f20\u5634\u8bc6\u522b");
        soundModeButton = secondaryButton("\u58f0\u97f3\u8bc6\u522b");
        mouthModeButton.setOnClickListener(view -> setDetectionMode(BlinkSettings.MODE_MOUTH));
        soundModeButton.setOnClickListener(view -> setDetectionMode(BlinkSettings.MODE_SOUND));
        modeRow.addView(mouthModeButton, rowButtonParams(0, dp(6)));
        modeRow.addView(soundModeButton, rowButtonParams(dp(6), 0));

        mouthIntervalValueView = settingLabel();
        panel.addView(mouthIntervalValueView, matchWrap());

        SeekBar mouthIntervalSeekBar = new SeekBar(this);
        mouthIntervalSeekBar.setMax(BlinkSettings.maxMouthIntervalProgress());
        mouthIntervalSeekBar.setProgress(BlinkSettings.mouthIntervalMsToProgress(
                BlinkSettings.getMouthIntervalMs(this)));
        mouthIntervalSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    BlinkSettings.setMouthIntervalMs(
                            MainActivity.this,
                            BlinkSettings.progressToMouthIntervalMs(progress)
                    );
                    refreshStatus();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                DebugLog.add(MainActivity.this, "Mouth interval setting="
                        + BlinkSettings.getMouthIntervalMs(MainActivity.this) + "ms");
            }
        });
        panel.addView(mouthIntervalSeekBar, matchWrap());

        panel.addView(settingHint(
                "\u5f20\u5634\u6a21\u5f0f\u4e0b\u751f\u6548\uff0c\u53ef\u5728 1000ms \u5230 5000ms \u4e4b\u95f4\u8c03\u8282\u3002\u58f0\u97f3\u6a21\u5f0f\u4f1a\u76d1\u542c\u9ea6\u514b\u98ce\u5e76\u8bc6\u522b\u54cd\u6307\u5cf0\u503c\u3002"
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

    private void setDetectionMode(int mode) {
        int previous = BlinkSettings.getDetectionMode(this);
        BlinkSettings.setDetectionMode(this, mode);
        int current = BlinkSettings.getDetectionMode(this);
        if (previous != current) {
            DebugLog.add(this, "Detection mode setting=" + BlinkSettings.getDetectionModeLabel(this));
            if (BlinkDetectionService.isRunning()) {
                stopDetection();
            }
        }
        refreshStatus();
    }

    private void requestNeededPermissions() {
        List<String> permissions = new ArrayList<>();
        if (BlinkSettings.getDetectionMode(this) == BlinkSettings.MODE_MOUTH && !hasCameraPermission()) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (BlinkSettings.getDetectionMode(this) == BlinkSettings.MODE_SOUND && !hasMicrophonePermission()) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
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

    private boolean hasMicrophonePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNeededModePermission() {
        return BlinkSettings.getDetectionMode(this) == BlinkSettings.MODE_SOUND
                ? hasMicrophonePermission()
                : hasCameraPermission();
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
        String microphone = hasMicrophonePermission() ? "\u5df2\u5141\u8bb8" : "\u672a\u5141\u8bb8";
        String notification = hasNotificationPermission() ? "\u5df2\u5141\u8bb8" : "\u672a\u5141\u8bb8";
        String accessibility = isAccessibilityEnabled() ? "\u5df2\u5f00\u542f" : "\u672a\u5f00\u542f";
        String service = BlinkDetectionService.isRunning() ? "\u8bc6\u522b\u4e2d" : "\u672a\u8fd0\u884c";
        String mode = BlinkSettings.getDetectionModeLabel(this);
        int mouthIntervalMs = BlinkSettings.getMouthIntervalMs(this);

        statusView.setText(
                "\u76f8\u673a\u6743\u9650\uff1a" + camera
                        + "\n\u9ea6\u514b\u98ce\u6743\u9650\uff1a" + microphone
                        + "\n\u901a\u77e5\u6743\u9650\uff1a" + notification
                        + "\n\u65e0\u969c\u788d\u670d\u52a1\uff1a" + accessibility
                        + "\n\u540e\u53f0\u8bc6\u522b\uff1a" + service
                        + "\n\u9501\u5c4f\u6216\u7184\u5c4f\uff1a\u81ea\u52a8\u5f85\u673a\u505c\u6b62\u68c0\u6d4b"
                        + "\n\u5f53\u524d\u6a21\u5f0f\uff1a" + mode
                        + "\n\u5634\u5df4\u68c0\u6d4b\u95f4\u9694\uff1a" + mouthIntervalMs + "ms"
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
        int mode = BlinkSettings.getDetectionMode(this);
        if (modeValueView != null) {
            modeValueView.setText("\u8bc6\u522b\u6a21\u5f0f\uff1a" + BlinkSettings.getDetectionModeLabel(this));
        }
        if (mouthIntervalValueView != null) {
            mouthIntervalValueView.setText("\u5634\u5df4\u68c0\u6d4b\u95f4\u9694\uff1a"
                    + BlinkSettings.getMouthIntervalMs(this) + "ms");
        }
        if (mouthModeButton != null) {
            mouthModeButton.setText(mode == BlinkSettings.MODE_MOUTH
                    ? "\u5f20\u5634\u8bc6\u522b \u2713"
                    : "\u5f20\u5634\u8bc6\u522b");
        }
        if (soundModeButton != null) {
            soundModeButton.setText(mode == BlinkSettings.MODE_SOUND
                    ? "\u58f0\u97f3\u8bc6\u522b \u2713"
                    : "\u58f0\u97f3\u8bc6\u522b");
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

    private LinearLayout.LayoutParams rowButtonParams(int leftMargin, int rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMargins(leftMargin, 0, rightMargin, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
