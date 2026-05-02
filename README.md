# capacitor-geoar

GeoAR done simple — a Capacitor 8 plugin that gives the WebView a transparent camera preview and a single fused device-orientation stream. Use it as the substrate for GPS-anchored AR overlays drawn in HTML, CSS, canvas, or WebGL.

```text
┌───────────────────────────┐
│   Your HTML/CSS overlay   │  ← drawn by your app, transparent WebView
│        (markers,          │
│  compass, mini-map, etc.) │
├───────────────────────────┤
│   Camera preview layer    │  ← managed by the plugin
└───────────────────────────┘
        ↑ orientation events (heading, pitch, roll @ N Hz)
```

## What it does and doesn't do

| Does                                                                 | Does NOT                                              |
| -------------------------------------------------------------------- | ----------------------------------------------------- |
| Transparent rear/front camera preview behind your WebView            | ARKit / ARCore tracking, SLAM, world anchors          |
| Native fused orientation (iOS `CMDeviceMotion`, Android `TYPE_ROTATION_VECTOR`) | Stereoscopic / VR side-by-side rendering              |
| Display-corrected horizontal & vertical field of view in degrees     | 3D scene rendering, raycasting, hit testing           |
| Capability + permission probe in one call                            | GPS — use `@capacitor/geolocation` alongside this     |
| iOS true-north heading when available                                | POI math — that's the host app's job (it's just trig) |

## Install

```bash
npm install capacitor-geoar
npx cap sync
```

The JS plugin is registered as **`Geoscan`** — the npm package is `capacitor-geoar`, but the symbol you import is `Geoscan`:

```ts
import { Geoscan } from 'capacitor-geoar';
```

## Platform requirements

- **iOS**: iOS 14+. AVFoundation + CoreMotion + CoreLocation. True-north heading is used when available (`CLLocationManager.headingAvailable()`).
- **Android**: API 24+. Requires `TYPE_ROTATION_VECTOR` (fused orientation). Heading is magnetic north — apply declination in app code via Android's `GeomagneticField` if you need true north.
- **Web**: not supported. `checkCapabilities()` returns `{ ready: false, reason: 'web_unsupported' }`; `startSession()` rejects.

## Native configuration

The plugin needs camera and location permissions. Declare them in the host app — Capacitor does not add these automatically.

**iOS — `ios/App/App/Info.plist`:**

```xml
<key>NSCameraUsageDescription</key>
<string>Used to show the camera behind the AR overlay.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>Used to compute the bearing of nearby points of interest.</string>
```

**Android — `android/app/src/main/AndroidManifest.xml`:**

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<uses-feature android:name="android.hardware.camera"        android:required="true" />
<uses-feature android:name="android.hardware.sensor.gyroscope"      android:required="true" />
<uses-feature android:name="android.hardware.sensor.accelerometer"  android:required="true" />
<uses-feature android:name="android.hardware.sensor.compass"        android:required="true" />
```

Capacitor will request runtime permissions for camera; for location use `@capacitor/geolocation` to trigger the prompt.

## Quick start

```ts
import { Geoscan } from 'capacitor-geoar';

const caps = await Geoscan.checkCapabilities();
if (!caps.ready) {
  console.warn('Cannot start AR session:', caps.reason);
  return;
}

const { horizontalFovDeg, verticalFovDeg, orientationHz } =
  await Geoscan.startSession({ orientationHz: 30, camera: 'rear' });

const handle = await Geoscan.addListener('orientation', (e) => {
  // e.heading (0=N, 90=E), e.pitch (+up/-down), e.roll, e.accuracy, e.timestamp
});

// later:
await handle.remove();
await Geoscan.stopSession();
```

The host app's body / WebView typically needs to be transparent so the camera shows through. By default, `startSession({ transparentWebView: true })` toggles this for you and `stopSession()` restores it. If you manage WebView background yourself, pass `transparentWebView: false` and apply `body { background: transparent }` while a session is active.

## Project a world bearing onto the screen

Almost every overlay needs to answer "where on screen does *this* compass bearing land right now?". The math is small enough to fit here, and it's exactly what the [example app](example-app/) does for the four cardinal directions.

```ts
function projectBearing(
  bearing: number,           // POI bearing from user, 0..360
  heading: number,           // current device heading, 0..360
  pitch: number,             // current device pitch (-90..90)
  fov: { horizontalDeg: number; verticalDeg: number }, // from startSession()
): { x: number; y: number } | null {
  const halfH = fov.horizontalDeg / 2;
  const halfV = fov.verticalDeg / 2;

  // Wrap into [-180, 180] so a bearing just east of north doesn't read as +358°
  // when heading is just west of north.
  const delta = ((bearing - heading + 540) % 360) - 180;

  if (Math.abs(delta) > halfH) return null;       // outside frustum horizontally
  if (Math.abs(pitch) > halfV) return null;       // outside frustum vertically

  return {
    x: ((delta + halfH) / fov.horizontalDeg) * 100,    // % of screen width
    y: 50 + (pitch / fov.verticalDeg) * 100,           // % of screen height
  };
}
```

For real POIs, compute `bearing` from user GPS to the POI with the standard great-circle formula (see [`demo/src/app/util/geo-math.ts`](../demo/src/app/util/geo-math.ts) for a reference).

## Calibration accuracy

Compass quality varies — the plugin reports it on every event:

```ts
addListener('orientation', (e) => {
  if (e.accuracy === 'low' || e.accuracy === 'unreliable') {
    showFigure8Hint(); // ask the user to wave the phone in a figure-8 pattern
  }
});
```

On Android the value reflects `SensorManager.SENSOR_STATUS_ACCURACY_*`; on iOS it's derived from `CLHeading.headingAccuracy` (degrees of confidence).

## API

<docgen-index>

* [`checkCapabilities()`](#checkcapabilities)
* [`startSession(...)`](#startsession)
* [`stopSession()`](#stopsession)
* [`pauseSession()`](#pausesession)
* [`addListener('orientation', ...)`](#addlistenerorientation-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### checkCapabilities()

```typescript
checkCapabilities() => Promise<CapabilitiesResult>
```

Probe hardware presence and current permission state. Does not prompt the
user. Replaces the cordova-plugins-diagnostic + per-sensor probing pattern.

**Returns:** <code>Promise&lt;<a href="#capabilitiesresult">CapabilitiesResult</a>&gt;</code>

--------------------


### startSession(...)

```typescript
startSession(options?: StartOptions | undefined) => Promise<StartResult>
```

Request any missing permissions, start the camera preview behind a
transparent WebView, and begin emitting `orientation` events.
Idempotent — calling while a session is active resolves with the current
session's <a href="#startresult">`StartResult`</a> without re-initializing native resources.

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#startoptions">StartOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#startresult">StartResult</a>&gt;</code>

--------------------


### stopSession()

```typescript
stopSession() => Promise<void>
```

Stop camera, stop orientation stream, restore WebView opacity. Idempotent.

--------------------


### pauseSession()

```typescript
pauseSession() => Promise<void>
```

Pause without tearing down — for when the AR page is backgrounded or
obscured by a modal. Resume by calling `startSession` again.

--------------------


### addListener('orientation', ...)

```typescript
addListener(eventName: 'orientation', listenerFunc: (event: OrientationEvent) => void) => Promise<PluginListenerHandle>
```

Single fused orientation stream. There is intentionally no separate
accelerometer/gyroscope/magnetometer event — the plugin only emits the
fused result, and `removeAllListeners()` is the only teardown the caller
needs to manage.

| Param              | Type                                                                              |
| ------------------ | --------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'orientation'</code>                                                        |
| **`listenerFunc`** | <code>(event: <a href="#orientationevent">OrientationEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

--------------------


### Interfaces


#### CapabilitiesResult

| Prop                   | Type                                                              | Description                                                                                                                                                                                                 |
| ---------------------- | ----------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`ready`**            | <code>boolean</code>                                              | True only if every capability required to start an AR session is present and authorized. When false, `reason` carries the first blocker found.                                                              |
| **`camera`**           | <code><a href="#cameracapability">CameraCapability</a></code>     |                                                                                                                                                                                                             |
| **`location`**         | <code><a href="#locationcapability">LocationCapability</a></code> |                                                                                                                                                                                                             |
| **`accelerometer`**    | <code>boolean</code>                                              |                                                                                                                                                                                                             |
| **`gyroscope`**        | <code>boolean</code>                                              |                                                                                                                                                                                                             |
| **`magnetometer`**     | <code>boolean</code>                                              |                                                                                                                                                                                                             |
| **`fusedOrientation`** | <code>boolean</code>                                              | Native fused orientation: Android `TYPE_ROTATION_VECTOR`, iOS `CMDeviceMotion`. When true, the plugin emits a stable heading/pitch/roll without the caller having to fuse raw sensor streams in JS.         |
| **`trueHeading`**      | <code>boolean</code>                                              | iOS only — heading is corrected to true (geographic) north using the device's location-derived magnetic declination. False on Android (heading is magnetic north; callers can apply declination if needed). |
| **`reason`**           | <code><a href="#capabilityreason">CapabilityReason</a></code>     |                                                                                                                                                                                                             |


#### CameraCapability

| Prop             | Type                                                        |
| ---------------- | ----------------------------------------------------------- |
| **`hardware`**   | <code>boolean</code>                                        |
| **`permission`** | <code><a href="#permissionstate">PermissionState</a></code> |


#### LocationCapability

| Prop                 | Type                                                        |
| -------------------- | ----------------------------------------------------------- |
| **`hardware`**       | <code>boolean</code>                                        |
| **`serviceEnabled`** | <code>boolean</code>                                        |
| **`permission`**     | <code><a href="#permissionstate">PermissionState</a></code> |


#### StartResult

| Prop                   | Type                | Description                                                                                                                                                                                             |
| ---------------------- | ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`horizontalFovDeg`** | <code>number</code> | Display-corrected horizontal field of view in degrees, after the camera preview is cropped to the screen aspect ratio. Always positive. Use this value for screen-X math; do not call `Math.abs` on it. |
| **`verticalFovDeg`**   | <code>number</code> | Display-corrected vertical FOV in degrees. Always positive.                                                                                                                                             |
| **`orientationHz`**    | <code>number</code> | Actual orientation event rate after clamping to hardware.                                                                                                                                               |


#### StartOptions

| Prop                     | Type                           | Description                                                                                                                                                                           |
| ------------------------ | ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`orientationHz`**      | <code>number</code>            | Preferred orientation event rate in Hz. Native floors this to a hardware- supported rate. Default 30. Values above 60 waste battery without visible benefit for GPS-anchored markers. |
| **`camera`**             | <code>'rear' \| 'front'</code> | Default 'rear'.                                                                                                                                                                       |
| **`transparentWebView`** | <code>boolean</code>           | Make the WebView background transparent on start, restore on stop. Default true. Set false if the host app manages WebView transparency itself.                                       |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### OrientationEvent

| Prop            | Type                                                                | Description                                                                                                                                |
| --------------- | ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| **`heading`**   | <code>number</code>                                                 | Compass heading in degrees, 0=North, 90=East, 180=South, 270=West.                                                                         |
| **`pitch`**     | <code>number</code>                                                 | Pitch in degrees: 0=horizontal, +90=looking up, -90=looking down.                                                                          |
| **`roll`**      | <code>number</code>                                                 | Roll in degrees: 0=upright, ±180=upside down.                                                                                              |
| **`accuracy`**  | <code><a href="#orientationaccuracy">OrientationAccuracy</a></code> | Compass calibration quality. When 'low' or 'unreliable' the heading may drift; the host app should prompt the user to figure-8 the device. |
| **`timestamp`** | <code>number</code>                                                 | Monotonic timestamp in ms — useful for detecting stalled streams.                                                                          |


### Type Aliases


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>


#### CapabilityReason

<code>'no_camera' | 'no_gps' | 'gps_disabled' | 'no_accelerometer' | 'no_gyroscope' | 'no_magnetometer' | 'no_fused_orientation' | 'permission_denied' | 'web_unsupported'</code>


#### OrientationAccuracy

<code>'unreliable' | 'low' | 'medium' | 'high'</code>

</docgen-api>

## Design notes

- **Single fused stream, not raw sensors.** Apps should not fuse accel/gyro/mag in JS — the platforms' native fusion (Android `TYPE_ROTATION_VECTOR`, iOS `CMDeviceMotion`) is more accurate and more battery-efficient than re-implementing a complementary filter in TypeScript.
- **Heading reference frame.** On iOS the plugin requests `xTrueNorthZVertical` when available so heading is geographic north. On Android it is magnetic north — callers needing true north should apply declination from `GeomagneticField` themselves. The `trueHeading` capability flag tells you which frame you got.
- **FOV is display-corrected.** Both `horizontalFovDeg` and `verticalFovDeg` are always positive and already account for the screen-aspect crop and the sensor-vs-screen 90° rotation in portrait. Use them directly for screen projection — do not call `Math.abs` on them, do not swap them based on orientation.
- **Orientation is computed from the rotation matrix, not Euler angles.** Heading and pitch come from rotating the camera's body-frame look vector into the world frame. This avoids the gimbal-lock and axis-confusion bugs that bite naive `getOrientation()` / `attitude.pitch` implementations when the phone is held upright.
- **Orientation rate is clamped to 5–100 Hz** in native. The default of 30 Hz is a sweet spot for GPS-anchored overlays; values above 60 burn battery without a visible benefit since GPS itself updates at 1 Hz.
- **FOV is computed once, at `startSession`.** If the user rotates the device mid-session the camera preview will rotate (iOS) but the FOV in `StartResult` stays at the value computed for the original orientation. If you need FOV to track rotation, stop and start the session on `orientationchange`.

## Troubleshooting

**Camera preview doesn't appear** — make sure the page background is transparent while a session is active. The plugin makes the WebView itself transparent (when `transparentWebView: true`, the default) but if your `<body>` or root view has a solid background you'll see that instead of the camera. Add `body.session-active { background: transparent; }` and toggle the class around `startSession` / `stopSession`.

**Heading is jumpy or wrong** — check `e.accuracy`. If it's `'low'` or `'unreliable'`, the magnetometer needs calibration; ask the user to wave the phone in a figure-8. On Android, also check that you're testing outdoors or far from large metal objects / electronics.

**POI markers stick to the top or bottom edge** — your overlay code is probably clamping `screenY` instead of dropping the marker. When `Math.abs(pitch) > verticalFovDeg / 2`, the POI is outside the frustum vertically — return early and don't render it.

**`startSession` rejects with `permission_denied`** — Capacitor's runtime permission prompt for camera fires on the first `startSession` call. If the user denied previously, they need to re-grant in Settings; the plugin won't re-prompt. For location, trigger the prompt explicitly with `@capacitor/geolocation` before calling `startSession`.

**Web build fails or runs but does nothing** — by design. `Geoscan` has a web shim that returns `{ ready: false, reason: 'web_unsupported' }` from `checkCapabilities()` and rejects from `startSession()`. Gate your AR routes behind a capability check.

## Example app

The [`example-app/`](example-app/) folder is a self-contained Vite + vanilla-JS smoke test that uses every public method and renders a real overlay (compass markers + horizon line). It's the shortest path from "I installed the plugin" to "I see something working on a device."

A fuller consumer — Ionic Angular with GPS, POI math, and a service-based architecture — lives at [`../demo/`](../demo/) in this monorepo.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md).
