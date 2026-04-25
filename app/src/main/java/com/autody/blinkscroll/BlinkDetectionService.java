package com.autody.blinkscroll;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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
    private static final float MOUTH_OPEN_RATIO_THRESHOLD = 0.055f;
    private static final int SOUND_SAMPLE_RATE = 16000;
    private static final int SNAP_MIN_PEAK = 9000;
    private static final int SNAP_MIN_DELTA = 5500;
    private static final float SNAP_MIN_RATIO = 4.5f;

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
    private long lastSoundLogAt;
    private boolean mouthOpen;
    private boolean foregroundStarted;
    private HandlerThread audioThread;
    private Handler audioHandler;
    private AudioRecord audioRecord;
    private volatile boolean audioRunning;
    private double soundNoiseFloor = 700.0;

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

        int mode = BlinkSettings.getDetectionMode(this);
        if (mode == BlinkSettings.MODE_SOUND) {
            DebugLog.add(this, "Sound mode armed: snap detection from microphone");
            closeCamera();
            startAudioDetection();
        } else {
            DebugLog.add(this, "Mouth mode armed: interval=" + BlinkSettings.getMouthIntervalMs(this)
                    + "ms openRatio>=" + MOUTH_OPEN_RATIO_THRESHOLD);
            stopAudioDetection();
            startCamera();
        }
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
            updateNotification("Detecting open mouth");
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
            return;
        }

        Float mouthOpenRatio = getMouthOpenRatio(face);
        if (mouthOpenRatio == null) {
            if (now - lastFaceLogAt > 2000) {
                lastFaceLogAt = now;
                DebugLog.add(this, "Face found but mouth contours unavailable");
            }
            return;
        }

        boolean wasMouthOpen = mouthOpen;
        mouthOpen = mouthOpenRatio >= MOUTH_OPEN_RATIO_THRESHOLD;

        if (now - lastFaceLogAt > 2000) {
            lastFaceLogAt = now;
            DebugLog.add(this, String.format(Locale.US,
                    "faces=%d mouthRatio=%.3f open=%s interval=%dms accessibility=%s",
                    faces.size(), mouthOpenRatio, mouthOpen, BlinkSettings.getMouthIntervalMs(this),
                    BlinkAccessibilityService.isReady()));
        }

        if (!wasMouthOpen && mouthOpen) {
            DebugLog.add(this, String.format(Locale.US, "Mouth opened ratio=%.3f", mouthOpenRatio));
        }
        if (wasMouthOpen && !mouthOpen) {
            DebugLog.add(this, String.format(Locale.US, "Mouth closed ratio=%.3f", mouthOpenRatio));
        }

        if (!wasMouthOpen && mouthOpen) {
            DebugLog.add(this, "Open mouth detected");
            triggerSwipe("Open mouth");
        } else if (now - lastNotificationAt > 5000) {
            updateNotification(BlinkAccessibilityService.isReady()
                    ? "Detecting open mouth"
                    : "Waiting for accessibility");
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
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SOUND_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                DebugLog.add(this, "AudioRecord init failed state=" + audioRecord.getState());
                stopAudioDetection();
                stopSelf();
                return;
            }

            audioThread = new HandlerThread("SnapAudio");
            audioThread.start();
            audioHandler = new Handler(audioThread.getLooper());
            audioRunning = true;
            running = true;
            updateNotification("Detecting finger snap");
            DebugLog.add(this, "Audio detection started sampleRate=" + SOUND_SAMPLE_RATE
                    + " buffer=" + bufferSize);
            audioHandler.post(() -> readAudioLoop(bufferSize));
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to start audio detection", exception);
            DebugLog.add(this, "Audio start failed: " + exception.getMessage());
            stopAudioDetection();
            stopSelf();
        }
    }

    private void readAudioLoop(int bufferSize) {
        short[] buffer = new short[Math.max(256, bufferSize / 2)];
        try {
            audioRecord.startRecording();
            while (audioRunning && audioRecord != null) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    handleAudioSamples(buffer, read);
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Audio read failed", exception);
            DebugLog.add(this, "Audio read failed: " + exception.getMessage());
        }
    }

    private void handleAudioSamples(short[] buffer, int read) {
        int peak = 0;
        long sum = 0;
        for (int i = 0; i < read; i++) {
            int value = Math.abs((int) buffer[i]);
            peak = Math.max(peak, value);
            sum += value;
        }

        double average = sum / (double) read;
        soundNoiseFloor = soundNoiseFloor * 0.92 + average * 0.08;
        double ratio = peak / Math.max(1.0, soundNoiseFloor);
        double delta = peak - soundNoiseFloor;
        long now = SystemClock.elapsedRealtime();

        if (now - lastSoundLogAt > 2000) {
            lastSoundLogAt = now;
            DebugLog.add(this, String.format(Locale.US,
                    "sound peak=%d noise=%.0f ratio=%.1f accessibility=%s",
                    peak, soundNoiseFloor, ratio, BlinkAccessibilityService.isReady()));
        }

        if (peak >= SNAP_MIN_PEAK && delta >= SNAP_MIN_DELTA && ratio >= SNAP_MIN_RATIO) {
            DebugLog.add(this, String.format(Locale.US,
                    "Finger snap detected peak=%d noise=%.0f ratio=%.1f",
                    peak, soundNoiseFloor, ratio));
            triggerSwipe("Finger snap");
            soundNoiseFloor = Math.max(soundNoiseFloor, peak * 0.45);
        }
    }

    private void stopAudioDetection() {
        audioRunning = false;

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
                "Mouth detection",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Front camera mouth detection service");
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
