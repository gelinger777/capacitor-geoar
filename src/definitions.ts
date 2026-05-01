export interface GeoscanPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
