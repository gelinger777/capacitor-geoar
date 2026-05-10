package com.gelinger.capgeoar;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.os.SystemClock;
import android.util.Size;
import android.util.SizeF;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

public class Geoscan implements SensorEventListener {

    public interface OrientationCallback {
        void onOrientation(double heading, double pitch, double roll, String accuracy, long timestamp);
    }

    public static class Capabilities {
        public boolean cameraHardware;
        public boolean locationHardware;
        public boolean locationServiceEnabled;
        public boolean accelerometer;
        public boolean gyroscope;
        public boolean magnetometer;
        public boolean fusedOrientation;
        public boolean trueHeading; // false on Android — heading is magnetic
    }

    public static class StartConfig {
        public String camera = "rear";
        public int orientationHz = 30;
        public boolean transparentWebView = true;
    }

    public static class StartResult {
        public double horizontalFovDeg;
        public double verticalFovDeg;
        public int orientationHz;
    }

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final ViewGroup webViewParent;
    private final View webView;
    private OrientationCallback orientationCallback;

    private PreviewView previewView;
    private ProcessCameraProvider cameraProvider;
    private Camera boundCamera;
    private Integer originalWebViewBackground;

    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final Sensor magneticSensor;
    private final Sensor accelerometer;
    private final Sensor gyroscope;
    private boolean sensorsRegistered;
    private int currentAccuracy = SensorManager.SENSOR_STATUS_NO_CONTACT;
    private final float[] rotationMatrix = new float[9];

