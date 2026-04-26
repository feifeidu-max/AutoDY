package com.autody.blinkscroll;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.Image;
import android.media.ImageReader;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
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
    private static final float FACE_CHANGE_TRIGGER_SCORE = 0.18f;
    private static final float FACE_CHANGE_RESET_SCORE = 0.08f;
    private static final float FACE_HEAD_SHAKE_TRIGGER_SCORE = 0.16f;
    private static final long FRAME_PROCESSING_TIMEOUT_MS = 5000;
    private static final long CAMERA_STALE_TIMEOUT_MS = 12000;
    private static final long CAMERA_RESTART_COOLDOWN_MS = 10000;
    private static final int SOUND_SAMPLE_RATE = 22050;
    private static final int SOUND_ANALYSIS_WINDOW_MS = 10;
    private static final float SNAP_DELTA_RATIO = 0.58f;
    private static final float SNAP_MIN_RATIO = 3.6f;
    private static final float SNAP_MIN_CREST = 2.6f;
    private static final float SNAP_MIN_EDGE_RATIO = 0.10f;
    private static final float SNAP_MIN_STRONG_FRACTION = 0.015f;
    private static final float SNAP_MAX_STRONG_FRACTION = 0.22f;
    private static final long SNAP_REFRACTORY_MS = 900;
    private static final String MODE_IDLE = "IDLE";
    private static final String MODE_MOUTH = "MOUTH";
    private static final String MODE_SOUND = "SOUND";

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
    private long lastCameraImageAt;
    private long lastCameraRestartAt;
    private long lastFaceChangeTriggerAt;
    private long lastNotificationAt;
    private long lastFaceLogAt;
    private long lastNoFaceLogAt;
    private long lastSoundLogAt;
    private long lastSnapAcceptedAt;
    private long lastSoundAnalyzeAt;
    private boolean mouthOpen;
    private boolean faceChangeActive;
    private boolean foregroundStarted;
    private HandlerThread audioThread;
    private Handler audioHandler;
    private AudioRecord audioRecord;
    private String audioSourceLabel = "VOICE_RECOGNITION";
    private AcousticEchoCanceler acousticEchoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl automaticGainControl;
    private boolean communicationAudioMode;
    private volatile boolean audioRunning;
    private double soundNoiseFloor = 700.0;
    private Float lastFaceCenterX;
    private Float lastFaceCenterY;
    private Float lastFaceSizeRatio;
    private Float lastFaceMouthRatio;
    private Float lastFaceYaw;
    private Float lastFacePitch;
    private Float lastFaceRoll;
    private boolean receiverRegistered;
    private volatile boolean stoppingForScreenOff;
    private String activeMode = MODE_IDLE;
    private final Handler powerHandler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                stopDetectionForScreenOff("screen off broadcast");
            }
        }
    };
    private final Runnable powerWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (!isScreenInteractive()) {
                stopDetectionForScreenOff("power watchdog");
                return;
            }
            checkCameraHealth();
            powerHandler.postDelayed(this, 1000);
        }
    };

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DebugLog.add(this, "Service onCreate");
        createNotificationChannel();
        try {
            startForegroundNow(BlinkSettings.getDetectionMode(this) == BlinkSettings.MODE_SOUND
                    ? "Preparing microphone"
                    : "Preparing camera");
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
                        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                        .setMinFaceSize(0.18f)
                        .build()
        );
        registerScreenStateReceiver();
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

        running = true;
        stoppingForScreenOff = false;
        DebugLog.add(this, "Service armed manual session");
        startPowerWatchdog();
        startConfiguredMode("start");
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
        stopPowerWatchdog();
        unregisterScreenStateReceiver();
        stopAudioDetection();
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
            int foregroundType = BlinkSettings.getDetectionMode(this) == BlinkSettings.MODE_SOUND
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            startForeground(NOTIFICATION_ID, notification, foregroundType);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void registerScreenStateReceiver() {
        if (receiverRegistered) {
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenStateReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterScreenStateReceiver() {
        if (!receiverRegistered) {
            return;
        }

        unregisterReceiver(screenStateReceiver);
        receiverRegistered = false;
    }

    private void startConfiguredMode(String reason) {
        int mode = BlinkSettings.getDetectionMode(this);
        if (mode == BlinkSettings.MODE_SOUND) {
            startSoundMode(reason);
        } else {
            startMouthMode(reason);
        }
    }

    private void startPowerWatchdog() {
        powerHandler.removeCallbacks(powerWatchdog);
        powerHandler.post(powerWatchdog);
    }

    private void stopPowerWatchdog() {
        powerHandler.removeCallbacks(powerWatchdog);
    }

    private boolean isScreenInteractive() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        return powerManager == null || powerManager.isInteractive();
    }

    private void stopDetectionForScreenOff(String reason) {
        if (stoppingForScreenOff) {
            return;
        }

        stoppingForScreenOff = true;
        DebugLog.add(this, "Screen off: stop detection until manual start reason=" + reason);
        enterStandby(reason);
        running = false;
        stopSelf();
    }

    private void enterStandby(String reason) {
        boolean hadActiveMode = !MODE_IDLE.equals(activeMode);
        stopAudioDetection();
        closeCamera();
        mouthOpen = false;
        faceChangeActive = false;
        lastFaceChangeTriggerAt = 0L;
        lastFaceCenterX = null;
        lastFaceCenterY = null;
        lastFaceSizeRatio = null;
        lastFaceMouthRatio = null;
        activeMode = MODE_IDLE;
        updateNotification("Stopped: screen off");
        DebugLog.add(this, (hadActiveMode ? "Detection paused" : "Standby")
                + " reason=" + reason);
    }

    private void startMouthMode(String reason) {
        if (MODE_MOUTH.equals(activeMode)) {
            return;
        }

        stopAudioDetection();
        activeMode = MODE_MOUTH;
        DebugLog.add(this, "Face change mode active reason=" + reason
                + " interval=" + BlinkSettings.getMouthIntervalMs(this)
                + "ms faceChange>=" + FACE_CHANGE_TRIGGER_SCORE);
        startCamera();
    }

    private void startSoundMode(String reason) {
        if (MODE_SOUND.equals(activeMode)) {
            return;
        }

        closeCamera();
        activeMode = MODE_SOUND;
        DebugLog.add(this, "Sound mode active reason=" + reason);
        startAudioDetection();
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
            resetFaceChangeState();
            lastFrameAt = 0L;
            lastCameraImageAt = SystemClock.elapsedRealtime();

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
            updateNotification("Detecting face change");
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
        lastCameraImageAt = now;
        if (processingFrame && now - lastFrameAt > FRAME_PROCESSING_TIMEOUT_MS) {
            DebugLog.add(this, "Frame processing timeout, reset processor");
            processingFrame = false;
        }

        int mouthIntervalMs = BlinkSettings.getMouthIntervalMs(this);
        if (processingFrame || now - lastFrameAt < mouthIntervalMs) {
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
            mouthOpen = false;
            resetFaceChangeState();
            return;
        }

        Float mouthOpenRatio = getMouthOpenRatio(face);
        Rect bounds = face.getBoundingBox();
        float faceWidth = Math.max(1f, bounds.width());
        float faceHeight = Math.max(1f, bounds.height());
        float faceSize = Math.max(faceWidth, faceHeight);
        float centerX = bounds.exactCenterX();
        float centerY = bounds.exactCenterY();
        float frameSize = Math.max(1f, imageReader != null
                ? Math.max(imageReader.getWidth(), imageReader.getHeight())
                : faceSize);
        float sizeRatio = faceSize / frameSize;
        float moveScore = 0f;
        float sizeScore = 0f;
        float mouthScore = 0f;
        float headScore = 0f;
        float yawScore = 0f;
        float pitchScore = 0f;
        float rollScore = 0f;
        float yaw = face.getHeadEulerAngleY();
        float pitch = face.getHeadEulerAngleX();
        float roll = face.getHeadEulerAngleZ();

        if (lastFaceCenterX != null && lastFaceCenterY != null && lastFaceSizeRatio != null) {
            float dx = centerX - lastFaceCenterX;
            float dy = centerY - lastFaceCenterY;
            moveScore = (float) (Math.hypot(dx, dy) / faceSize);
            sizeScore = Math.abs(sizeRatio - lastFaceSizeRatio);
            if (mouthOpenRatio != null && lastFaceMouthRatio != null) {
                mouthScore = Math.abs(mouthOpenRatio - lastFaceMouthRatio);
            }
            if (lastFaceYaw != null && lastFacePitch != null && lastFaceRoll != null) {
                yawScore = Math.abs(yaw - lastFaceYaw) / 22f;
                pitchScore = Math.abs(pitch - lastFacePitch) / 26f;
                rollScore = Math.abs(roll - lastFaceRoll) / 28f;
                headScore = Math.max(yawScore, Math.max(pitchScore * 0.85f, rollScore * 0.75f));
            }
        }

        float expressionScore = moveScore * 0.65f + sizeScore * 1.6f + mouthScore * 1.8f;
        float faceChangeScore = Math.max(expressionScore, headScore);
        boolean wasFaceChangeActive = faceChangeActive;
        faceChangeActive = expressionScore >= FACE_CHANGE_TRIGGER_SCORE
                || headScore >= FACE_HEAD_SHAKE_TRIGGER_SCORE;
        if (faceChangeScore <= FACE_CHANGE_RESET_SCORE) {
            faceChangeActive = false;
        }
        mouthOpen = faceChangeActive;

        if (now - lastFaceLogAt > 2000) {
            lastFaceLogAt = now;
            DebugLog.add(this, String.format(Locale.US,
                    "faces=%d change=%.3f move=%.3f size=%.3f mouth=%.3f head=%.3f yaw=%.3f pitch=%.3f roll=%.3f active=%s interval=%dms accessibility=%s",
                    faces.size(), faceChangeScore, moveScore, sizeScore, mouthScore,
                    headScore, yawScore, pitchScore, rollScore,
                    faceChangeActive, BlinkSettings.getMouthIntervalMs(this),
                    BlinkAccessibilityService.isReady()));
        }

        if (faceChangeActive && now - lastFaceChangeTriggerAt >= SWIPE_COOLDOWN_MS) {
            lastFaceChangeTriggerAt = now;
            DebugLog.add(this, String.format(Locale.US,
                    "Face change detected change=%.3f move=%.3f size=%.3f mouth=%.3f head=%.3f yaw=%.3f pitch=%.3f roll=%.3f",
                    faceChangeScore, moveScore, sizeScore, mouthScore,
                    headScore, yawScore, pitchScore, rollScore));
            triggerSwipe("Face change");
            resetFaceChangeBaseline(centerX, centerY, sizeRatio, mouthOpenRatio, yaw, pitch, roll);
            faceChangeActive = false;
            return;
        } else if (wasFaceChangeActive && !faceChangeActive) {
            DebugLog.add(this, String.format(Locale.US,
                    "Face change reset change=%.3f", faceChangeScore));
        }

        if (now - lastNotificationAt > 5000) {
            updateNotification(BlinkAccessibilityService.isReady()
                    ? "Detecting face change"
                    : "Waiting for accessibility");
        }

        resetFaceChangeBaseline(centerX, centerY, sizeRatio, mouthOpenRatio, yaw, pitch, roll);
    }

    private void resetFaceChangeState() {
        faceChangeActive = false;
        lastFaceCenterX = null;
        lastFaceCenterY = null;
        lastFaceSizeRatio = null;
        lastFaceMouthRatio = null;
        lastFaceYaw = null;
        lastFacePitch = null;
        lastFaceRoll = null;
    }

    private void resetFaceChangeBaseline(float centerX, float centerY, float sizeRatio,
            Float mouthOpenRatio, float yaw, float pitch, float roll) {
        lastFaceCenterX = centerX;
        lastFaceCenterY = centerY;
        lastFaceSizeRatio = sizeRatio;
        lastFaceMouthRatio = mouthOpenRatio;
        lastFaceYaw = yaw;
        lastFacePitch = pitch;
        lastFaceRoll = roll;
    }

    private void checkCameraHealth() {
        if (!MODE_MOUTH.equals(activeMode) || !running || stoppingForScreenOff) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (processingFrame && now - lastFrameAt > FRAME_PROCESSING_TIMEOUT_MS) {
            processingFrame = false;
            DebugLog.add(this, "Camera watchdog reset stuck frame processor");
        }

        boolean cameraMissing = cameraDevice == null || captureSession == null || imageReader == null;
        boolean cameraStale = lastCameraImageAt > 0 && now - lastCameraImageAt > CAMERA_STALE_TIMEOUT_MS;
        if ((cameraMissing || cameraStale) && now - lastCameraRestartAt > CAMERA_RESTART_COOLDOWN_MS) {
            lastCameraRestartAt = now;
            DebugLog.add(this, "Camera watchdog restart missing=" + cameraMissing
                    + " stale=" + cameraStale);
            closeCamera();
            startCamera();
        }
    }

    private Float getMouthOpenRatio(Face face) {
        FaceContour upperLip = face.getContour(FaceContour.UPPER_LIP_BOTTOM);
        FaceContour lowerLip = face.getContour(FaceContour.LOWER_LIP_TOP);
        if (upperLip == null || lowerLip == null
                || upperLip.getPoints().isEmpty() || lowerLip.getPoints().isEmpty()) {
            return null;
        }

        float upperY = averageY(upperLip.getPoints());
        float lowerY = averageY(lowerLip.getPoints());
        int faceHeight = Math.max(1, face.getBoundingBox().height());
        float gap = Math.max(0f, lowerY - upperY);
        return gap / faceHeight;
    }

    private float averageY(List<PointF> points) {
        float sum = 0f;
        for (PointF point : points) {
            sum += point.y;
        }
        return sum / points.size();
    }

    private void triggerSwipe(String reason) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSwipeAt < SWIPE_COOLDOWN_MS) {
            DebugLog.add(this, reason + " ignored by cooldown");
            return;
        }

        lastSwipeAt = now;
        boolean dispatched = BlinkAccessibilityService.swipeUpFromCenter();
        DebugLog.add(this, "Swipe dispatch result=" + dispatched + " reason=" + reason);
        updateNotification(dispatched ? "Swipe triggered" : "Enable accessibility first");
    }

    @SuppressLint("MissingPermission")
    private void startAudioDetection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Microphone permission missing");
            DebugLog.add(this, "Microphone permission missing");
            stopSelf();
            return;
        }

        stopAudioDetection();
        int minBufferSize = AudioRecord.getMinBufferSize(
                SOUND_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBufferSize <= 0) {
            updateNotification("Microphone unavailable");
            DebugLog.add(this, "AudioRecord min buffer invalid=" + minBufferSize);
            stopSelf();
            return;
        }

        int bufferSize = Math.max(minBufferSize, SOUND_SAMPLE_RATE / 4);
        try {
            enterCommunicationAudioMode();
            audioRecord = createAudioRecord(bufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                DebugLog.add(this, "AudioRecord init failed state=" + audioRecord.getState());
                stopAudioDetection();
                stopSelf();
                return;
            }
            bindPreferredInputDevice(audioRecord);
            attachAudioPreprocessors(audioRecord);

            audioThread = new HandlerThread("SnapAudio");
            audioThread.start();
            audioHandler = new Handler(audioThread.getLooper());
            audioRunning = true;
            running = true;
            lastSoundAnalyzeAt = 0L;
            updateNotification("Detecting finger snap");
            DebugLog.add(this, "Audio detection started sampleRate=" + SOUND_SAMPLE_RATE
                    + " buffer=" + bufferSize
                    + " threshold=" + BlinkSettings.getSoundThreshold(this)
                    + " interval=" + BlinkSettings.getSoundIntervalMs(this) + "ms"
                    + " source=" + audioSourceLabel);
            audioHandler.post(() -> readAudioLoop(bufferSize));
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to start audio detection", exception);
            DebugLog.add(this, "Audio start failed: " + exception.getMessage());
            stopAudioDetection();
            stopSelf();
        }
    }

    private void enterCommunicationAudioMode() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        communicationAudioMode = true;
        DebugLog.add(this, "Audio mode=MODE_IN_COMMUNICATION");
    }

    private AudioRecord createAudioRecord(int bufferSize) {
        RuntimeException lastError = null;
        int[] sources = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? new int[]{
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.UNPROCESSED
        }
                : new int[]{
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };

        for (int source : sources) {
            try {
                AudioRecord record = new AudioRecord(
                        source,
                        SOUND_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                );
                if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioSourceLabel = audioSourceToLabel(source);
                    return record;
                }
                record.release();
            } catch (RuntimeException exception) {
                lastError = exception;
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        audioSourceLabel = "UNAVAILABLE";
        return new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SOUND_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );
    }

    private String audioSourceToLabel(int source) {
        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            return "VOICE_COMMUNICATION_MIC";
        }
        if (source == MediaRecorder.AudioSource.UNPROCESSED) {
            return "UNPROCESSED_MIC";
        }
        if (source == MediaRecorder.AudioSource.MIC) {
            return "MIC";
        }
        if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
            return "VOICE_RECOGNITION_MIC";
        }
        return "SOURCE_" + source;
    }

    private void bindPreferredInputDevice(AudioRecord record) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }

        AudioDeviceInfo preferred = null;
        for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (deviceInfo.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                preferred = deviceInfo;
                break;
            }
        }

        if (preferred != null) {
            boolean routed = record.setPreferredDevice(preferred);
            DebugLog.add(this, "Preferred input="
                    + deviceTypeToLabel(preferred.getType())
                    + " routed=" + routed);
        }
    }

    private void attachAudioPreprocessors(AudioRecord record) {
        int audioSessionId = record.getAudioSessionId();

        if (AcousticEchoCanceler.isAvailable()) {
            acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId);
            if (acousticEchoCanceler != null) {
                acousticEchoCanceler.setEnabled(true);
                DebugLog.add(this, "AEC enabled=" + acousticEchoCanceler.getEnabled());
            } else {
                DebugLog.add(this, "AEC create failed");
            }
        } else {
            DebugLog.add(this, "AEC unavailable");
        }

        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioSessionId);
            if (noiseSuppressor != null) {
                noiseSuppressor.setEnabled(true);
                DebugLog.add(this, "NS enabled=" + noiseSuppressor.getEnabled());
            } else {
                DebugLog.add(this, "NS create failed");
            }
        } else {
            DebugLog.add(this, "NS unavailable");
        }

        if (AutomaticGainControl.isAvailable()) {
            automaticGainControl = AutomaticGainControl.create(audioSessionId);
            if (automaticGainControl != null) {
                automaticGainControl.setEnabled(false);
                DebugLog.add(this, "AGC enabled=" + automaticGainControl.getEnabled());
            } else {
                DebugLog.add(this, "AGC create failed");
            }
        } else {
            DebugLog.add(this, "AGC unavailable");
        }
    }

    private String deviceTypeToLabel(int type) {
        if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            return "BUILTIN_MIC";
        }
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return "BLUETOOTH_SCO";
        }
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
            return "WIRED_HEADSET";
        }
        if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
            return "USB_MIC";
        }
        return "TYPE_" + type;
    }

    private void readAudioLoop(int bufferSize) {
        int analysisWindowSamples = soundWindowMsToSamples(SOUND_ANALYSIS_WINDOW_MS);
        short[] buffer = new short[Math.max(analysisWindowSamples, Math.min(bufferSize / 2, analysisWindowSamples * 2))];
        try {
            audioRecord.startRecording();
            while (audioRunning && audioRecord != null) {
                if (!isScreenInteractive()) {
                    powerHandler.post(() -> stopDetectionForScreenOff("audio loop"));
                    break;
                }
                int read = audioRecord.read(buffer, 0, analysisWindowSamples);
                if (read > 0) {
                    long now = SystemClock.elapsedRealtime();
                    int intervalMs = BlinkSettings.getSoundIntervalMs(this);
                    if (now - lastSoundAnalyzeAt >= intervalMs) {
                        lastSoundAnalyzeAt = now;
                        if (read >= 128) {
                            handleAudioSamples(buffer, 0, read);
                        }
                    }
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Audio read failed", exception);
            DebugLog.add(this, "Audio read failed: " + exception.getMessage());
        }
    }

    private void handleAudioSamples(short[] buffer, int offset, int read) {
        int snapPeakThreshold = BlinkSettings.getSoundThreshold(this);
        int snapDeltaThreshold = Math.max(100, Math.round(snapPeakThreshold * SNAP_DELTA_RATIO));
        int peak = 0;
        long sum = 0;
        long deltaSum = 0;
        int strongCount = 0;
        int prev = 0;
        for (int i = offset; i < offset + read; i++) {
            int value = Math.abs((int) buffer[i]);
            peak = Math.max(peak, value);
            sum += value;
            if (i > offset) {
                deltaSum += Math.abs(value - prev);
            }
            prev = value;
        }

        int strongThreshold = Math.max(4000, (int) (peak * 0.60f));
        for (int i = offset; i < offset + read; i++) {
            int value = Math.abs((int) buffer[i]);
            if (value >= strongThreshold) {
                strongCount++;
            }
        }

        double average = sum / (double) read;
        soundNoiseFloor = soundNoiseFloor * 0.92 + average * 0.08;
        double ratio = peak / Math.max(1.0, soundNoiseFloor);
        double delta = peak - soundNoiseFloor;
        double crest = peak / Math.max(1.0, average);
        double edgeRatio = (deltaSum / (double) Math.max(1, read - 1)) / Math.max(1.0, average);
        double strongFraction = strongCount / (double) Math.max(1, read);
        long now = SystemClock.elapsedRealtime();

        if (now - lastSoundLogAt > 2000) {
            lastSoundLogAt = now;
            DebugLog.add(this, String.format(Locale.US,
                    "sound peak=%d noise=%.0f ratio=%.1f crest=%.1f edge=%.1f strong=%.2f threshold=%d interval=%dms accessibility=%s source=%s",
                    peak, soundNoiseFloor, ratio, crest, edgeRatio, strongFraction,
                    snapPeakThreshold, BlinkSettings.getSoundIntervalMs(this),
                    BlinkAccessibilityService.isReady(), audioSourceLabel));
        }

        if (now - lastSnapAcceptedAt < SNAP_REFRACTORY_MS) {
            return;
        }

        if (peak >= snapPeakThreshold
                && delta >= snapDeltaThreshold
                && ratio >= SNAP_MIN_RATIO
                && crest >= SNAP_MIN_CREST
                && edgeRatio >= SNAP_MIN_EDGE_RATIO
                && strongFraction >= SNAP_MIN_STRONG_FRACTION
                && strongFraction <= SNAP_MAX_STRONG_FRACTION) {
            lastSnapAcceptedAt = now;
            DebugLog.add(this, String.format(Locale.US,
                    "Finger snap detected peak=%d noise=%.0f ratio=%.1f crest=%.1f edge=%.1f strong=%.2f threshold=%d interval=%dms source=%s",
                    peak, soundNoiseFloor, ratio, crest, edgeRatio, strongFraction,
                    snapPeakThreshold, BlinkSettings.getSoundIntervalMs(this), audioSourceLabel));
            triggerSwipe("Finger snap");
            soundNoiseFloor = Math.max(soundNoiseFloor, peak * 0.45);
        }
    }

    private int soundWindowMsToSamples(int windowMs) {
        return Math.max(128, Math.round(windowMs * SOUND_SAMPLE_RATE / 1000f));
    }

    private void stopAudioDetection() {
        audioRunning = false;
        lastSoundAnalyzeAt = 0L;
        releaseAudioPreprocessors();

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            audioRecord.release();
            audioRecord = null;
            DebugLog.add(this, "AudioRecord released");
        }
        audioSourceLabel = "VOICE_RECOGNITION";
        leaveCommunicationAudioMode();

        if (audioThread != null) {
            audioThread.quitSafely();
            try {
                audioThread.join(1000);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
            audioHandler = null;
            DebugLog.add(this, "Audio thread stopped");
        }
    }

    private void releaseAudioPreprocessors() {
        if (acousticEchoCanceler != null) {
            acousticEchoCanceler.release();
            acousticEchoCanceler = null;
        }
        if (noiseSuppressor != null) {
            noiseSuppressor.release();
            noiseSuppressor = null;
        }
        if (automaticGainControl != null) {
            automaticGainControl.release();
            automaticGainControl = null;
        }
    }

    private void leaveCommunicationAudioMode() {
        if (!communicationAudioMode) {
            return;
        }
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }
        communicationAudioMode = false;
        DebugLog.add(this, "Audio mode=MODE_NORMAL");
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
        lastCameraImageAt = 0L;
        resetFaceChangeState();

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
                "Mouth detection",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Front camera face change detection service");
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

}
