# Integrating capacitor-geoar from git

How to add the `capacitor-geoar` Capacitor plugin to a host app **directly from git** (no npm registry publish required). Use this when you want to consume the plugin straight from `https://github.com/gelinger777/capacitor-geoar`.

For a full API reference, mental model, troubleshooting, and design notes see [`README.md`](README.md). This file is the integration cheat-sheet only.

---

## 1. Install from git

The plugin's `package.json` runs `npm run build` in a `prepare` script, so installing from git triggers a fresh build of `dist/` on the consumer's machine. No manual build step needed.

**Pinned to a branch (auto-updates on next install):**

```bash
npm install gelinger777/capacitor-geoar#main
```

**Pinned to a tag (recommended for reproducible builds):**

```bash
npm install gelinger777/capacitor-geoar#v0.0.1
```

**Pinned to a specific commit (most reproducible):**

```bash
npm install gelinger777/capacitor-geoar#<full-or-short-sha>
```

**Equivalent in `package.json`:**

```json
{
  "dependencies": {
    "capacitor-geoar": "github:gelinger777/capacitor-geoar#main",
    "@capacitor/core": "^8.0.0"
  }
}
```

The full URL form `git+https://github.com/gelinger777/capacitor-geoar.git#<ref>` works too.

After install, run the standard Capacitor sync:

```bash
npx cap sync
```

That copies the native sources into `ios/App/Pods/` (via SPM/CocoaPods) and `android/app/`.

---

## 2. Native config — required

Capacitor does not auto-add platform permissions. Add these to the host app:

**iOS — `ios/App/App/Info.plist`:**

```xml
<key>NSCameraUsageDescription</key>
<string>Used to show the camera behind the AR overlay.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>Used to compute the bearing of nearby points of interest.</string>
```

Minimum iOS deployment target: **14.0**.

**Android — `android/app/src/main/AndroidManifest.xml`:**

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<uses-feature android:name="android.hardware.camera"               android:required="true" />
<uses-feature android:name="android.hardware.sensor.gyroscope"     android:required="true" />
<uses-feature android:name="android.hardware.sensor.accelerometer" android:required="true" />
<uses-feature android:name="android.hardware.sensor.compass"       android:required="true" />
```

Minimum Android API level: **24**.

For the location runtime prompt, install `@capacitor/geolocation` and call it once before `startSession` — the plugin handles the camera prompt itself.

---

## 3. Minimum usage

The npm package is `capacitor-geoar` but the JS symbol is **`Geoscan`**:

```ts
import { Geoscan } from 'capacitor-geoar';

const caps = await Geoscan.checkCapabilities();
if (!caps.ready) {
  console.warn('AR cannot start:', caps.reason);
  return;
}

const { horizontalFovDeg, verticalFovDeg, orientationHz } =
  await Geoscan.startSession({ orientationHz: 30, camera: 'rear' });

const handle = await Geoscan.addListener('orientation', (e) => {
  // e.heading (0=N, 90=E), e.pitch (+up/-down), e.roll, e.accuracy, e.timestamp
});

// teardown:
await handle.remove();
await Geoscan.stopSession();
```

While a session is active, the page must be transparent so the camera shines through. The plugin makes the WebView itself transparent by default; the app also needs:

```css
body.session-active { background: transparent; }
```

…and toggle the class around `startSession` / `stopSession`.

---

## 4. Project a world bearing onto the screen

Almost every overlay needs this. The plugin gives you the two display-corrected FOVs; you do the trig:

```ts
function projectBearing(
  bearing: number,           // POI bearing from user, 0..360
  heading: number,           // current device heading, 0..360
  pitch: number,             // current device pitch (-90..90)
  fov: { horizontalDeg: number; verticalDeg: number },
): { xPercent: number; yPercent: number } | null {
  const halfH = fov.horizontalDeg / 2;
  const halfV = fov.verticalDeg / 2;

  // Wrap into [-180, 180] so a bearing just east of north doesn't read as +358°
  // when heading is just west of north.
  const delta = ((bearing - heading + 540) % 360) - 180;

  if (Math.abs(delta) > halfH) return null;
  if (Math.abs(pitch) > halfV) return null;

  return {
    xPercent: ((delta + halfH) / fov.horizontalDeg) * 100,
    yPercent: 50 + (pitch / fov.verticalDeg) * 100,
  };
}
```

`bearing` for a real POI is the great-circle bearing from user GPS to POI — see [`example-app/src/js/example.js`](example-app/src/js/example.js) for a full working overlay (compass markers + horizon line) that uses exactly this function.

---

## 5. Updating to a new commit / tag

`npm install` against the same git ref **does not** re-fetch the latest commit — npm caches by ref. To pull a newer commit on a moving branch:

```bash
npm uninstall capacitor-geoar
npm install gelinger777/capacitor-geoar#main
npx cap sync
```

For tagged releases, bump the `#vX.Y.Z` suffix in `package.json` and run `npm install && npx cap sync`. Pinning to tags is strongly preferred over `#main` for production apps.

---

## 6. Contract reminders — easy to get wrong

- **`horizontalFovDeg` and `verticalFovDeg` are always positive and already display-corrected.** Do NOT call `Math.abs` on them. Do NOT swap them based on portrait/landscape. The plugin already accounts for sensor-vs-screen rotation.
- **FOV is computed once at `startSession`.** If the user rotates the device mid-session, the camera preview will rotate (iOS) but the FOV value in `StartResult` stays at the original orientation. If you need it to track rotation, stop and restart the session in `orientationchange`.
- **`heading` is true north on iOS** when `CLLocationManager.headingAvailable()` is true (see `caps.trueHeading`). On Android it's **magnetic north** — apply declination via Android's `GeomagneticField` if you need true north.
- **When a POI's pitch falls outside `±verticalFovDeg/2`**, drop it. Do not clamp `screenY` — clamped-and-stuck markers at the screen edges are the classic visual signature of pitch math gone wrong.
- **Watch `e.accuracy`.** On `'low'` or `'unreliable'`, the magnetometer needs calibration — prompt the user to wave the phone in a figure-8.
- **No raw accel/gyro/mag events.** The plugin only emits the fused `'orientation'` stream. Do not try to fuse sensors yourself in JS — the native `TYPE_ROTATION_VECTOR` (Android) and `CMDeviceMotion` (iOS) fusion is more accurate and battery-efficient.
- **No web fallback.** `Geoscan.checkCapabilities()` returns `{ ready: false, reason: 'web_unsupported' }` and `startSession()` rejects on web. Gate your AR routes behind a capability check or a platform guard.

---

## 7. Verify the install worked

After `npm install` + `npx cap sync`, smoke-test on a device:

```ts
import { Geoscan } from 'capacitor-geoar';

const caps = await Geoscan.checkCapabilities();
console.log(caps);
// Expect: ready: true on a phone with permissions; ready: false + a 'reason' otherwise.
```

If `ready: false` with `reason: 'no_fused_orientation'` on Android, the device lacks `TYPE_ROTATION_VECTOR` (rare on modern hardware). If `reason: 'permission_denied'`, the user denied camera or location.
