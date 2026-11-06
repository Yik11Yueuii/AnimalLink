import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'
import { createJsonRequester } from '../services/apiClient'
import { clearMiniappSession, currentCampusIdentity, currentMiniappUser, listIdentityCampuses, loginWithWechat, logoutMiniappUser, storedMiniappSession, submitCampusIdentity } from '../services/userAuthentication'

const TOKEN = 'a'.repeat(43)
const installWx = (mock: Record<string, unknown>) => {
  const global = globalThis as unknown as { wx?: Record<string, unknown> }
  const previous = global.wx
  global.wx = mock
  return () => { if (previous === undefined) delete global.wx; else global.wx = previous }
}

test('ordinary user login uses wx.login and only sends the opaque session to current-user and logout endpoints', async () => {
  const requests: Array<{ url: string; method: string; header: Record<string, string> }> = []
  const storage = new Map<string, string>()
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  const restoreWx = installWx({
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
        options.success({ statusCode: 200, data: { revoked: true } })
      }
    },
  })
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
    restoreWx()
  }
})

test('offline demo never fabricates a WeChat identity', async () => {
  const previousMode = runtimeConfig.userAuthenticationMode
  const restoreWx = installWx({ getStorageSync: () => undefined })
  try {
    runtimeConfig.userAuthenticationMode = 'offline-demo'
    assert.equal(await currentMiniappUser(), null)
    await assert.rejects(() => loginWithWechat(), /离线演示模式/)
  } finally {
    runtimeConfig.userAuthenticationMode = previousMode
    restoreWx()
  }
})

test('an unverified existing session blocks login until explicit logout, including failed revocation', async () => {
  const storage = new Map<string, string>([['animallink.miniapp.user-session', TOKEN]])
  const nextToken = 'b'.repeat(43)
  const calls: string[] = []
  let loginCalls = 0
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  const restoreWx = installWx({
    login: ({ success }: { success: (value: { code: string }) => void }) => {
      loginCalls += 1
      success({ code: 'new-account-code' })
    },
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: string) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    request: (options: { url: string; header: Record<string, string>; success: (response: { statusCode: number; data: unknown }) => void; fail: (error: { errMsg: string }) => void }) => {
      const path = new URL(options.url).pathname
      calls.push(path)
      if (path === '/auth/wechat/me' || path === '/auth/wechat/logout') {
        assert.equal(options.header['X-Miniapp-Session'], TOKEN)
        options.fail({ errMsg: 'request:fail timeout' })
      } else {
        assert.equal(path, '/auth/wechat/login')
        assert.equal(options.header['X-Miniapp-Session'], undefined)
        options.success({ statusCode: 200, data: { token: nextToken, userId: 'new-user', displayName: '新用户', systemRole: 'USER' } })
      }
    },
  })
  try {
    runtimeConfig.userAuthenticationMode = 'api'
    runtimeConfig.gatewayBaseUrl = 'https://example.test'
    await assert.rejects(currentMiniappUser(), /timeout/)
    await assert.rejects(loginWithWechat(), /已有会话/)
    assert.equal(loginCalls, 0)
    assert.equal(storedMiniappSession(), TOKEN)
    await assert.rejects(logoutMiniappUser(), /timeout/)
    assert.equal(storedMiniappSession(), undefined)
    assert.equal((await loginWithWechat()).userId, 'new-user')
    assert.equal(storedMiniappSession(), nextToken)
    assert.equal(loginCalls, 1)
    assert.deepEqual(calls, ['/auth/wechat/me', '/auth/wechat/logout', '/auth/wechat/login'])
  } finally {
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
    restoreWx()
  }
})

