import { runtimeConfig } from '../config/runtime'
import { createJsonRequester } from './apiClient'
import { ApiPublicAnimalDataSource, type PublicAnimalDataSource } from './dataSource'
import { OfflinePublicAnimalDataSource } from './offlineDataSource'
import { ApiCommunityDataSource, type CommunityDataSource } from './communityDataSource'
import { OfflineCommunityDataSource } from './offlineCommunityDataSource'

let source: PublicAnimalDataSource | null = null
let communitySource: CommunityDataSource | null = null

export const getPublicAnimalDataSource = (): PublicAnimalDataSource => {
  if (source) return source
  source = runtimeConfig.animalDataMode === 'offline-demo'
    ? new OfflinePublicAnimalDataSource()
    : new ApiPublicAnimalDataSource(createJsonRequester(runtimeConfig.gatewayBaseUrl))
  return source
}

export const getCommunityDataSource = (): CommunityDataSource => {
  if (communitySource) return communitySource
  communitySource = runtimeConfig.communityDataMode === 'offline-demo'
    ? new OfflineCommunityDataSource()
    : new ApiCommunityDataSource(createJsonRequester(runtimeConfig.gatewayBaseUrl))
  return communitySource
}

export const isAnimalOfflineDemo = () => runtimeConfig.animalDataMode === 'offline-demo'

export const isCommunityOfflineDemo = () => runtimeConfig.communityDataMode === 'offline-demo'
