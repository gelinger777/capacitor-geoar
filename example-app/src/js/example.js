// capacitor-geoar example — an end-to-end smoke test that doubles as a tutorial.
//
// What this shows:
//   1. Probe capabilities before starting (so the UI can explain what's missing).
//   2. Start a session and read the display-corrected FOV from the result.
//   3. Subscribe to the fused 'orientation' stream.
//   4. Project compass bearings (N/E/S/W) onto screen X using horizontalFovDeg.
//   5. Project pitch onto screen Y using verticalFovDeg (the horizon line).
//   6. Tear down cleanly on stop.
//
// No GPS is used here on purpose — the four cardinal markers are just fixed
// bearings (0°, 90°, 180°, 270°) so the example stays self-contained. To
// project a real-world POI, replace the bearing constants with
// `bearingDeg(userPosition, poi)` from a geo-math helper of your own.

import { Geoscan } from 'capacitor-geoar';

const els = {
  body: document.body,
  heading: document.getElementById('heading'),
  pitch: document.getElementById('pitch'),
  roll: document.getElementById('roll'),
  accuracy: document.getElementById('accuracy'),
  horizon: document.getElementById('horizon'),
  log: document.getElementById('log'),
  btnStart: document.getElementById('btn-start'),
  btnStop: document.getElementById('btn-stop'),
  markers: {
    0: document.getElementById('marker-N'),
    90: document.getElementById('marker-E'),
    180: document.getElementById('marker-S'),
    270: document.getElementById('marker-W'),
  },
};

let orientationHandle = null;
let fov = null; // { horizontalDeg, verticalDeg } once a session is running.

function log(msg, kind = '') {
  const line = document.createElement('div');
  line.className = `line ${kind}`;
  line.textContent = msg;
  els.log.prepend(line);
}

els.btnStart.addEventListener('click', start);
els.btnStop.addEventListener('click', stop);

async function start() {
  els.btnStart.disabled = true;

  try {
    const caps = await Geoscan.checkCapabilities();
    log(`capabilities: ready=${caps.ready}${caps.reason ? ` reason=${caps.reason}` : ''}`,
        caps.ready ? 'ok' : 'err');
    if (!caps.ready) {
      log(`camera.permission=${caps.camera.permission}, location.permission=${caps.location.permission}`);
      els.btnStart.disabled = false;
      return;
    }

    const result = await Geoscan.startSession({
      orientationHz: 30,
      camera: 'rear',
      transparentWebView: true,
    });
    fov = { horizontalDeg: result.horizontalFovDeg, verticalDeg: result.verticalFovDeg };
    log(`session started: H-FOV=${fov.horizontalDeg.toFixed(1)}° V-FOV=${fov.verticalDeg.toFixed(1)}° @ ${result.orientationHz}Hz`, 'ok');

    orientationHandle = await Geoscan.addListener('orientation', onOrientation);

    els.body.classList.add('session-active');
    els.btnStop.disabled = false;
  } catch (err) {
    log(`start failed: ${err?.message || err}`, 'err');
    els.btnStart.disabled = false;
  }
}

async function stop() {
  els.btnStop.disabled = true;
  try {
    if (orientationHandle) {
      await orientationHandle.remove();
      orientationHandle = null;
    }
    await Geoscan.stopSession();
    fov = null;
    els.body.classList.remove('session-active');
    els.btnStart.disabled = false;
    log('session stopped', 'ok');
  } catch (err) {
    log(`stop failed: ${err?.message || err}`, 'err');
    els.btnStop.disabled = false;
  }
}

function onOrientation(e) {
  els.heading.textContent = `${e.heading.toFixed(1)}°`;
  els.pitch.textContent = `${e.pitch.toFixed(1)}°`;
  els.roll.textContent = `${e.roll.toFixed(1)}°`;
  els.accuracy.textContent = e.accuracy;

  if (!fov) return;

  // ---- Horizon line: project pitch onto screen Y ------------------------------
  // The plugin's verticalFovDeg is the angle across the screen's HEIGHT, so a
  // vertical pixel offset of `(pitch / verticalFovDeg) * screenHeight` maps the
  // horizon (where pitch=0 lands) relative to the screen center.
  const halfV = fov.verticalDeg / 2;
  if (Math.abs(e.pitch) <= halfV) {
    const yPercent = 50 + (e.pitch / fov.verticalDeg) * 100;
    els.horizon.style.transform = `translateY(${(yPercent - 50) * window.innerHeight / 100}px)`;
    els.horizon.style.top = '50%';
    els.horizon.style.opacity = '1';
  } else {
    els.horizon.style.opacity = '0';
  }

  // ---- Cardinal markers: project bearing onto screen X + pitch onto screen Y --
  for (const bearingStr of Object.keys(els.markers)) {
    const bearing = Number(bearingStr);
    placeMarker(els.markers[bearing], bearing, e.heading, e.pitch);
  }
}

/**
 * Project a world-space bearing onto the screen, using the plugin's display-
 * corrected FOV. Hides the marker when it falls outside the visible frustum.
 *
 * The horizontal-axis math is what every overlay needs. The vertical-axis math
 * here assumes the marker is at the user's eye level (no elevation data) — for
 * GPS POIs that's a reasonable default; for elevated targets you'd add an
 * `elevationAngle` term to `e.pitch`.
 */
function placeMarker(el, bearing, heading, pitch) {
  const halfH = fov.horizontalDeg / 2;
  const halfV = fov.verticalDeg / 2;

  // Wrap the angular delta into [-180, 180] so a bearing just east of north
  // doesn't appear as a +358° offset when heading is just west of north.
  let delta = ((bearing - heading + 540) % 360) - 180;

  if (Math.abs(delta) > halfH || Math.abs(pitch) > halfV) {
    el.style.display = 'none';
    return;
  }

  const xPercent = ((delta + halfH) / fov.horizontalDeg) * 100;
  const yPercent = 50 + (pitch / fov.verticalDeg) * 100;
  el.style.display = 'block';
  el.style.left = `${xPercent}%`;
  el.style.top = `${Math.max(8, Math.min(92, yPercent))}%`;
}

// Friendly hint when the user opens index.html in a desktop browser.
log('press "Start AR session" — on iOS/Android the camera will appear behind this UI.');
