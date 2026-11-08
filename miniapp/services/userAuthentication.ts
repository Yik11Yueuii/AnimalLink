import { ApiRequestError, buildGatewayUrl } from './apiClient'
import { isSafeAuthenticationGatewayUrl, runtimeConfig } from '../config/runtime'

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
interface LogoutResponse { revoked: boolean }

export interface CampusIdentityCampus { id: string; name: string; shortName: string | null; city: string; status: string }
export interface CampusMembership { id: string; campus: CampusIdentityCampus; membershipType: 'STUDENT' | 'ALUMNI'; status: string; expectedGraduationDate: string | null; approvedAt: string | null }
export interface CampusVerificationApplication { id: string; campus: CampusIdentityCampus; requestedMembershipType: 'STUDENT' | 'ALUMNI'; applicantName: string; affiliationNote: string | null; expectedGraduationDate: string | null; status: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED'; reviewReason: string | null; reviewedAt: string | null; createdAt: string; updatedAt: string; credentialPrecheckStatus: string }
export interface CampusIdentitySnapshot { memberships: CampusMembership[]; applications: CampusVerificationApplication[] }
export interface IdentityCampusOption { id: string; name: string; shortName: string | null; city: string; status: string }
export interface CampusIdentityApplicationInput { campusId: string; requestedMembershipType: 'STUDENT' | 'ALUMNI'; applicantName: string; affiliationNote?: string; expectedGraduationDate?: string; materialMediaId?: string }

const apiAvailable = () => runtimeConfig.userAuthenticationMode === 'api'
  && isSafeAuthenticationGatewayUrl(runtimeConfig.gatewayBaseUrl)
const request = <T>(path: string, method: 'GET' | 'POST', data?: Record<string, string>, token?: string) => new Promise<T>((resolve, reject) => {
  if (!apiAvailable()) { reject(new ApiRequestError('当前为离线演示模式，未启用真实微信登录。', null)); return }
  wx.request({ url: buildGatewayUrl(runtimeConfig.gatewayBaseUrl, path), method, data, timeout: path === '/auth/wechat/login' ? 12000 : 8000,
    header: { Accept: 'application/json', 'Content-Type': 'application/json', ...(token ? { [SESSION_HEADER]: token } : {}) },
    success(response) {
      if (response.statusCode >= 200 && response.statusCode < 300) { resolve(response.data as T); return }
      reject(new ApiRequestError(`身份服务返回 ${response.statusCode}。`, response.statusCode))
    }, fail(error) { reject(new ApiRequestError(error.errMsg || '网络请求失败。', null)) },
  })
})

export const storedMiniappSession = () => wx.getStorageSync(SESSION_KEY) as string | undefined
export const clearMiniappSession = () => wx.removeStorageSync(SESSION_KEY)

const revokeMiniappSession = async (token: string): Promise<void> => {
  const logout = await request<LogoutResponse>('/auth/wechat/logout', 'POST', {}, token)
  if (!logout?.revoked) throw new ApiRequestError('服务端会话撤销未确认。', null)
}

const compensateFailedSessionStorage = async (token: string): Promise<never> => {
  let localCleanupConfirmed = true
  try {
    if (storedMiniappSession() === token) clearMiniappSession()
  } catch { localCleanupConfirmed = false }
  try { await revokeMiniappSession(token) }
  catch { throw new ApiRequestError('本地登录信息保存失败，服务端 Session 撤销未确认。', null) }
  if (!localCleanupConfirmed) throw new ApiRequestError('本地登录信息保存失败，新会话已撤销，但本地清理未确认。', null)
  throw new ApiRequestError('本地登录信息保存失败，新会话已撤销。', null)
}

export const loginWithWechat = async (mayStoreSession: () => boolean = () => true): Promise<CurrentMiniappUser> => {
  if (!apiAvailable()) throw new ApiRequestError('当前为离线演示模式，真实微信登录尚未配置。', null)
  if (storedMiniappSession()) throw new ApiRequestError('已有会话，请先重试验证或主动退出后再切换账号。', 409)
  const login = await new Promise<WechatMiniprogram.LoginSuccessCallbackResult>((resolve, reject) => wx.login({ success: resolve, fail: reject }))
  if (!login.code) throw new ApiRequestError('微信未返回可用登录凭据。', null)
  if (!mayStoreSession()) throw new ApiRequestError('登录请求已失效。', null)
  const response = await request<LoginResponse>('/auth/wechat/login', 'POST', { code: login.code })
  if (!response.token || !/^[A-Za-z0-9_-]{43}$/.test(response.token)) {
    throw new ApiRequestError('身份服务响应无效。', null)
  }
  if (response.systemRole !== 'USER') {
    try { await revokeMiniappSession(response.token) }
    catch { throw new ApiRequestError('身份服务响应无效，服务端 Session 撤销未确认。', null) }
    throw new ApiRequestError('身份服务响应无效，新会话已撤销。', null)
  }
  let existingSession: string | undefined
  try { existingSession = storedMiniappSession() }
  catch { return compensateFailedSessionStorage(response.token) }
  let canStoreSession = false
  try { canStoreSession = mayStoreSession() }
  catch { /* A failed cancellation check must not keep a newly issued session. */ }
  if (!canStoreSession || existingSession) {
    try { await revokeMiniappSession(response.token) }
    catch { throw new ApiRequestError('登录已取消，但新会话的服务端撤销未确认。', null) }
    throw new ApiRequestError('登录请求已失效，新会话已撤销。', null)
  }
  try {
    wx.setStorageSync(SESSION_KEY, response.token)
    if (storedMiniappSession() !== response.token) throw new Error('Session storage verification failed')
  }
  catch { return compensateFailedSessionStorage(response.token) }
  return { userId: response.userId, displayName: response.displayName, systemRole: response.systemRole, idleExpiresAt: response.idleExpiresAt, absoluteExpiresAt: response.absoluteExpiresAt }
}

export const currentMiniappUser = async (): Promise<CurrentMiniappUser | null> => {
  const token = storedMiniappSession()
  if (!token || !apiAvailable()) return null
  try {
    const user = await request<CurrentMiniappUser>('/auth/wechat/me', 'GET', undefined, token)
    if (user.systemRole !== 'USER') throw new ApiRequestError('身份服务响应无效。', null)
    return user
  }
  catch (error) { if (error instanceof ApiRequestError && error.statusCode === 401 && storedMiniappSession() === token) clearMiniappSession(); throw error }
}

export const logoutMiniappUser = async (): Promise<void> => {
  const token = storedMiniappSession()
  try {
    if (token && apiAvailable()) await revokeMiniappSession(token)
  }
  finally { if (storedMiniappSession() === token) clearMiniappSession() }
}

export const currentCampusIdentity = async (): Promise<CampusIdentitySnapshot | null> => {
  const token = storedMiniappSession()
  if (!token || !apiAvailable()) return null
  try { return await request<CampusIdentitySnapshot>('/auth/wechat/campus-identities', 'GET', undefined, token) }
  catch (error) { if (error instanceof ApiRequestError && error.statusCode === 401 && storedMiniappSession() === token) clearMiniappSession(); throw error }
}

export const listIdentityCampuses = () => request<IdentityCampusOption[]>('/identity/api/v1/campuses', 'GET')

export const submitCampusIdentity = async (input: CampusIdentityApplicationInput): Promise<CampusVerificationApplication> => {
  const token = storedMiniappSession()
  if (!token) throw new ApiRequestError('请先完成微信登录。', 401)
  const data: Record<string, string> = {
    campusId: input.campusId, requestedMembershipType: input.requestedMembershipType, applicantName: input.applicantName,
  }
  if (input.affiliationNote) data.affiliationNote = input.affiliationNote
  if (input.expectedGraduationDate) data.expectedGraduationDate = input.expectedGraduationDate
  if (input.materialMediaId) data.materialMediaId = input.materialMediaId
  try { return await request<CampusVerificationApplication>('/auth/wechat/campus-identities/applications', 'POST', data, token) }
  catch (error) { if (error instanceof ApiRequestError && error.statusCode === 401 && storedMiniappSession() === token) clearMiniappSession(); throw error }
}

export interface ClaimedRescueCase { id: string; eventId: string; campusId: string; status: string; claimStatus: string; claimedAt: string | null }

export const claimRescue = async (campusId: string, eventId: string): Promise<ClaimedRescueCase> => {
  const token = storedMiniappSession()
  if (!token) throw new ApiRequestError('请先登录后认领。', 401)
  try { return await request<ClaimedRescueCase>(`/auth/wechat/rescues/${encodeURIComponent(eventId)}/claim`, 'POST', { campusId }, token) }
  catch (error) { if (error instanceof ApiRequestError && error.statusCode === 401 && storedMiniappSession() === token) clearMiniappSession(); throw error }
}

export interface CaseAction { id: string; caseId: string; actorUserId: string; description: string; occurredAt: string; createdAt: string; resultCode: string | null }
export interface CaseActionPage { caseId: string; eventId: string; campusId: string; status: string; items: CaseAction[]; page: number; size: number; total: number }
const caseActionRequest = async <T>(path: string, method: 'GET' | 'POST', data?: Record<string, string>): Promise<T> => {
  const token = storedMiniappSession()
  if (!token) throw new ApiRequestError('请先完成微信登录。', 401)
  try { return await request<T>(path, method, data, token) }
  catch (error) { if (error instanceof ApiRequestError && error.statusCode === 401 && storedMiniappSession() === token) clearMiniappSession(); throw error }
}
export const listCaseActions = (caseId: string, campusId: string, page: number, size = 20) => caseActionRequest<CaseActionPage>(`/auth/wechat/cases/${encodeURIComponent(caseId)}/actions?campusId=${encodeURIComponent(campusId)}&page=${page}&size=${size}`, 'GET')
export const createCaseAction = (caseId: string, campusId: string, description: string, occurredAt: string) => caseActionRequest<CaseAction>(`/auth/wechat/cases/${encodeURIComponent(caseId)}/actions`, 'POST', { campusId, description, occurredAt })

export interface AccessibleCase { id: string; eventId: string; campusId: string; status: string }
export const accessibleCaseByEvent = (eventId: string, campusId: string) => caseActionRequest<AccessibleCase>(`/auth/wechat/rescues/${encodeURIComponent(eventId)}/case?campusId=${encodeURIComponent(campusId)}`, 'GET')
