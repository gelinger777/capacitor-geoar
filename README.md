# capacitor-geoar

GeoAR done simple — a Capacitor 8 plugin that hands the WebView a transparent camera preview and a single fused device-orientation stream. Use it as the substrate for GPS-anchored AR overlays drawn in HTML/canvas/WebGL.

The plugin deliberately does not do SLAM, ARKit/ARCore tracking, 3D scene rendering, raycasting, or VR side-by-side. Those concerns belong to the host app. The plugin's job is to make the camera visible behind the WebView and to deliver a clean, calibrated orientation stream.

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
- **Android**: requires `TYPE_ROTATION_VECTOR` (fused orientation). Heading is magnetic north — apply declination in app code if you need true north.
- **Web**: not supported. `checkCapabilities()` returns `{ ready: false, reason: 'web_unsupported' }`; `startSession()` rejects.

You must declare camera and location permissions in the host app's `Info.plist` (`NSCameraUsageDescription`, `NSLocationWhenInUseUsageDescription`) and `AndroidManifest.xml` (`CAMERA`, `ACCESS_FINE_LOCATION`).

## Usage

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
  // e.heading, e.pitch, e.roll (degrees), e.accuracy, e.timestamp (ms)
});

// later:
await handle.remove();
await Geoscan.stopSession();
```

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

## Notes on the API shape

- **Single fused stream, not raw sensors.** Apps should not fuse accel/gyro/mag in JS — the platforms' native fusion (Android `TYPE_ROTATION_VECTOR`, iOS `CMDeviceMotion`) is more accurate and battery-efficient.
- **Heading reference frame.** On iOS the plugin requests `xTrueNorthZVertical` when available so heading is geographic north. On Android it is magnetic north — callers needing true north should apply declination from `GeomagneticField` themselves.
- **FOV is display-corrected.** Do not `Math.abs` it; it is always positive and already accounts for the screen-aspect crop. Use it directly for screen-X projection.
- **Orientation rate is clamped 5–100 Hz** in native. Values above ~60 are battery-expensive without visible benefit for GPS-anchored overlays.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md). The minimal Vite app under [example-app/](example-app/) is a smoke-test harness; the [Ionic Angular demo](../demo/) is a fuller consumer wiring orientation + GPS into POI math.