test('authentication refuses an unsafe Gateway URL before wx.login or any request', async () => {
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  let loginCalls = 0
  let requestCalls = 0
  const restoreWx = installWx({
    login: () => { loginCalls += 1 },
    request: () => { requestCalls += 1 },
    getStorageSync: () => undefined,
  })
  try {
    runtimeConfig.userAuthenticationMode = 'api'
    for (const gatewayBaseUrl of ['http://192.168.1.8:8080', 'http://127.1:8080',
      'http://user:password@localhost:8080', 'not-a-url']) {
      runtimeConfig.gatewayBaseUrl = gatewayBaseUrl
      await assert.rejects(loginWithWechat(), /真实微信登录尚未配置/)
      assert.equal(loginCalls, 0)
      assert.equal(requestCalls, 0)
    }
  } finally {
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
    restoreWx()
  }
})

test('logout clears the local opaque session even when server revocation cannot be confirmed', async () => {
  const storage = new Map<string, string>([['animallink.miniapp.user-session', TOKEN]])
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  const restoreWx = installWx({
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: string) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    request: ({ fail }: { fail: (value: { errMsg: string }) => void }) => fail({ errMsg: 'request:fail timeout' }),
  })
  try {
    runtimeConfig.userAuthenticationMode = 'api'
    runtimeConfig.gatewayBaseUrl = 'https://localhost:8443'
    await assert.rejects(() => logoutMiniappUser(), /timeout/)
    assert.equal(storedMiniappSession(), undefined)
  } finally {
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
    restoreWx()
  }
})

test('a successful HTTP logout with revoked=false is still reported as unconfirmed', async () => {
  const storage = new Map<string, string>([['animallink.miniapp.user-session', TOKEN]])
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  const restoreWx = installWx({
    getStorageSync: (key: string) => storage.get(key),
    removeStorageSync: (key: string) => storage.delete(key),
    request: ({ success }: { success: (response: { statusCode: number; data: unknown }) => void }) => {
      success({ statusCode: 200, data: { revoked: false } })
    },
  })
  try {
    runtimeConfig.userAuthenticationMode = 'api'
    runtimeConfig.gatewayBaseUrl = 'https://example.test'
    await assert.rejects(logoutMiniappUser(), /撤销未确认/)
    assert.equal(storedMiniappSession(), undefined)
  } finally {
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
    restoreWx()
  }
})

test('campus identity uses only the opaque session and never sends a client actor id', async () => {
  const storage = new Map<string, string>([['animallink.miniapp.user-session', TOKEN]])
  const calls: Array<{ path: string; method: string; data: unknown; session?: string }> = []
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  const restoreWx = installWx({
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
  })
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
    restoreWx()
  }
})

test('login submits only the WeChat code, and a superseded login cannot replace the current session', async () => {
  const storage = new Map<string, string>()
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  const pending: Array<{ url: string; data: unknown; header: Record<string, string>; success: (response: { statusCode: number; data: unknown }) => void }> = []
  const restoreWx = installWx({
    login: ({ success }: { success: (value: { code: string }) => void }) => success({ code: 'one-time-code' }),
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: string) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    request: (options: typeof pending[number]) => { pending.push(options) },
  })
  try {
    runtimeConfig.userAuthenticationMode = 'api'
    runtimeConfig.gatewayBaseUrl = 'https://example.test'
    let current = true
    const login = loginWithWechat(() => current)
    await Promise.resolve()
    assert.deepEqual(pending[0]?.data, { code: 'one-time-code' })
    assert.equal(pending[0]?.header['X-Miniapp-Session'], undefined)
    storage.set('animallink.miniapp.user-session', 'b'.repeat(43))
    current = false
    pending[0]?.success({ statusCode: 200, data: { token: TOKEN, userId: 'old-user', displayName: '旧用户', systemRole: 'USER' } })
    await Promise.resolve()
    assert.equal(new URL(pending[1].url).pathname, '/auth/wechat/logout')
    assert.equal(pending[1].header['X-Miniapp-Session'], TOKEN)
    pending[1].success({ statusCode: 200, data: { revoked: true } })
    await assert.rejects(login, /登录请求已失效/)
    assert.equal(storedMiniappSession(), 'b'.repeat(43))
  } finally {
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
    restoreWx()
  }
})

