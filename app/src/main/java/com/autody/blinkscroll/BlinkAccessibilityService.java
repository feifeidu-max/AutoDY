package com.autody.blinkscroll;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public class BlinkAccessibilityService extends AccessibilityService {
    private static volatile BlinkAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
        DebugLog.add(this, "Accessibility connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        DebugLog.add(this, "Accessibility interrupted");
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        DebugLog.add(this, "Accessibility destroyed");
        super.onDestroy();
    }

    public static boolean isReady() {
        return instance != null;
    }

    public static boolean swipeUpFromCenter() {
        BlinkAccessibilityService service = instance;
        if (service == null) {
            return false;
        }

        Size size = service.getScreenSize();
        float x = size.getWidth() / 2f;
        float startY = size.getHeight() * 0.72f;
        float endY = size.getHeight() * 0.28f;

        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 360))
                .build();
        DebugLog.add(service, "Dispatch swipe up x=" + Math.round(x)
                + " startY=" + Math.round(startY)
                + " endY=" + Math.round(endY));
        return service.dispatchGesture(gesture, null, null);
    }

    private Size getScreenSize() {
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            return new Size(bounds.width(), bounds.height());
        }

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return new Size(metrics.widthPixels, metrics.heightPixels);
    }
}
