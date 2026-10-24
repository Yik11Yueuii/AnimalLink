export type DataMode = 'api' | 'offline-demo'

export interface RuntimeConfig {
  animalDataMode: DataMode
  communityDataMode: DataMode
  gatewayBaseUrl: string
}

// Modes are feature-scoped so enabling the reviewed Animal API never sends the
// still-protected Campus Circle requests to Gateway by accident.
export const runtimeConfig: RuntimeConfig = {
  animalDataMode: 'offline-demo',
  communityDataMode: 'offline-demo',
  gatewayBaseUrl: '',
}