test('401 clears only its own session; network failures and late responses preserve a newer session', async () => {
  const storage = new Map<string, string>([['animallink.miniapp.user-session', TOKEN]])
  const pending: Array<{ success: (response: { statusCode: number; data: unknown }) => void; fail: (error: { errMsg: string }) => void }> = []
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  const restoreWx = installWx({
    getStorageSync: (key: string) => storage.get(key),
    removeStorageSync: (key: string) => storage.delete(key),
    request: (options: typeof pending[number]) => pending.push(options),
  })
  try {
    runtimeConfig.userAuthenticationMode = 'api'
    runtimeConfig.gatewayBaseUrl = 'https://example.test'
    const network = currentMiniappUser()
    pending.shift()?.fail({ errMsg: 'request:fail timeout' })
    await assert.rejects(network, /timeout/)
    assert.equal(storedMiniappSession(), TOKEN)
    const unavailable = currentCampusIdentity()
    pending.shift()?.success({ statusCode: 503, data: {} })
    await assert.rejects(unavailable, /503/)
    assert.equal(storedMiniappSession(), TOKEN)
    const rejected = currentMiniappUser()
    pending.shift()?.success({ statusCode: 401, data: {} })
    await assert.rejects(rejected, /401/)
    assert.equal(storedMiniappSession(), undefined)

    storage.set('animallink.miniapp.user-session', TOKEN)
    const stale = currentCampusIdentity()
    storage.set('animallink.miniapp.user-session', 'b'.repeat(43))
    pending.shift()?.success({ statusCode: 401, data: {} })
    await assert.rejects(stale, /401/)
    assert.equal(storedMiniappSession(), 'b'.repeat(43))
  } finally {
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
    restoreWx()
  }
})

test('late logout cannot erase a new session, and public data requests never attach the session', async () => {
  const storage = new Map<string, string>([['animallink.miniapp.user-session', TOKEN]])
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  let pendingLogout: { success: (response: { statusCode: number; data: unknown }) => void } | undefined
  let publicHeader: Record<string, string> | undefined
  const restoreWx = installWx({
    getStorageSync: (key: string) => storage.get(key),
    removeStorageSync: (key: string) => storage.delete(key),
    request: (options: { url: string; header: Record<string, string>; success: (response: { statusCode: number; data: unknown }) => void }) => {
      if (options.url.endsWith('/auth/wechat/logout')) pendingLogout = options
      else { publicHeader = options.header; options.success({ statusCode: 200, data: {} }) }
    },
  })
  try {
    runtimeConfig.userAuthenticationMode = 'api'
    runtimeConfig.gatewayBaseUrl = 'https://example.test'
    const logout = logoutMiniappUser()
    storage.set('animallink.miniapp.user-session', 'b'.repeat(43))
    pendingLogout?.success({ statusCode: 200, data: { revoked: true } })
    await logout
    assert.equal(storedMiniappSession(), 'b'.repeat(43))
    await createJsonRequester('https://example.test')('/animal/api/v1/public/animals')
    assert.equal(publicHeader?.['X-Miniapp-Session'], undefined)
  } finally {
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
    restoreWx()
  }
})

test('a non-USER response cannot be accepted as an ordinary miniapp identity', async () => {
  const storage = new Map<string, string>()
  const revocations: string[] = []
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  const restoreWx = installWx({
    login: ({ success }: { success: (value: { code: string }) => void }) => success({ code: 'wechat-code-123' }),
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: string) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    request: ({ url, header, success }: { url: string; header: Record<string, string>;
      success: (response: { statusCode: number; data: unknown }) => void }) => {
      if (url.endsWith('/auth/wechat/logout')) {
        revocations.push(header['X-Miniapp-Session'])
        success({ statusCode: 200, data: { revoked: true } })
      } else success({
        statusCode: 200, data: { token: TOKEN, userId: 'admin-user', displayName: '管理员', systemRole: 'GOVERNANCE_ADMIN' },
      })
    },
  })
  try {
    runtimeConfig.userAuthenticationMode = 'api'
    runtimeConfig.gatewayBaseUrl = 'https://example.test'
    await assert.rejects(loginWithWechat(), /身份服务响应无效，新会话已撤销/)
    assert.equal(storedMiniappSession(), undefined)
    assert.deepEqual(revocations, [TOKEN])
    storage.set('animallink.miniapp.user-session', TOKEN)
    await assert.rejects(currentMiniappUser(), /身份服务响应无效/)
  } finally {
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
    restoreWx()
  }
})

