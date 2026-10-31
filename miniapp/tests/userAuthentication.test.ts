import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'
import { clearMiniappSession, currentCampusIdentity, currentMiniappUser, listIdentityCampuses, loginWithWechat, logoutMiniappUser, storedMiniappSession, submitCampusIdentity } from '../services/userAuthentication'

const TOKEN = 'a'.repeat(43)

test('ordinary user login uses wx.login and only sends the opaque session to current-user and logout endpoints', async () => {
  const requests: Array<{ url: string; method: string; header: Record<string, string> }> = []
  const storage = new Map<string, string>()
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  ;(globalThis as unknown as { wx: Record<string, unknown> }).wx = {
    login: ({ success }: { success: (value: { code: string }) => void }) => success({ code: 'wechat-code-123' }),
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: string) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    request: (options: { url: string; method: string; header: Record<string, string>; success: (response: { statusCode: number; data: unknown }) => void }) => {
      requests.push({ url: options.url, method: options.method, header: options.header })
      if (options.url.endsWith('/auth/wechat/login')) {
        options.success({ statusCode: 200, data: { token: TOKEN, userId: 'user-1', displayName: '微信用户', systemRole: 'USER', idleExpiresAt: '2026-11-01T00:00:00Z', absoluteExpiresAt: '2026-11-30T00:00:00Z' } })
      } else if (options.url.endsWith('/auth/wechat/me')) {
        options.success({ statusCode: 200, data: { userId: 'user-1', displayName: '微信用户', systemRole: 'USER', idleExpiresAt: '2026-11-01T00:00:00Z', absoluteExpiresAt: '2026-11-30T00:00:00Z' } })
      } else {
        options.success({ statusCode: 204, data: {} })
      }
    },
  }
  try {
    runtimeConfig.userAuthenticationMode = 'api'
    runtimeConfig.gatewayBaseUrl = 'https://localhost:8443/'
    assert.equal((await loginWithWechat()).systemRole, 'USER')
    assert.equal(storedMiniappSession(), TOKEN)
    assert.equal((await currentMiniappUser())?.userId, 'user-1')
    await logoutMiniappUser()
    assert.equal(storedMiniappSession(), undefined)
    assert.deepEqual(requests.map(({ url, method, header }) => ({ path: new URL(url).pathname, method, session: header['X-Miniapp-Session'] })), [
      { path: '/auth/wechat/login', method: 'POST', session: undefined },
      { path: '/auth/wechat/me', method: 'GET', session: TOKEN },
      { path: '/auth/wechat/logout', method: 'POST', session: TOKEN },
    ])
  } finally {
    clearMiniappSession()
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
  }
})

test('offline demo never fabricates a WeChat identity', async () => {
  const previousMode = runtimeConfig.userAuthenticationMode
  try {
    runtimeConfig.userAuthenticationMode = 'offline-demo'
    assert.equal(await currentMiniappUser(), null)
    await assert.rejects(() => loginWithWechat(), /离线演示模式/)
  } finally {
    runtimeConfig.userAuthenticationMode = previousMode
  }
})

test('logout clears the local opaque session even when server revocation cannot be confirmed', async () => {
  const storage = new Map<string, string>([['animallink.miniapp.user-session', TOKEN]])
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  ;(globalThis as unknown as { wx: Record<string, unknown> }).wx = {
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: string) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    request: ({ fail }: { fail: (value: { errMsg: string }) => void }) => fail({ errMsg: 'request:fail timeout' }),
  }
  try {
    runtimeConfig.userAuthenticationMode = 'api'
    runtimeConfig.gatewayBaseUrl = 'https://localhost:8443'
    await assert.rejects(() => logoutMiniappUser(), /timeout/)
    assert.equal(storedMiniappSession(), undefined)
  } finally {
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
  }
})

test('campus identity uses only the opaque session and never sends a client actor id', async () => {
  const storage = new Map<string, string>([['animallink.miniapp.user-session', TOKEN]])
  const calls: Array<{ path: string; method: string; data: unknown; session?: string }> = []
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  ;(globalThis as unknown as { wx: Record<string, unknown> }).wx = {
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: string) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    request: (options: { url: string; method: string; data: unknown; header: Record<string, string>; success: (response: { statusCode: number; data: unknown }) => void }) => {
      const path = new URL(options.url).pathname
      calls.push({ path, method: options.method, data: options.data, session: options.header['X-Miniapp-Session'] })
      if (path === '/auth/wechat/campus-identities' && options.method === 'GET') {
        options.success({ statusCode: 200, data: { memberships: [], applications: [] } })
      } else if (path === '/identity/api/v1/campuses') {
        options.success({ statusCode: 200, data: [{ id: '22000000-0000-4000-8000-000000000001', name: '韩山师范学院社区', status: 'ACTIVE' }] })
      } else {
        options.success({ statusCode: 201, data: { id: 'application-1', status: 'PENDING_REVIEW' } })
      }
    },
  }
  try {
    runtimeConfig.userAuthenticationMode = 'api'
    runtimeConfig.gatewayBaseUrl = 'https://localhost:8443'
    assert.deepEqual(await currentCampusIdentity(), { memberships: [], applications: [] })
    assert.equal((await listIdentityCampuses())[0].name, '韩山师范学院社区')
    await submitCampusIdentity({
      campusId: '22000000-0000-4000-8000-000000000001', requestedMembershipType: 'STUDENT',
      applicantName: '演示同学', affiliationNote: '教育学院', expectedGraduationDate: '2027-06-30',
    })
    assert.deepEqual(calls, [
      { path: '/auth/wechat/campus-identities', method: 'GET', data: undefined, session: TOKEN },
      { path: '/identity/api/v1/campuses', method: 'GET', data: undefined, session: undefined },
      { path: '/auth/wechat/campus-identities/applications', method: 'POST', data: {
        campusId: '22000000-0000-4000-8000-000000000001', requestedMembershipType: 'STUDENT',
        applicantName: '演示同学', affiliationNote: '教育学院', expectedGraduationDate: '2027-06-30',
      }, session: TOKEN },
    ])
  } finally {
    clearMiniappSession()
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
  }
})
