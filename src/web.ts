import { WebPlugin } from '@capacitor/core';

import type {
  CapabilitiesResult,
  GeoscanPlugin,
  StartOptions,
  StartResult,
} from './definitions';

export class GeoscanWeb extends WebPlugin implements GeoscanPlugin {
  async checkCapabilities(): Promise<CapabilitiesResult> {
    return {
      ready: false,
      camera: { hardware: false, permission: 'denied' },
      location: { hardware: false, serviceEnabled: false, permission: 'denied' },
      accelerometer: false,
      gyroscope: false,
      magnetometer: false,
      fusedOrientation: false,
      trueHeading: false,
      reason: 'web_unsupported',
    };
  }

  async startSession(_options?: StartOptions): Promise<StartResult> {
    throw this.unavailable('Geoscan AR sessions are not supported on the web platform.');
  }

  async stopSession(): Promise<void> {
    return;
  }

  async pauseSession(): Promise<void> {
    return;
  }
}
