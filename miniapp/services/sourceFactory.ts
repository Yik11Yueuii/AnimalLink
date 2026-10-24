import { runtimeConfig } from '../config/runtime'
import { createJsonRequester } from './apiClient'
import { ApiPublicAnimalDataSource, type PublicAnimalDataSource } from './dataSource'
import { OfflinePublicAnimalDataSource } from './offlineDataSource'

let source: PublicAnimalDataSource | null = null

export const getPublicAnimalDataSource = (): PublicAnimalDataSource => {
  if (source) return source
  source = runtimeConfig.dataMode === 'offline-demo'
    ? new OfflinePublicAnimalDataSource()
    : new ApiPublicAnimalDataSource(createJsonRequester(runtimeConfig.gatewayBaseUrl))
  return source
}

export const isOfflineDemo = () => runtimeConfig.dataMode === 'offline-demo'
