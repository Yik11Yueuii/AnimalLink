import { runtimeConfig } from '../config/runtime'
import { createJsonRequester } from './apiClient'
import { ApiPublicAnimalDataSource, type PublicAnimalDataSource } from './dataSource'
import { OfflinePublicAnimalDataSource } from './offlineDataSource'
import { ApiCommunityDataSource, type CommunityDataSource } from './communityDataSource'
import { OfflineCommunityDataSource } from './offlineCommunityDataSource'
import { ApiRescueDataSource, type RescueDataSource } from './rescueDataSource'
import { OfflineRescueDataSource } from './offlineRescueDataSource'

export interface PublicDataSources {
  animal: PublicAnimalDataSource
  community: CommunityDataSource
  rescue: RescueDataSource
}

export const createPublicDataSources = (
  config: typeof runtimeConfig,
  requesterFactory = createJsonRequester,
): PublicDataSources => {
  if (config.publicDataMode === 'offline-demo') {
    return {
      animal: new OfflinePublicAnimalDataSource(),
      community: new OfflineCommunityDataSource(),
      rescue: new OfflineRescueDataSource(),
    }
  }
  const request = requesterFactory(config.gatewayBaseUrl)
  return {
    animal: new ApiPublicAnimalDataSource(request),
    community: new ApiCommunityDataSource(request),
    rescue: new ApiRescueDataSource(request),
  }
}

let sources: PublicDataSources | null = null
const getSources = () => {
  if (!sources) sources = createPublicDataSources(runtimeConfig)
  return sources
}

export const getPublicAnimalDataSource = (): PublicAnimalDataSource => {
  return getSources().animal
}

export const getCommunityDataSource = (): CommunityDataSource => {
  return getSources().community
}

export const getRescueDataSource = (): RescueDataSource => {
  return getSources().rescue
}

export const isAnimalOfflineDemo = () => runtimeConfig.animalDataMode === 'offline-demo'

export const isCommunityOfflineDemo = () => runtimeConfig.communityDataMode === 'offline-demo'
export const isRescueOfflineDemo = () => runtimeConfig.rescueDataMode === 'offline-demo'
export const isPublicDataApiMode = () => runtimeConfig.publicDataMode === 'api'
