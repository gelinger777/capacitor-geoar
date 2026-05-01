import { registerPlugin } from '@capacitor/core';

import type { GeoscanPlugin } from './definitions';

const Geoscan = registerPlugin<GeoscanPlugin>('Geoscan', {
  web: () => import('./web').then((m) => new m.GeoscanWeb()),
});

export * from './definitions';
export { Geoscan };
