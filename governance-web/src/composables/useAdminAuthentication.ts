import { computed, reactive } from 'vue'
import {
  AuthenticationApiError,
  authenticationMessage,
  loginAdmin,
  logoutAdmin,
  restoreAdminSession,
  type AdminSession,
} from '@/api/authentication'

export type AuthenticationStatus =
  | 'initializing'
  | 'unauthenticated'
  | 'authenticated'
  | 'validation-failed'
  | 'forbidden'
  | 'rate-limited'
  | 'unavailable'

const state = reactive<{
  status: AuthenticationStatus
  session: AdminSession | null
  message: string
  expired: boolean
}>({
  status: 'initializing',
  session: null,
  message: '',
  expired: false,
})

let operationGeneration = 0
let initialization: Promise<void> | undefined
let expiryTimer: number | undefined

const cancelExpiryTimer = () => {
  if (expiryTimer !== undefined && typeof window !== 'undefined') window.clearTimeout(expiryTimer)
  expiryTimer = undefined
}

const setUnauthenticated = (expired = false) => {
  cancelExpiryTimer()
  state.status = 'unauthenticated'
  state.session = null
  state.expired = expired
  state.message = expired ? '管理员会话已过期，请重新登录。' : ''
}

const scheduleExpiry = (session: AdminSession, generation: number) => {
  cancelExpiryTimer()
  if (typeof window === 'undefined') return
  const expiresAt = Math.min(Date.parse(session.idleExpiresAt), Date.parse(session.absoluteExpiresAt))
  if (!Number.isFinite(expiresAt)) {
    state.status = 'validation-failed'
    state.session = null
    state.message = '认证服务返回了无效的会话期限。'
    return
  }
  const delay = Math.max(0, Math.min(expiresAt - Date.now(), 2_147_483_647))
  expiryTimer = window.setTimeout(() => {
    if (generation !== operationGeneration) return
    operationGeneration += 1
    setUnauthenticated(true)
  }, delay)
}

const setAuthenticated = (session: AdminSession, generation: number) => {
  state.status = 'authenticated'
  state.session = session
  state.message = ''
  state.expired = false
  scheduleExpiry(session, generation)
}

const setFailure = (error: unknown) => {
  cancelExpiryTimer()
  state.session = null
  state.expired = false
  state.message = authenticationMessage(error)
  if (error instanceof AuthenticationApiError) {
    if (error.kind === 'unauthenticated') state.status = 'unauthenticated'
    else if (error.kind === 'forbidden') state.status = 'forbidden'
    else if (error.kind === 'rate-limited') state.status = 'rate-limited'
    else if (error.kind === 'invalid-response') state.status = 'validation-failed'
    else state.status = 'unavailable'
  } else {
    state.status = 'unavailable'
  }
}

const restore = (force = false): Promise<void> => {
  if (!force && initialization) return initialization
  if (!force && state.status !== 'initializing') return Promise.resolve()
  const generation = ++operationGeneration
  state.status = 'initializing'
  state.message = ''
  const current = restoreAdminSession()
    .then((session) => {
      if (generation === operationGeneration) setAuthenticated(session, generation)
    })
    .catch((error) => {
      if (generation === operationGeneration) setFailure(error)
    })
    .finally(() => {
      if (initialization === current) initialization = undefined
    })
  initialization = current
  return current
}

const login = async (username: string, password: string): Promise<void> => {
  const generation = ++operationGeneration
  try {
    const session = await loginAdmin(username, password)
    if (generation === operationGeneration) setAuthenticated(session, generation)
  } catch (error) {
    if (generation === operationGeneration) setFailure(error)
    throw error
  }
}

const logout = async (): Promise<void> => {
  const generation = ++operationGeneration
  try {
    await logoutAdmin()
    if (generation === operationGeneration) setUnauthenticated(false)
  } catch (error) {
    if (generation === operationGeneration) setFailure(error)
    throw error
  }
}

const isAuthenticated = computed(() => state.status === 'authenticated' && state.session !== null)

export const useAdminAuthentication = () => ({ state, isAuthenticated, restore, login, logout })

export const resetAdminAuthenticationForTests = () => {
  operationGeneration += 1
  initialization = undefined
  cancelExpiryTimer()
  state.status = 'initializing'
  state.session = null
  state.message = ''
  state.expired = false
}