    public Geoscan(Context context, LifecycleOwner lifecycleOwner, ViewGroup webViewParent, View webView) {
        this.context = context;
        this.lifecycleOwner = lifecycleOwner;
        this.webViewParent = webViewParent;
        this.webView = webView;

        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) : null;
        magneticSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) : null;
        accelerometer = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        gyroscope = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) : null;
    }

    public void setOrientationCallback(OrientationCallback callback) {
        this.orientationCallback = callback;
    }

    public Capabilities checkCapabilities() {
        Capabilities caps = new Capabilities();
        PackageManager pm = context.getPackageManager();

        caps.cameraHardware = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        caps.locationHardware = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
                || pm.hasSystemFeature(PackageManager.FEATURE_LOCATION);

        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        caps.locationServiceEnabled = lm != null && (
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));

        caps.accelerometer = accelerometer != null;
        caps.gyroscope = gyroscope != null;
        caps.magnetometer = magneticSensor != null;
        caps.fusedOrientation = rotationSensor != null;
        caps.trueHeading = false;

        return caps;
    }

    public boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void start(@NonNull StartConfig config, @NonNull StartCallback callback) {
        if (cameraProvider != null) {
            callback.onError("session_already_active", null);
            return;
        }

        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(context);

        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                bindCameraAndComputeFov(config, callback);
                registerSensorListener(config.orientationHz);
                if (config.transparentWebView) {
                    applyWebViewTransparency();
                }
            } catch (Exception e) {
                cameraProvider = null;
                callback.onError("camera_start_failed", e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void bindCameraAndComputeFov(StartConfig config, StartCallback callback)
            throws CameraAccessException {
        previewView = new PreviewView(context);
        // Use TextureView-backed implementation (COMPATIBLE) instead of the default
        // SurfaceView (PERFORMANCE). SurfaceView punches a hole through the window
        // and is z-ordered separately from the regular view tree, which breaks
        // composition with a transparent WebView above (the preview can appear
        // clipped to a sub-region of the screen, especially under Capacitor 8's
        // edge-to-edge layout). TextureView is a regular View and blends correctly.
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        int webViewIndex = webViewParent.indexOfChild(webView);
        webViewParent.addView(previewView, webViewIndex);

        CameraSelector selector = "front".equals(config.camera)
                ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        cameraProvider.unbindAll();
        boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview);

        StartResult result = new StartResult();
        double[] fov = computeDisplayFov(config.camera);
        result.horizontalFovDeg = fov[0];
        result.verticalFovDeg = fov[1];
        result.orientationHz = clampOrientationHz(config.orientationHz);
        callback.onSuccess(result);
    }

    private double[] computeDisplayFov(String cameraSide) throws CameraAccessException {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return new double[]{60.0, 45.0};

        String targetId = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics chars = cm.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing == null) continue;
            if ("front".equals(cameraSide) && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                targetId = id;
                break;
            }
            if (!"front".equals(cameraSide) && facing == CameraCharacteristics.LENS_FACING_BACK) {
                targetId = id;
                break;
            }
        }
        if (targetId == null) return new double[]{60.0, 45.0};

        CameraCharacteristics chars = cm.getCameraCharacteristics(targetId);
        SizeF physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float[] focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        Size pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);

        if (physicalSize == null || focals == null || focals.length == 0 || pixelArray == null) {
            return new double[]{60.0, 45.0};
        }

        float focal = focals[0];
        // Sensor "wide" axis = longer of the physical dimensions (and the matching pixel-array side).
        double sensorWideFov, sensorNarrowFov;
        int sw, sh;
        if (physicalSize.getWidth() >= physicalSize.getHeight()) {
            sensorWideFov = Math.toDegrees(2 * Math.atan(physicalSize.getWidth() / (2 * focal)));
            sensorNarrowFov = Math.toDegrees(2 * Math.atan(physicalSize.getHeight() / (2 * focal)));
            sw = pixelArray.getWidth();
            sh = pixelArray.getHeight();
        } else {
            sensorWideFov = Math.toDegrees(2 * Math.atan(physicalSize.getHeight() / (2 * focal)));
            sensorNarrowFov = Math.toDegrees(2 * Math.atan(physicalSize.getWidth() / (2 * focal)));
            sw = pixelArray.getHeight();
            sh = pixelArray.getWidth();
        }

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return new double[]{sensorWideFov, sensorNarrowFov};
        int vw = wm.getDefaultDisplay().getWidth();
        int vh = wm.getDefaultDisplay().getHeight();
        if (vw == 0 || vh == 0) return new double[]{sensorWideFov, sensorNarrowFov};

        // Phone camera sensors are physically in landscape. In a portrait host view the
        // preview is rotated 90° so the sensor's wide axis maps to the screen's height.
        boolean portrait = vh > vw;
        int rotW = portrait ? sh : sw;
        int rotH = portrait ? sw : sh;
        double rotXFov = portrait ? sensorNarrowFov : sensorWideFov;
        double rotYFov = portrait ? sensorWideFov : sensorNarrowFov;

        // CameraX PreviewView FILL_CENTER: scale until both dimensions cover the view; longer dimension is cropped.
        double scale = Math.max((double) vw / rotW, (double) vh / rotH);
        double visW = Math.min(rotW, vw / scale);
        double visH = Math.min(rotH, vh / scale);

        double h = Math.toDegrees(2 * Math.atan(
                Math.tan(Math.toRadians(rotXFov / 2)) * (visW / rotW)));
        double v = Math.toDegrees(2 * Math.atan(
                Math.tan(Math.toRadians(rotYFov / 2)) * (visH / rotH)));
        return new double[]{Math.abs(h), Math.abs(v)};
    }

    private int clampOrientationHz(int requested) {
        if (requested < 5) return 5;
        if (requested > 100) return 100;
        return requested;
    }

    private void registerSensorListener(int hz) {
        if (sensorManager == null || rotationSensor == null) return;
        if (sensorsRegistered) return;
        int periodUs = 1_000_000 / clampOrientationHz(hz);
        sensorManager.registerListener(this, rotationSensor, periodUs);
        sensorsRegistered = true;
    }

    private void unregisterSensorListener() {
        if (sensorManager == null) return;
        if (!sensorsRegistered) return;
        sensorManager.unregisterListener(this);
        sensorsRegistered = false;
    }

    private void applyWebViewTransparency() {
        originalWebViewBackground = Color.WHITE;
        webView.setBackgroundColor(Color.TRANSPARENT);
        if (webView instanceof android.webkit.WebView) {
            ((android.webkit.WebView) webView).setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private void restoreWebView() {
        if (originalWebViewBackground != null) {
            webView.setBackgroundColor(originalWebViewBackground);
            originalWebViewBackground = null;
        }
    }

    public void pause() {
        unregisterSensorListener();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    public void stop() {
        unregisterSensorListener();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        boundCamera = null;
        if (previewView != null && previewView.getParent() == webViewParent) {
            webViewParent.removeView(previewView);
        }
        previewView = null;
        restoreWebView();
    }

    public boolean isSessionActive() {
        return cameraProvider != null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        if (orientationCallback == null) return;

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);

        // Derive heading/pitch/roll from the camera direction in world frame
        // rather than from SensorManager.getOrientation(), which decomposes the
        // rotation around body axes and produces wrong "camera tilt" values when
        // the device is held upright (the screen plane is at ±90° to the ground,
        // landing the standard Euler decomposition near gimbal lock). The matrix
        // approach is orientation-agnostic.
        //
        // Android: v_world = R * v_body. World axes are X=East, Y=North (magnetic),
        // Z=Up. Camera looks along -Z body, so v_world = R * (0, 0, -1):
        //   v_world_i = -R[i*3 + 2]   (row-major 3x3).
        double camEast = -rotationMatrix[2];
        double camNorth = -rotationMatrix[5];
        double camUp = -rotationMatrix[8];

        // Compass heading: 0=N, 90=E, clockwise. Magnetic on Android (matches
        // the trueHeading=false capability flag).
        double heading = Math.toDegrees(Math.atan2(camEast, camNorth));
        if (heading < 0) heading += 360;

        // Pitch: angle above horizontal (positive = camera looking up).
        double horiz = Math.sqrt(camEast * camEast + camNorth * camNorth);
        double pitch = Math.toDegrees(Math.atan2(camUp, horiz));

        // Roll: device twist around camera axis (z-component of body X vs body Y
        // in world frame). 0 = portrait upright; ±90 = landscape; ±180 = upside
        // down. Same sign convention as the iOS implementation.
        double roll = Math.toDegrees(Math.atan2(rotationMatrix[6], rotationMatrix[7]));

        orientationCallback.onOrientation(heading, pitch, roll,
                accuracyToString(currentAccuracy), SystemClock.elapsedRealtime());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            currentAccuracy = accuracy;
        }
    }

    private static String accuracyToString(int code) {
        switch (code) {
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH: return "high";
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM: return "medium";
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW: return "low";
            default: return "unreliable";
        }
    }

    public interface StartCallback {
        void onSuccess(StartResult result);
        void onError(@NonNull String code, @Nullable String message);
    }
}
