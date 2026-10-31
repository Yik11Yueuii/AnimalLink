import { ApiRequestError, buildGatewayUrl } from './apiClient'
import { runtimeConfig } from '../config/runtime'

const SESSION_KEY = 'animallink.miniapp.user-session'
const SESSION_HEADER = 'X-Miniapp-Session'

export interface CurrentMiniappUser {
  userId: string
  displayName: string
  systemRole: 'USER'
  idleExpiresAt: string
  absoluteExpiresAt: string
}

interface LoginResponse extends CurrentMiniappUser { token: string }

const apiAvailable = () => runtimeConfig.userAuthenticationMode === 'api' && !!runtimeConfig.gatewayBaseUrl.trim()
const request = <T>(path: string, method: 'GET' | 'POST', data?: Record<string, string>, token?: string) => new Promise<T>((resolve, reject) => {
  if (!apiAvailable()) { reject(new ApiRequestError('当前为离线演示模式，未启用真实微信登录。', null)); return }
  wx.request({ url: buildGatewayUrl(runtimeConfig.gatewayBaseUrl, path), method, data, timeout: 8000,
    header: { Accept: 'application/json', 'Content-Type': 'application/json', ...(token ? { [SESSION_HEADER]: token } : {}) },
    success(response) {
      if (response.statusCode >= 200 && response.statusCode < 300) { resolve(response.data as T); return }
      reject(new ApiRequestError(`身份服务返回 ${response.statusCode}。`, response.statusCode))
    }, fail(error) { reject(new ApiRequestError(error.errMsg || '网络请求失败。', null)) },
  })
})

export const storedMiniappSession = () => wx.getStorageSync(SESSION_KEY) as string | undefined
export const clearMiniappSession = () => wx.removeStorageSync(SESSION_KEY)

export const loginWithWechat = async (): Promise<CurrentMiniappUser> => {
  if (!apiAvailable()) throw new ApiRequestError('当前为离线演示模式，真实微信登录尚未配置。', null)
  const login = await new Promise<WechatMiniprogram.LoginSuccessCallbackResult>((resolve, reject) => wx.login({ success: resolve, fail: reject }))
  if (!login.code) throw new ApiRequestError('微信未返回可用登录凭据。', null)
  const response = await request<LoginResponse>('/auth/wechat/login', 'POST', { code: login.code })
  if (!response.token || !/^[A-Za-z0-9_-]{43}$/.test(response.token)) throw new ApiRequestError('身份服务响应无效。', null)
  wx.setStorageSync(SESSION_KEY, response.token)
  return { userId: response.userId, displayName: response.displayName, systemRole: response.systemRole, idleExpiresAt: response.idleExpiresAt, absoluteExpiresAt: response.absoluteExpiresAt }
}

export const currentMiniappUser = async (): Promise<CurrentMiniappUser | null> => {
  const token = storedMiniappSession()
  if (!token || !apiAvailable()) return null
  try { return await request<CurrentMiniappUser>('/auth/wechat/me', 'GET', undefined, token) }
  catch (error) { if (error instanceof ApiRequestError && error.statusCode === 401) clearMiniappSession(); throw error }
}

export const logoutMiniappUser = async (): Promise<void> => {
  const token = storedMiniappSession()
  try { if (token && apiAvailable()) await request('/auth/wechat/logout', 'POST', {}, token) }
  finally { clearMiniappSession() }
}