test('a failed post-issuance cancellation check revokes the new session', async () => {
  const h = storageFailureLogin((storage, key, token) => { storage.set(key, token) })
  try {
    let checks = 0
    const login = loginWithWechat(() => {
      checks += 1
      if (checks > 1) throw new Error('page unavailable')
      return true
    })
    await later()
    h.take('/auth/wechat/login').success({ statusCode: 200, data: issuedLogin })
    await later()
    const revoke = h.take('/auth/wechat/logout')
    assert.equal(revoke.header['X-Miniapp-Session'], TOKEN)
    revoke.success({ statusCode: 200, data: { revoked: true } })
    await assert.rejects(login, /登录请求已失效，新会话已撤销/)
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
  } finally { h.restore() }
})

const storageFailureLogin = (
  write: (storage: Map<string, string>, key: string, token: string) => void,
  read: (storage: Map<string, string>, key: string) => string | undefined = (storage, key) => storage.get(key),
) => {
  const storage = new Map<string, string>()
  let loginCalls = 0
  const pending: Array<{ path: string; header: Record<string, string>;
    success: (response: { statusCode: number; data: unknown }) => void;
    fail: (error: { errMsg: string }) => void }> = []
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  const restoreWx = installWx({
    login: ({ success }: { success: (value: { code: string }) => void }) => {
      loginCalls += 1
      success({ code: 'one-time-code' })
    },
    getStorageSync: (key: string) => read(storage, key),
    setStorageSync: (key: string, token: string) => write(storage, key, token),
    removeStorageSync: (key: string) => storage.delete(key),
    request: (options: { url: string; header: Record<string, string>;
      success: (response: { statusCode: number; data: unknown }) => void;
      fail: (error: { errMsg: string }) => void }) => {
      pending.push({ path: new URL(options.url).pathname, header: options.header,
        success: options.success, fail: options.fail })
    },
  })
  runtimeConfig.userAuthenticationMode = 'api'
  runtimeConfig.gatewayBaseUrl = 'https://example.test'
  const take = (path: string) => {
    const index = pending.findIndex(request => request.path === path)
    assert.notEqual(index, -1, `missing request for ${path}`)
    return pending.splice(index, 1)[0]
  }
  const restore = () => {
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
    restoreWx()
  }
  return { storage, pending, take, restore, loginCalls: () => loginCalls }
}

const issuedLogin = { token: TOKEN, userId: 'user-1', displayName: '微信用户', systemRole: 'USER' }
const later = () => new Promise<void>(resolve => setImmediate(resolve))

test('a successful token save does not issue a compensating logout', async () => {
  const h = storageFailureLogin((storage, key, token) => { storage.set(key, token) })
  try {
    const login = loginWithWechat()
    await later()
    h.take('/auth/wechat/login').success({ statusCode: 200, data: issuedLogin })
    assert.equal((await login).userId, 'user-1')
    assert.equal(h.storage.get('animallink.miniapp.user-session'), TOKEN)
    assert.equal(h.pending.length, 0)
  } finally { h.restore() }
})

