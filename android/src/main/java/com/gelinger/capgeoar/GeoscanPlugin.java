package com.gelinger.capgeoar;

import android.Manifest;
import android.location.LocationManager;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "Geoscan",
    permissions = {
        @Permission(alias = "camera", strings = { Manifest.permission.CAMERA }),
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION })
    }
)
public class GeoscanPlugin extends Plugin {

    private Geoscan implementation;

    @Override
    public void load() {
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        View webView = getBridge().getWebView();
        ViewGroup parent = (ViewGroup) webView.getParent();
        implementation = new Geoscan(getContext(), activity, parent, webView);
        implementation.setOrientationCallback((heading, pitch, roll, accuracy, timestamp) -> {
            JSObject event = new JSObject();
            event.put("heading", heading);
            event.put("pitch", pitch);
            event.put("roll", roll);
            event.put("accuracy", accuracy);
            event.put("timestamp", timestamp);
            notifyListeners("orientation", event);
        });
    }

    @PluginMethod
    public void checkCapabilities(PluginCall call) {
        Geoscan.Capabilities caps = implementation.checkCapabilities();

        JSObject result = new JSObject();
        result.put("camera", new JSObject()
                .put("hardware", caps.cameraHardware)
                .put("permission", permissionStateToJs(getPermissionState("camera"))));
        result.put("location", new JSObject()
                .put("hardware", caps.locationHardware)
                .put("serviceEnabled", caps.locationServiceEnabled)
                .put("permission", permissionStateToJs(getPermissionState("location"))));
        result.put("accelerometer", caps.accelerometer);
        result.put("gyroscope", caps.gyroscope);
        result.put("magnetometer", caps.magnetometer);
        result.put("fusedOrientation", caps.fusedOrientation);
        result.put("trueHeading", caps.trueHeading);

        String reason = firstBlocker(caps,
                getPermissionState("camera"),
                getPermissionState("location"));
        boolean ready = reason == null;
        result.put("ready", ready);
        if (reason != null) result.put("reason", reason);

        call.resolve(result);
    }

    @PluginMethod
    public void startSession(PluginCall call) {
        if (implementation.isSessionActive()) {
            resolveStartResult(call, lastStart);
            return;
        }

        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "cameraPermissionCallback");
            return;
        }
        launchSession(call);
    }

    @PermissionCallback
    private void cameraPermissionCallback(PluginCall call) {
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            call.reject("permission_denied", "Camera permission was denied.");
            return;
        }
        launchSession(call);
    }

    private Geoscan.StartResult lastStart;

    private void launchSession(PluginCall call) {
        Geoscan.StartConfig config = new Geoscan.StartConfig();
        Integer hz = call.getInt("orientationHz");
        if (hz != null) config.orientationHz = hz;
        String camera = call.getString("camera");
        if (camera != null) config.camera = camera;
        Boolean transparent = call.getBoolean("transparentWebView");
        if (transparent != null) config.transparentWebView = transparent;

        getActivity().runOnUiThread(() -> implementation.start(config, new Geoscan.StartCallback() {
            @Override
            public void onSuccess(Geoscan.StartResult result) {
                lastStart = result;
                resolveStartResult(call, result);
            }

            @Override
            public void onError(String code, String message) {
                call.reject(message != null ? message : code, code);
            }
        }));
    }

    private void resolveStartResult(PluginCall call, Geoscan.StartResult r) {
        JSObject ret = new JSObject();
        ret.put("horizontalFovDeg", r.horizontalFovDeg);
        ret.put("verticalFovDeg", r.verticalFovDeg);
        ret.put("orientationHz", r.orientationHz);
        call.resolve(ret);
    }

    @PluginMethod
    public void stopSession(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            implementation.stop();
            lastStart = null;
            call.resolve();
        });
    }

    @PluginMethod
    public void pauseSession(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            implementation.pause();
            call.resolve();
        });
    }

    private static String permissionStateToJs(PermissionState state) {
        if (state == null) return "unknown";
        switch (state) {
            case GRANTED: return "granted";
            case DENIED: return "denied";
            case PROMPT_WITH_RATIONALE:
            case PROMPT: return "prompt";
            default: return "unknown";
        }
    }

    private static String firstBlocker(Geoscan.Capabilities caps,
                                       PermissionState cameraPerm,
                                       PermissionState locationPerm) {
        if (!caps.cameraHardware) return "no_camera";
        if (!caps.accelerometer) return "no_accelerometer";
        if (!caps.gyroscope) return "no_gyroscope";
        if (!caps.magnetometer) return "no_magnetometer";
        if (!caps.fusedOrientation) return "no_fused_orientation";
        if (!caps.locationHardware) return "no_gps";
        if (!caps.locationServiceEnabled) return "gps_disabled";
        if (cameraPerm == PermissionState.DENIED || locationPerm == PermissionState.DENIED) {
            return "permission_denied";
        }
        return null;
    }
}
