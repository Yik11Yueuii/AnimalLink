export type DataMode = 'api' | 'offline-demo'

export interface RuntimeConfig {
  dataMode: DataMode
  gatewayBaseUrl: string
}

// Offline demo is explicit and visibly labelled in every data-backed page.
// For real local reads, set dataMode to 'api' and configure the local Gateway URL.
export const runtimeConfig: RuntimeConfig = {
  dataMode: 'offline-demo',
  gatewayBaseUrl: '',
}
