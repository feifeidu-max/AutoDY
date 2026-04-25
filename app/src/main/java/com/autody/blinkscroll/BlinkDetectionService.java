package com.autody.blinkscroll;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class BlinkDetectionService extends Service {
    public static final String ACTION_START = "com.autody.blinkscroll.START";
    public static final String ACTION_STOP = "com.autody.blinkscroll.STOP";

    private static final String TAG = "BlinkDetectionService";
    private static final String CHANNEL_ID = "blink_detection";
    private static final int NOTIFICATION_ID = 1001;
    private static final long SWIPE_COOLDOWN_MS = 1800;

    private static final SparseIntArray DEVICE_ORIENTATIONS = new SparseIntArray();

    static {
        DEVICE_ORIENTATIONS.append(Surface.ROTATION_0, 0);
        DEVICE_ORIENTATIONS.append(Surface.ROTATION_90, 90);
        DEVICE_ORIENTATIONS.append(Surface.ROTATION_180, 180);
        DEVICE_ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private static volatile boolean running;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private ImageReader imageReader;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private FaceDetector faceDetector;
    private int sensorOrientation;
    private volatile boolean processingFrame;
    private long lastFrameAt;
    private long lastSwipeAt;
    private long lastNotificationAt;
    private long lastFaceLogAt;
    private long lastNoFaceLogAt;
    private boolean foregroundStarted;
    private final BlinkTracker blinkTracker = new BlinkTracker();

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DebugLog.add(this, "Service onCreate");
        createNotificationChannel();
        try {
            startForegroundNow("Preparing camera");
            foregroundStarted = true;
            DebugLog.add(this, "Foreground notification started");
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to start camera foreground service", exception);
            DebugLog.add(this, "Foreground start failed: " + exception.getClass().getSimpleName()
                    + " " + exception.getMessage());
            stopSelf();
        }
        faceDetector = FaceDetection.getClient(
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                        .setMinFaceSize(0.18f)
                        .build()
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        DebugLog.add(this, "Service onStartCommand action=" + action);
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!foregroundStarted) {
            stopSelf();
            return START_NOT_STICKY;
        }

        DebugLog.add(this, "Blink tuning: frame="
                + BlinkSettings.getFrameIntervalMs(this)
                + "ms gap<="
                + BlinkSettings.getBlinkGapMs(this)
                + "ms series<="
                + BlinkSettings.getSeriesWindowMs(this)
                + "ms open>=0.65 closed<=0.35");
        startCamera();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        DebugLog.add(this, "Service onDestroy");
        running = false;
        closeCamera();
        if (faceDetector != null) {
            faceDetector.close();
            faceDetector = null;
        }
        super.onDestroy();
    }

    private void startForegroundNow(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @SuppressLint("MissingPermission")
    private void startCamera() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Camera permission missing");
            DebugLog.add(this, "Camera permission missing");
            stopSelf();
            return;
        }

        try {
            closeCamera();
            ensureCameraThread();

            CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraConfig config = findFrontCamera(cameraManager);
            if (config == null) {
                updateNotification("No front camera found");
                DebugLog.add(this, "No front camera found");
                stopSelf();
                return;
            }

            sensorOrientation = config.sensorOrientation;
            DebugLog.add(this, "Front camera selected id=" + config.cameraId
                    + " size=" + config.size.getWidth() + "x" + config.size.getHeight()
                    + " sensorOrientation=" + sensorOrientation);
            imageReader = ImageReader.newInstance(
                    config.size.getWidth(),
                    config.size.getHeight(),
                    ImageFormat.YUV_420_888,
                    2
            );
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);

            cameraManager.openCamera(config.cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    DebugLog.add(BlinkDetectionService.this, "Camera opened");
                    cameraDevice = camera;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    if (cameraDevice == camera) {
                        cameraDevice = null;
                    }
                    updateNotification("Camera disconnected");
                    DebugLog.add(BlinkDetectionService.this, "Camera disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    if (cameraDevice == camera) {
                        cameraDevice = null;
                    }
                    updateNotification("Camera open failed");
                    DebugLog.add(BlinkDetectionService.this, "Camera error=" + error);
                }
            }, cameraHandler);
        } catch (SecurityException securityException) {
            Log.w(TAG, "Camera permission denied while starting service", securityException);
            updateNotification("Camera blocked by system");
            DebugLog.add(this, "Camera security exception: " + securityException.getMessage());
            stopSelf();
        } catch (CameraAccessException | IllegalStateException exception) {
            Log.w(TAG, "Unable to start camera", exception);
            updateNotification("Unable to start camera");
            DebugLog.add(this, "Camera start failed: " + exception.getClass().getSimpleName()
                    + " " + exception.getMessage());
            stopSelf();
        }
    }

    private void createCaptureSession() {
        if (cameraDevice == null || imageReader == null) {
            return;
        }

        try {
            cameraDevice.createCaptureSession(
                    Collections.singletonList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            DebugLog.add(BlinkDetectionService.this, "Capture session configured");
                            captureSession = session;
                            startRepeatingCapture();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            updateNotification("Camera config failed");
                            DebugLog.add(BlinkDetectionService.this, "Capture session configure failed");
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException exception) {
            Log.w(TAG, "Unable to create capture session", exception);
            updateNotification("Camera config failed");
            DebugLog.add(this, "Capture session failed: " + exception.getMessage());
        }
    }

    private void startRepeatingCapture() {
        if (cameraDevice == null || captureSession == null || imageReader == null) {
            return;
        }

        try {
            CaptureRequest.Builder requestBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(imageReader.getSurface());
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            captureSession.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
            running = true;
            updateNotification("Detecting triple blink");
            DebugLog.add(this, "Camera repeating request started");
        } catch (CameraAccessException exception) {
            Log.w(TAG, "Unable to start camera repeating request", exception);
            updateNotification("Camera capture failed");
            DebugLog.add(this, "Repeating request failed: " + exception.getMessage());
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        int frameIntervalMs = BlinkSettings.getFrameIntervalMs(this);
        if (processingFrame || now - lastFrameAt < frameIntervalMs) {
            image.close();
            return;
        }

        processingFrame = true;
        lastFrameAt = now;

        try {
            InputImage inputImage = InputImage.fromMediaImage(image, getRotationCompensation());
            faceDetector.process(inputImage)
                    .addOnSuccessListener(this::handleFaces)
                    .addOnFailureListener(error -> {
                        Log.w(TAG, "Face detection failed", error);
                        DebugLog.add(this, "Face detection failed: " + error.getMessage());
                    })
                    .addOnCompleteListener(task -> {
                        image.close();
                        processingFrame = false;
                    });
        } catch (RuntimeException exception) {
            image.close();
            processingFrame = false;
            Log.w(TAG, "Unable to process camera frame", exception);
            DebugLog.add(this, "Frame process exception: " + exception.getMessage());
        }
    }

    private void handleFaces(List<Face> faces) {
        long now = SystemClock.elapsedRealtime();
        Face face = chooseFace(faces);
        if (face == null) {
            if (now - lastNoFaceLogAt > 2000) {
                lastNoFaceLogAt = now;
                DebugLog.add(this, "No face detected. faces=" + faces.size());
            }
            blinkTracker.resetIfIdle(now, this);
            return;
        }

        Float left = face.getLeftEyeOpenProbability();
        Float right = face.getRightEyeOpenProbability();
        if (left == null || right == null || left < 0f || right < 0f) {
            if (now - lastFaceLogAt > 1500) {
                lastFaceLogAt = now;
                DebugLog.add(this, "Face found but eye probabilities unavailable");
            }
            return;
        }

        float eyeOpenScore = (left + right) / 2f;
        boolean wasClosed = blinkTracker.isClosed();
        int blinkCountBefore = blinkTracker.getBlinkCount();
        boolean triggered = blinkTracker.acceptEyeScore(eyeOpenScore, now, this);
        String trackerEvent = blinkTracker.getLastEvent();
        boolean isClosed = blinkTracker.isClosed();
        int blinkCountAfter = blinkTracker.getBlinkCount();

        if (now - lastFaceLogAt > 1000) {
            lastFaceLogAt = now;
            DebugLog.add(this, String.format(Locale.US,
                    "faces=%d left=%.2f right=%.2f score=%.2f closed=%s blinks=%d gap=%dms frame=%dms accessibility=%s",
                    faces.size(), left, right, eyeOpenScore, isClosed, blinkCountAfter,
                    BlinkSettings.getBlinkGapMs(this), BlinkSettings.getFrameIntervalMs(this),
                    BlinkAccessibilityService.isReady()));
        }

        if (!wasClosed && isClosed) {
            DebugLog.add(this, String.format(Locale.US, "Eyes closed score=%.2f", eyeOpenScore));
        }
        if (wasClosed && !isClosed && blinkCountAfter != blinkCountBefore) {
            DebugLog.add(this, "Blink accepted count=" + blinkCountAfter);
        }
        if (trackerEvent != null && trackerEvent.startsWith("Blink rejected")) {
            DebugLog.add(this, trackerEvent);
        }
        if (trackerEvent != null && trackerEvent.startsWith("Blink series reset")) {
            DebugLog.add(this, trackerEvent);
        }

        if (triggered) {
            DebugLog.add(this, "Triple blink detected");
            if (now - lastSwipeAt >= SWIPE_COOLDOWN_MS) {
                lastSwipeAt = now;
                boolean dispatched = BlinkAccessibilityService.swipeUpFromCenter();
                DebugLog.add(this, "Swipe dispatch result=" + dispatched);
                updateNotification(dispatched ? "Swipe triggered" : "Enable accessibility first");
            } else {
                DebugLog.add(this, "Triple blink ignored by cooldown");
            }
        } else if (now - lastNotificationAt > 5000) {
            updateNotification(BlinkAccessibilityService.isReady()
                    ? "Detecting triple blink"
                    : "Waiting for accessibility");
        }
    }

    private Face chooseFace(List<Face> faces) {
        Face best = null;
        int bestArea = 0;
        for (Face face : faces) {
            Rect bounds = face.getBoundingBox();
            int area = Math.max(0, bounds.width()) * Math.max(0, bounds.height());
            if (area > bestArea) {
                best = face;
                bestArea = area;
            }
        }
        return best;
    }

    private int getRotationCompensation() {
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        int rotation = windowManager.getDefaultDisplay().getRotation();
        int deviceRotation = DEVICE_ORIENTATIONS.get(rotation);
        return (sensorOrientation + deviceRotation) % 360;
    }

    private CameraConfig findFrontCamera(CameraManager cameraManager) throws CameraAccessException {
        for (String cameraId : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) {
                continue;
            }

            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (map == null || orientation == null) {
                continue;
            }

            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (sizes == null || sizes.length == 0) {
                continue;
            }

            return new CameraConfig(cameraId, chooseAnalysisSize(sizes), orientation);
        }
        return null;
    }

    private Size chooseAnalysisSize(Size[] sizes) {
        return Arrays.stream(sizes)
                .filter(size -> size.getWidth() <= 640 && size.getHeight() <= 480)
                .max(Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()))
                .orElseGet(() -> Arrays.stream(sizes)
                        .min(Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()))
                        .orElse(sizes[0]));
    }

    private void ensureCameraThread() {
        if (cameraThread != null) {
            return;
        }

        cameraThread = new HandlerThread("BlinkCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        DebugLog.add(this, "Camera thread started");
    }

    private void closeCamera() {
        processingFrame = false;

        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
            } catch (CameraAccessException | IllegalStateException ignored) {
            }
            captureSession.close();
            captureSession = null;
            DebugLog.add(this, "Capture session closed");
        }

        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
            DebugLog.add(this, "Camera device closed");
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
            DebugLog.add(this, "ImageReader closed");
        }

        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join(1000);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
            DebugLog.add(this, "Camera thread stopped");
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("AutoDY")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_eye)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        lastNotificationAt = SystemClock.elapsedRealtime();
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to update notification", exception);
            DebugLog.add(this, "Notification update failed: " + exception.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Blink detection",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Front camera blink detection service");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private static final class CameraConfig {
        final String cameraId;
        final Size size;
        final int sensorOrientation;

        CameraConfig(String cameraId, Size size, int sensorOrientation) {
            this.cameraId = cameraId;
            this.size = size;
            this.sensorOrientation = sensorOrientation;
        }
    }

    private static final class BlinkTracker {
        private static final float CLOSED_THRESHOLD = 0.35f;
        private static final float OPEN_THRESHOLD = 0.65f;
        private static final long MIN_CLOSED_MS = 45;
        private static final long MAX_CLOSED_MS = 700;
        private static final long MIN_BLINK_GAP_MS = 120;

        private boolean closed;
        private long closedAt;
        private long firstBlinkAt;
        private long lastBlinkAt;
        private int blinkCount;
        private String lastEvent;

        boolean acceptEyeScore(float eyeOpenScore, long now, Context context) {
            lastEvent = null;
            resetIfIdle(now, context);

            if (!closed && eyeOpenScore <= CLOSED_THRESHOLD) {
                closed = true;
                closedAt = now;
                return false;
            }

            if (closed && eyeOpenScore >= OPEN_THRESHOLD) {
                long closedDuration = now - closedAt;
                closed = false;
                if (closedDuration < MIN_CLOSED_MS) {
                    reset();
                    lastEvent = "Blink rejected: closed too short " + closedDuration + "ms";
                    return false;
                }
                if (closedDuration > MAX_CLOSED_MS) {
                    reset();
                    lastEvent = "Blink rejected: closed too long " + closedDuration + "ms";
                    return false;
                }
                return registerBlink(now, context);
            }

            return false;
        }

        void resetIfIdle(long now, Context context) {
            int maxBlinkGapMs = BlinkSettings.getBlinkGapMs(context);
            int maxSeriesMs = BlinkSettings.getSeriesWindowMs(maxBlinkGapMs);
            if (blinkCount > 0 && now - lastBlinkAt > maxBlinkGapMs) {
                lastEvent = "Blink series reset: gap " + (now - lastBlinkAt)
                        + "ms > " + maxBlinkGapMs + "ms";
                reset();
            }
            if (firstBlinkAt > 0 && now - firstBlinkAt > maxSeriesMs) {
                lastEvent = "Blink series reset: series " + (now - firstBlinkAt)
                        + "ms > " + maxSeriesMs + "ms";
                reset();
            }
        }

        boolean isClosed() {
            return closed;
        }

        int getBlinkCount() {
            return blinkCount;
        }

        String getLastEvent() {
            return lastEvent;
        }

        private boolean registerBlink(long now, Context context) {
            int maxBlinkGapMs = BlinkSettings.getBlinkGapMs(context);
            int maxSeriesMs = BlinkSettings.getSeriesWindowMs(maxBlinkGapMs);
            long gap = blinkCount == 0 ? 0 : now - lastBlinkAt;
            if (blinkCount > 0 && gap < MIN_BLINK_GAP_MS) {
                lastEvent = "Blink rejected: gap too short " + gap + "ms";
                return false;
            }

            if (blinkCount == 0 || gap <= maxBlinkGapMs) {
                if (blinkCount == 0) {
                    firstBlinkAt = now;
                }
                blinkCount++;
                lastBlinkAt = now;
            } else {
                blinkCount = 1;
                firstBlinkAt = now;
                lastBlinkAt = now;
            }

            if (blinkCount >= 3 && now - firstBlinkAt <= maxSeriesMs) {
                reset();
                return true;
            }
            return false;
        }

        private void reset() {
            closed = false;
            closedAt = 0;
            firstBlinkAt = 0;
            lastBlinkAt = 0;
            blinkCount = 0;
        }
    }
}
