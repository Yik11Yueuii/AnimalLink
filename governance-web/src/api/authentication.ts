import axios from 'axios'
import { gatewayClient } from './client'

export interface AdminSession {
  sessionId: string
  userId: string
  systemRole: 'GOVERNANCE_ADMIN'
  idleExpiresAt: string
  absoluteExpiresAt: string
}

interface CsrfMaterial {
  token: string
  headerName: string
}

export type AuthenticationFailure =
  | 'unauthenticated'
  | 'forbidden'
  | 'conflict'
  | 'rate-limited'
  | 'unavailable'
  | 'invalid-response'

export class AuthenticationApiError extends Error {
  constructor(
    public readonly kind: AuthenticationFailure,
    public readonly status?: number,
  ) {
    super(kind)
    this.name = 'AuthenticationApiError'
  }
}

let csrfMaterial: CsrfMaterial | undefined
let csrfRequest: Promise<CsrfMaterial> | undefined

const validCsrfMaterial = (value: unknown): value is CsrfMaterial => {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<CsrfMaterial>
  return typeof candidate.token === 'string'
    && candidate.token.length >= 16
    && candidate.token.length <= 512
    && typeof candidate.headerName === 'string'
    && /^[A-Za-z0-9-]{1,64}$/.test(candidate.headerName)
}

const validSession = (value: unknown): value is AdminSession => {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<AdminSession>
  return typeof candidate.sessionId === 'string'
    && typeof candidate.userId === 'string'
    && candidate.systemRole === 'GOVERNANCE_ADMIN'
    && typeof candidate.idleExpiresAt === 'string'
    && typeof candidate.absoluteExpiresAt === 'string'
}

const mapFailure = (error: unknown): AuthenticationApiError => {
  if (error instanceof AuthenticationApiError) return error
  if (!axios.isAxiosError(error)) return new AuthenticationApiError('unavailable')
  if (error.code === 'ECONNABORTED' || !error.response) {
    return new AuthenticationApiError('unavailable')
  }
  const status = error.response.status
  if (status === 401) return new AuthenticationApiError('unauthenticated', status)
  if (status === 403) return new AuthenticationApiError('forbidden', status)
  if (status === 409) return new AuthenticationApiError('conflict', status)
  if (status === 429) return new AuthenticationApiError('rate-limited', status)
  if (status === 503) return new AuthenticationApiError('unavailable', status)
  return new AuthenticationApiError('unavailable', status)
}

const requestCsrf = async (force = false): Promise<CsrfMaterial> => {
  if (!force && csrfMaterial) return csrfMaterial
  if (!force && csrfRequest) return csrfRequest
  const current = gatewayClient.get('/auth/admin/csrf')
    .then(({ data }) => {
      if (!validCsrfMaterial(data)) throw new AuthenticationApiError('invalid-response')
      csrfMaterial = { token: data.token, headerName: data.headerName }
      return csrfMaterial
    })
    .catch((error) => {
      csrfMaterial = undefined
      throw mapFailure(error)
    })
    .finally(() => {
      if (csrfRequest === current) csrfRequest = undefined
    })
  csrfRequest = current
  return current
}

const authenticatedMutation = async <T>(path: string, body?: unknown): Promise<T> => {
  const csrf = await requestCsrf()
  try {
    const { data } = await gatewayClient.post<T>(path, body, {
      headers: { [csrf.headerName]: csrf.token },
    })
    return data
  } catch (error) {
    const mapped = mapFailure(error)
    if (mapped.kind === 'forbidden') csrfMaterial = undefined
    throw mapped
  }
}

export const prepareAdminAuthentication = (): Promise<void> => requestCsrf().then(() => undefined)

export const loginAdmin = async (username: string, password: string): Promise<AdminSession> => {
  const data = await authenticatedMutation<unknown>('/auth/admin/login', { username, password })
  if (!validSession(data)) throw new AuthenticationApiError('invalid-response')
  return data
}

export const restoreAdminSession = async (): Promise<AdminSession> => {
  try {
    const { data } = await gatewayClient.get<unknown>('/auth/admin/session')
    if (!validSession(data)) throw new AuthenticationApiError('invalid-response')
    return data
  } catch (error) {
    throw mapFailure(error)
  }
}

export const logoutAdmin = async (): Promise<void> => {
  await authenticatedMutation<void>('/auth/admin/logout')
}

/**
 * Sends a same-origin, CSRF-protected administrator mutation. The caller must use
 * an exact Gateway path; this helper never reads or exposes the HttpOnly session cookie.
 */
export const authenticatedAdminMutation = async <T>(path: string, body: unknown): Promise<T> =>
  authenticatedMutation<T>(path, body)

export const authenticationMessage = (error: unknown): string => {
  const failure = error instanceof AuthenticationApiError ? error.kind : 'unavailable'
  if (failure === 'unauthenticated') return '用户名或密码错误，或当前会话已经失效。'
  if (failure === 'forbidden') return '认证请求被安全策略拒绝，请检查访问地址和权限。'
  if (failure === 'conflict') return '数据已被其他治理操作更新，请重新读取后再试。'
  if (failure === 'rate-limited') return '登录尝试过于频繁，请稍后再试。'
  if (failure === 'invalid-response') return '认证服务返回了无法验证的响应。'
  return '认证服务暂时不可用，请稍后重试。'
}

export const resetAuthenticationApiForTests = (): void => {
  csrfMaterial = undefined
  csrfRequest = undefined
}
