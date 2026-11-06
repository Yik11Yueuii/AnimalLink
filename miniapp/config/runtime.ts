export type DataMode = 'api' | 'offline-demo'
export type UserAuthenticationMode = 'api' | 'offline-demo'

export const LOCAL_RUNTIME_OVERRIDE_STORAGE_KEY = 'animallink.runtime.public-data.v1'

export interface LocalDevelopmentRuntimeOverride {
  publicDataMode: DataMode
  gatewayBaseUrl: string
  userAuthenticationMode?: UserAuthenticationMode
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

interface GatewayOrigin { baseUrl: string; secure: boolean; loopback: boolean }

// An override is an origin, never a URL with credentials, path, query or fragment.
// Some Mini Program runtimes lack URL; the fallback accepts only this small origin grammar.
const parseGatewayOrigin = (value: string): GatewayOrigin | null => {
  const input = value.trim()
  if (!input || /[\s\\@?#]/.test(input)) return null
  const authority = /^(https?):\/\/(\[[^\]]+\]|[^:/?#]+)(?::([0-9]{1,5}))?\/?$/i.exec(input)
  if (!authority) return null
  const port = authority[3] === undefined ? 1 : Number(authority[3])
  if (port < 1 || port > 65535) return null
  const rawHost = authority[2].toLowerCase()
  const loopback = rawHost === 'localhost' || rawHost === '127.0.0.1' || rawHost === '[::1]'
  const URLConstructor = (globalThis as unknown as { URL?: new (url: string) => {
    protocol: string; hostname: string; username: string; password: string;
    pathname: string; search: string; hash: string; origin: string
  } }).URL
  if (URLConstructor) {
    try {
      const url = new URLConstructor(input)
      if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.username || url.password
        || url.pathname !== '/' || url.search || url.hash || url.origin === 'null') return null
      const host = url.hostname.toLowerCase()
      if (host.split('.').some(label => label.startsWith('-') || label.endsWith('-'))) return null
      return { baseUrl: url.origin, secure: url.protocol === 'https:', loopback }
    } catch { return null }
  }
  const host = authority[2].toLowerCase()
  if (!/^(\[::1\]|[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*)$/.test(host)
    || host.split('.').some(label => label.startsWith('-') || label.endsWith('-'))) return null
  return { baseUrl: input.replace(/\/$/, ''), secure: authority[1].toLowerCase() === 'https', loopback }
}

export const isSafeAuthenticationGatewayUrl = (value: string): boolean => {
  const origin = parseGatewayOrigin(value)
  return !!origin && (origin.secure || origin.loopback)
}

const isLocalDevelopmentOverride = (value: unknown): value is LocalDevelopmentRuntimeOverride => {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<LocalDevelopmentRuntimeOverride>
  if (candidate.publicDataMode !== 'api' && candidate.publicDataMode !== 'offline-demo') return false
  if (typeof candidate.gatewayBaseUrl !== 'string') return false
  if (candidate.userAuthenticationMode !== undefined
    && candidate.userAuthenticationMode !== 'api'
    && candidate.userAuthenticationMode !== 'offline-demo') return false
  if (candidate.publicDataMode === 'offline-demo') {
    return candidate.gatewayBaseUrl.trim() === '' && candidate.userAuthenticationMode !== 'api'
  }
  return parseGatewayOrigin(candidate.gatewayBaseUrl) !== null
}

export const resolveRuntimeConfig = (
  environment: 'develop' | 'trial' | 'release' | 'unknown',
  localOverride: unknown,
): RuntimeConfig => {
  // Local storage is deliberately ignored outside the WeChat develop build.
  if (environment !== 'develop' || !isLocalDevelopmentOverride(localOverride)) return { ...offlineConfig }
  if (localOverride.publicDataMode === 'offline-demo') return { ...offlineConfig }
  const origin = parseGatewayOrigin(localOverride.gatewayBaseUrl)
  if (!origin) return { ...offlineConfig }
  return {
    publicDataMode: 'api',
    animalDataMode: 'api',
    communityDataMode: 'api',
    rescueDataMode: 'api',
    userAuthenticationMode: localOverride.userAuthenticationMode === 'api' && (origin.secure || origin.loopback)
      ? 'api' : 'offline-demo',
    gatewayBaseUrl: origin.baseUrl,
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
