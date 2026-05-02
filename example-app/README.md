# capacitor-geoar example app

A minimal Vite + vanilla-JS app that exercises every public method on the plugin and renders a working AR overlay (compass markers + horizon line) on top of the camera preview.

It's deliberately not bound to a framework — read [`src/js/example.js`](src/js/example.js) and you have the full pattern that any Ionic / React / Vue / vanilla consumer would follow.

## What it does

- Calls `Geoscan.checkCapabilities()` and shows what's missing if the session can't start.
- Calls `Geoscan.startSession()` — camera preview is placed behind the (transparent) WebView.
- Subscribes to the `'orientation'` stream and live-updates a heading / pitch / roll / accuracy readout.
- Projects the four cardinal compass directions (N / E / S / W) onto the screen using the display-corrected `horizontalFovDeg` returned by `startSession`.
- Draws a horizon line driven by `pitch` and `verticalFovDeg`.
- Cleans up on **Stop** (`removeAllListeners` + `stopSession`).

The cardinal markers are fixed bearings (0°, 90°, 180°, 270°) — no GPS is used. To project a real-world POI, replace the bearings with `bearingDeg(userPosition, poi)` from your own helper.

## Run on a device

The plugin only works on iOS or Android — there's no web fallback.

```bash
# from this folder (capacitor-geoar/example-app/)
npm install
npm run build
npx cap sync

# then open the platform project and run on a real device:
npx cap open ios
npx cap open android
```

The plugin is wired in via `"capacitor-geoar": "file:.."` so changes you make to the parent plugin are picked up after a `npm run build && npx cap sync` from the parent.

## Permissions

The example app's native projects already declare camera + location permissions. If you start from this template, make sure your own host app does too — see the **Native config** section in the [parent README](../README.md).