test('failed token storage revokes the issued session using the response token, without accepting login', async () => {
  const h = storageFailureLogin(() => { throw new Error('storage unavailable') })
  try {
    const login = loginWithWechat()
    await later()
    const loginRequest = h.take('/auth/wechat/login')
    assert.equal(loginRequest.header['X-Miniapp-Session'], undefined)
    loginRequest.success({ statusCode: 200, data: issuedLogin })
    await later()
    const revoke = h.take('/auth/wechat/logout')
    assert.equal(revoke.header['X-Miniapp-Session'], TOKEN)
    revoke.success({ statusCode: 200, data: { revoked: true } })
    await assert.rejects(login, error => {
      assert.match(String(error), /本地登录信息保存失败，新会话已撤销/)
      assert.doesNotMatch(String(error), new RegExp(TOKEN))
      return true
    })
    assert.equal(storedMiniappSession(), undefined)
    assert.equal(h.pending.length, 0)
  } finally { h.restore() }
})

test('failed token storage reports unconfirmed revocation on rejection, timeout or revoked=false', async () => {
  for (const outcome of ['rejected', 'timeout', 'not-revoked'] as const) {
    const h = storageFailureLogin(() => { throw new Error('storage unavailable') })
    try {
      const login = loginWithWechat()
      await later()
      h.take('/auth/wechat/login').success({ statusCode: 200, data: issuedLogin })
      await later()
      const revoke = h.take('/auth/wechat/logout')
      assert.equal(revoke.header['X-Miniapp-Session'], TOKEN)
      if (outcome === 'timeout') revoke.fail({ errMsg: 'request:fail timeout' })
      else if (outcome === 'not-revoked') revoke.success({ statusCode: 200, data: { revoked: false } })
      else revoke.success({ statusCode: 503, data: {} })
      await assert.rejects(login, error => {
        assert.match(String(error), /本地登录信息保存失败，服务端 Session 撤销未确认/)
        assert.doesNotMatch(String(error), new RegExp(TOKEN))
        return true
      })
      assert.equal(storedMiniappSession(), undefined)
      assert.equal(h.pending.length, 0)
    } finally { h.restore() }
  }
})

test('partial storage writes remove only the newly issued token and preserve a different token', async () => {
  for (const replacement of [undefined, 'b'.repeat(43)]) {
    const h = storageFailureLogin((storage, key, token) => {
      storage.set(key, replacement ?? token)
      throw new Error('partial write')
    })
    try {
      const login = loginWithWechat()
      await later()
      h.take('/auth/wechat/login').success({ statusCode: 200, data: issuedLogin })
      await later()
      const revoke = h.take('/auth/wechat/logout')
      assert.equal(revoke.header['X-Miniapp-Session'], TOKEN)
      revoke.success({ statusCode: 200, data: { revoked: true } })
      await assert.rejects(login, /本地登录信息保存失败，新会话已撤销/)
      assert.equal(storedMiniappSession(), replacement)
      assert.equal(h.pending.length, 0)
    } finally { h.restore() }
  }
})

test('a pre-login storage read failure cannot send a code or create a Gateway session', async () => {
  const h = storageFailureLogin(() => {}, () => { throw new Error('storage read unavailable') })
  try {
    await assert.rejects(loginWithWechat(), /storage read unavailable/)
    assert.equal(h.loginCalls(), 0)
    assert.equal(h.pending.length, 0)
  } finally { h.restore() }
})

test('a post-issuance storage read failure revokes the new token and preserves a different stored token', async () => {
  const otherToken = 'b'.repeat(43)
  let reads = 0
  const h = storageFailureLogin((storage, key, token) => { storage.set(key, token) }, (storage, key) => {
    reads += 1
    if (reads === 2) { storage.set(key, otherToken); throw new Error('storage read unavailable') }
    return storage.get(key)
  })
  try {
    const login = loginWithWechat()
    await later()
    const request = h.take('/auth/wechat/login')
    assert.equal(request.header['X-Miniapp-Session'], undefined)
    request.success({ statusCode: 200, data: issuedLogin })
    await later()
    const revoke = h.take('/auth/wechat/logout')
    assert.equal(revoke.header['X-Miniapp-Session'], TOKEN)
    revoke.success({ statusCode: 200, data: { revoked: true } })
    await assert.rejects(login, /本地登录信息保存失败，新会话已撤销/)
    assert.equal(h.storage.get('animallink.miniapp.user-session'), otherToken)
    assert.equal(h.pending.length, 0)
  } finally { h.restore() }
})

