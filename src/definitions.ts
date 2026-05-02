import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

export type CapabilityReason =
  | 'no_camera'
  | 'no_gps'
  | 'gps_disabled'
  | 'no_accelerometer'
  | 'no_gyroscope'
  | 'no_magnetometer'
  | 'no_fused_orientation'
  | 'permission_denied'
  | 'web_unsupported';

export interface CameraCapability {
  hardware: boolean;
  permission: PermissionState;
}

export interface LocationCapability {
  hardware: boolean;
  serviceEnabled: boolean;
  permission: PermissionState;
}

export interface CapabilitiesResult {
  /**
   * True only if every capability required to start an AR session is present
   * and authorized. When false, `reason` carries the first blocker found.
   */
  ready: boolean;

  camera: CameraCapability;
  location: LocationCapability;

  accelerometer: boolean;
  gyroscope: boolean;
  magnetometer: boolean;

  /**
   * Native fused orientation: Android `TYPE_ROTATION_VECTOR`, iOS `CMDeviceMotion`.
   * When true, the plugin emits a stable heading/pitch/roll without the caller
   * having to fuse raw sensor streams in JS.
   */
  fusedOrientation: boolean;

  /**
   * iOS only — heading is corrected to true (geographic) north using the
   * device's location-derived magnetic declination. False on Android (heading
   * is magnetic north; callers can apply declination if needed).
   */
  trueHeading: boolean;

  reason?: CapabilityReason;
}

export interface StartOptions {
  /**
   * Preferred orientation event rate in Hz. Native floors this to a hardware-
   * supported rate. Default 30. Values above 60 waste battery without visible
   * benefit for GPS-anchored markers.
   */
  orientationHz?: number;

  /** Default 'rear'. */
  camera?: 'rear' | 'front';

  /**
   * Make the WebView background transparent on start, restore on stop. Default true.
   * Set false if the host app manages WebView transparency itself.
   */
  transparentWebView?: boolean;
}

export interface StartResult {
  /**
   * Display-corrected horizontal field of view in degrees, after the camera
   * preview is cropped to the screen aspect ratio. Always positive. Use this
   * value for screen-X math; do not call `Math.abs` on it.
   */
  horizontalFovDeg: number;

  /** Display-corrected vertical FOV in degrees. Always positive. */
  verticalFovDeg: number;

  /** Actual orientation event rate after clamping to hardware. */
  orientationHz: number;
}

export type OrientationAccuracy = 'unreliable' | 'low' | 'medium' | 'high';

export interface OrientationEvent {
  /** Compass heading in degrees, 0=North, 90=East, 180=South, 270=West. */
  heading: number;

  /** Pitch in degrees: 0=horizontal, +90=looking up, -90=looking down. */
  pitch: number;

  /** Roll in degrees: 0=upright, ±180=upside down. */
  roll: number;

  /**
   * Compass calibration quality. When 'low' or 'unreliable' the heading may
   * drift; the host app should prompt the user to figure-8 the device.
   */
  accuracy: OrientationAccuracy;

  /** Monotonic timestamp in ms — useful for detecting stalled streams. */
  timestamp: number;
}

export interface GeoscanPlugin {
  /**
   * Probe hardware presence and current permission state. Does not prompt the
   * user. Replaces the cordova-plugins-diagnostic + per-sensor probing pattern.
   */
  checkCapabilities(): Promise<CapabilitiesResult>;

  /**
   * Request any missing permissions, start the camera preview behind a
   * transparent WebView, and begin emitting `orientation` events.
   * Idempotent — calling while a session is active resolves with the current
   * session's `StartResult` without re-initializing native resources.
   */
  startSession(options?: StartOptions): Promise<StartResult>;

  /** Stop camera, stop orientation stream, restore WebView opacity. Idempotent. */
  stopSession(): Promise<void>;

  /**
   * Pause without tearing down — for when the AR page is backgrounded or
   * obscured by a modal. Resume by calling `startSession` again.
   */
  pauseSession(): Promise<void>;

  /**
   * Single fused orientation stream. There is intentionally no separate
   * accelerometer/gyroscope/magnetometer event — the plugin only emits the
   * fused result, and `removeAllListeners()` is the only teardown the caller
   * needs to manage.
   */
  addListener(
    eventName: 'orientation',
    listenerFunc: (event: OrientationEvent) => void,
  ): Promise<PluginListenerHandle>;

  removeAllListeners(): Promise<void>;
}
