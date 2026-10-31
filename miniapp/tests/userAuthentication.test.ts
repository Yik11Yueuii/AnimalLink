import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'
import { clearMiniappSession, currentMiniappUser, loginWithWechat, logoutMiniappUser, storedMiniappSession } from '../services/userAuthentication'

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
