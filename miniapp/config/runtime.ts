export type DataMode = 'api' | 'offline-demo'
export type UserAuthenticationMode = 'api' | 'offline-demo'

export const LOCAL_RUNTIME_OVERRIDE_STORAGE_KEY = 'animallink.runtime.public-data.v1'

export interface LocalDevelopmentRuntimeOverride {
  publicDataMode: DataMode
  gatewayBaseUrl: string
}

export interface RuntimeConfig {
  publicDataMode: DataMode
  animalDataMode: DataMode
  communityDataMode: DataMode
  rescueDataMode: DataMode
  userAuthenticationMode: UserAuthenticationMode
  gatewayBaseUrl: string
}

const offlineConfig: RuntimeConfig = {
  publicDataMode: 'offline-demo',
  animalDataMode: 'offline-demo',
  communityDataMode: 'offline-demo',
  rescueDataMode: 'offline-demo',
  userAuthenticationMode: 'offline-demo',
  gatewayBaseUrl: '',
}

const isLocalDevelopmentOverride = (value: unknown): value is LocalDevelopmentRuntimeOverride => {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<LocalDevelopmentRuntimeOverride>
  if (candidate.publicDataMode !== 'api' && candidate.publicDataMode !== 'offline-demo') return false
  if (typeof candidate.gatewayBaseUrl !== 'string') return false
  if (candidate.publicDataMode === 'offline-demo') return candidate.gatewayBaseUrl.trim() === ''
  const baseUrl = candidate.gatewayBaseUrl.trim().replace(/\/+$/, '')
  return /^https?:\/\/[^\s\/@?#]+(?::\d{1,5})?$/i.test(baseUrl)
}

export const resolveRuntimeConfig = (
  environment: 'develop' | 'trial' | 'release' | 'unknown',
  localOverride: unknown,
): RuntimeConfig => {
  // Local storage is deliberately ignored outside the WeChat develop build.
  if (environment !== 'develop' || !isLocalDevelopmentOverride(localOverride)) return { ...offlineConfig }
  if (localOverride.publicDataMode === 'offline-demo') return { ...offlineConfig }
  const gatewayBaseUrl = localOverride.gatewayBaseUrl.trim().replace(/\/+$/, '')
  return {
    publicDataMode: 'api',
    animalDataMode: 'api',
    communityDataMode: 'api',
    rescueDataMode: 'api',
    userAuthenticationMode: 'offline-demo',
    gatewayBaseUrl,
  }
}

const environment = (): 'develop' | 'trial' | 'release' | 'unknown' => {
  try {
    if (typeof wx === 'undefined' || typeof wx.getAccountInfoSync !== 'function') return 'unknown'
    const value = wx.getAccountInfoSync().miniProgram.envVersion
    return value === 'develop' || value === 'trial' || value === 'release' ? value : 'unknown'
  } catch {
    return 'unknown'
  }
}

const localOverride = (): unknown => {
  if (environment() !== 'develop') return undefined
  try {
    return wx.getStorageSync<unknown>(LOCAL_RUNTIME_OVERRIDE_STORAGE_KEY)
  } catch {
    return undefined
  }
}

// Evaluated once before any DataSource singleton is created. Changing the local
// override requires a mini-program restart/recompile by design.
export const runtimeConfig: RuntimeConfig = resolveRuntimeConfig(environment(), localOverride())