test('a post-issuance storage read failure still attempts revocation when storage remains unreadable', async () => {
  let reads = 0
  const h = storageFailureLogin(() => {}, () => {
    reads += 1
    if (reads > 1) throw new Error('storage read unavailable')
    return undefined
  })
  try {
    const login = loginWithWechat()
    await later()
    h.take('/auth/wechat/login').success({ statusCode: 200, data: issuedLogin })
    await later()
    const revoke = h.take('/auth/wechat/logout')
    assert.equal(revoke.header['X-Miniapp-Session'], TOKEN)
    revoke.success({ statusCode: 200, data: { revoked: true } })
    await assert.rejects(login, /新会话已撤销，但本地清理未确认/)
    assert.equal(h.pending.length, 0)
  } finally { h.restore() }
})

test('a post-issuance storage read failure reports unconfirmed revocation on 503, timeout or revoked=false', async () => {
  for (const outcome of ['unavailable', 'timeout', 'not-revoked'] as const) {
    let reads = 0
    const h = storageFailureLogin(() => {}, (storage, key) => {
      reads += 1
      if (reads === 2) throw new Error('storage read unavailable')
      return storage.get(key)
    })
    try {
      const login = loginWithWechat()
      await later()
      h.take('/auth/wechat/login').success({ statusCode: 200, data: issuedLogin })
      await later()
      const revoke = h.take('/auth/wechat/logout')
      assert.equal(revoke.header['X-Miniapp-Session'], TOKEN)
      if (outcome === 'timeout') revoke.fail({ errMsg: 'request:fail timeout' })
      else if (outcome === 'not-revoked') revoke.success({ statusCode: 200, data: { revoked: false } })
      else revoke.success({ statusCode: 503, data: {} })
      await assert.rejects(login, error => {
        assert.match(String(error), /本地登录信息保存失败，服务端 Session 撤销未确认/)
        assert.doesNotMatch(String(error), new RegExp(TOKEN))
        return true
      })
      assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
      assert.equal(h.pending.length, 0)
    } finally { h.restore() }
  }
})

test('a silent storage write failure is detected by read-back and revokes the issued session', async () => {
  const h = storageFailureLogin(() => {})
  try {
    const login = loginWithWechat()
    await later()
    h.take('/auth/wechat/login').success({ statusCode: 200, data: issuedLogin })
    await later()
    const revoke = h.take('/auth/wechat/logout')
    assert.equal(revoke.header['X-Miniapp-Session'], TOKEN)
    revoke.success({ statusCode: 200, data: { revoked: true } })
    await assert.rejects(login, /本地登录信息保存失败，新会话已撤销/)
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.equal(h.pending.length, 0)
  } finally { h.restore() }
})

test('a read-back exception after partial storage write cleans only the issued token and revokes it', async () => {
  let reads = 0
  const h = storageFailureLogin((storage, key, token) => { storage.set(key, token) }, (storage, key) => {
    reads += 1
    if (reads === 3) throw new Error('storage verification unavailable')
    return storage.get(key)
  })
  try {
    const login = loginWithWechat()
    await later()
    h.take('/auth/wechat/login').success({ statusCode: 200, data: issuedLogin })
    await later()
    const revoke = h.take('/auth/wechat/logout')
    assert.equal(revoke.header['X-Miniapp-Session'], TOKEN)
    revoke.success({ statusCode: 200, data: { revoked: true } })
    await assert.rejects(login, /本地登录信息保存失败，新会话已撤销/)
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.equal(h.pending.length, 0)
  } finally { h.restore() }
})
