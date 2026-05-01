import { WebPlugin } from '@capacitor/core';

import type { GeoscanPlugin } from './definitions';

export class GeoscanWeb extends WebPlugin implements GeoscanPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
