import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { runtimeConfig } from '../config/runtime'

type PageDefinition = { data: Record<string, unknown>; [name: string]: unknown }
type Request = {
  url: string
  method: string
  success: (response: { statusCode: number; data: unknown }) => void
  fail: (error: { errMsg: string }) => void
}

const TOKEN = 'a'.repeat(43)
const later = () => new Promise<void>(resolve => setImmediate(resolve))
let pageDefinition: PageDefinition | undefined

const createPage = async () => {
  if (!pageDefinition) {
    const global = globalThis as unknown as { Page?: (options: PageDefinition) => void }
    const previousPage = global.Page
    global.Page = options => { pageDefinition = options }
    try { await import('../pages/my/index') }
    finally { if (previousPage === undefined) delete global.Page; else global.Page = previousPage }
  }
  assert.ok(pageDefinition)
  return Object.assign({}, pageDefinition, {
    data: JSON.parse(JSON.stringify(pageDefinition.data)) as Record<string, unknown>,
    setData(update: Record<string, unknown>) { Object.assign(this.data, update) },
  }) as PageDefinition & { setData: (update: Record<string, unknown>) => void }
}

const deferred = <T>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

type Response = { statusCode: number; data: unknown }
type Outcome = { response: Response } | { failure: { errMsg: string } }
const controlledPage = async (initialToken?: string) => {
  const page = await createPage()
  const global = globalThis as unknown as { wx?: Record<string, unknown> }
  const previousWx = global.wx
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  const storage = new Map<string, string>(initialToken ? [['animallink.miniapp.user-session', initialToken]] : [])
  const requests: Array<{ path: string; header: Record<string, string>; outcome: ReturnType<typeof deferred<Outcome>> }> = []
  const logins: Array<ReturnType<typeof deferred<{ code: string }>>> = []
  global.wx = {
    login: ({ success }: { success: (value: { code: string }) => void }) => {
      const result = deferred<{ code: string }>()
      logins.push(result)
      void result.promise.then(success)
    },
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: string) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    request: (options: Request & { header: Record<string, string> }) => {
      const outcome = deferred<Outcome>()
      requests.push({ path: new URL(options.url).pathname, header: options.header, outcome })
      void outcome.promise.then(result => {
        if ('response' in result) options.success(result.response)
        else options.fail(result.failure)
      })
    },
  }
  runtimeConfig.userAuthenticationMode = 'api'
  runtimeConfig.gatewayBaseUrl = 'https://example.test'
  const take = (path: string) => {
    const index = requests.findIndex(request => request.path === path)
    assert.notEqual(index, -1, `missing request for ${path}`)
    return requests.splice(index, 1)[0]
  }
  const respond = (path: string, statusCode: number, data: unknown = {}) => take(path).outcome.resolve({ response: { statusCode, data } })
  const fail = (path: string) => take(path).outcome.resolve({ failure: { errMsg: 'request:fail timeout' } })
  const restoreGlobals = () => {
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
    if (previousWx === undefined) delete global.wx; else global.wx = previousWx
  }
  return { page, storage, requests, logins, take, respond, fail, restoreGlobals }
}

test('My page retries session validation after a network failure, then logs in and clears stale identity on logout or 401', async () => {
  const global = globalThis as unknown as { wx?: Record<string, unknown> }
  const previousWx = global.wx
  const page = await createPage()
  const storage = new Map<string, string>([['animallink.miniapp.user-session', TOKEN]])
  const pending: Request[] = []
  let loginCalls = 0
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  global.wx = {
    login: ({ success }: { success: (value: { code: string }) => void }) => { loginCalls += 1; success({ code: 'wechat-code-123' }) },
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: string) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    request: (options: Request) => pending.push(options),
  }
  const take = (path: string) => {
    const index = pending.findIndex(request => new URL(request.url).pathname === path)
    assert.notEqual(index, -1, `missing request for ${path}`)
    return pending.splice(index, 1)[0]
  }
  const resolveIdentity = async () => {
    await later()
    take('/auth/wechat/campus-identities').success({ statusCode: 200, data: { memberships: [], applications: [] } })
    await later()
    take('/identity/api/v1/campuses').success({ statusCode: 200, data: [] })
  }
  try {
    runtimeConfig.userAuthenticationMode = 'api'
    runtimeConfig.gatewayBaseUrl = 'https://example.test'

    const failedRestore = (page.restoreSession as () => Promise<void>).call(page)
    take('/auth/wechat/me').fail({ errMsg: 'request:fail timeout' })
    await failedRestore
    assert.equal(page.data.user, null)
    assert.equal(page.data.sessionRetryAvailable, true)
    assert.equal(storage.get('animallink.miniapp.user-session'), TOKEN)
    await (page.login as () => Promise<void>).call(page)
    assert.equal(loginCalls, 0)
    assert.equal(pending.length, 0)

    const retry = (page.restoreSession as () => Promise<void>).call(page)
    take('/auth/wechat/me').success({ statusCode: 200, data: { userId: 'user-1', displayName: '微信用户', systemRole: 'USER' } })
    await resolveIdentity()
    await retry
    assert.equal((page.data.user as unknown as { userId: string }).userId, 'user-1')
    assert.equal(page.data.sessionRetryAvailable, false)

    const logout = (page.logout as () => Promise<void>).call(page)
    take('/auth/wechat/logout').fail({ errMsg: 'request:fail timeout' })
    await logout
    assert.equal(page.data.user, null)
    assert.equal(page.data.identity, null)
    assert.equal(storage.has('animallink.miniapp.user-session'), false)
    assert.match(String(page.data.error), /服务端会话撤销未得到确认/)

    const login = (page.login as () => Promise<void>).call(page)
    await (page.login as () => Promise<void>).call(page)
    assert.equal(loginCalls, 1)
    await later()
    take('/auth/wechat/login').success({ statusCode: 200, data: { token: TOKEN, userId: 'user-2', displayName: '新用户', systemRole: 'USER' } })
    await resolveIdentity()
    await login
    assert.equal((page.data.user as unknown as { userId: string }).userId, 'user-2')
    assert.equal(storage.get('animallink.miniapp.user-session'), TOKEN)

    const campusUnavailable = (page.loadCampusIdentity as (epoch: number, userId: string) => Promise<void>)
      .call(page, Number(page.data.sessionEpoch), 'user-2')
    take('/auth/wechat/campus-identities').success({ statusCode: 200, data: { memberships: [], applications: [] } })
    await later()
    take('/identity/api/v1/campuses').success({ statusCode: 401, data: {} })
    await campusUnavailable
    assert.equal((page.data.user as unknown as { userId: string }).userId, 'user-2')
    assert.equal(storage.get('animallink.miniapp.user-session'), TOKEN)
    assert.match(String(page.data.error), /校园列表暂不可用/)

    const identityExpired = (page.loadCampusIdentity as (epoch: number, userId: string) => Promise<void>)
      .call(page, Number(page.data.sessionEpoch), 'user-2')
    assert.equal(pending.some(request => new URL(request.url).pathname === '/identity/api/v1/campuses'), false)
    take('/auth/wechat/campus-identities').success({ statusCode: 401, data: {} })
    await identityExpired
    assert.equal(storage.has('animallink.miniapp.user-session'), false)
    assert.equal(page.data.user, null)
    assert.equal(pending.length, 0)

    storage.set('animallink.miniapp.user-session', TOKEN)
    page.setData({ user: { userId: 'user-2', displayName: '新用户' } })
    const expired = (page.restoreSession as () => Promise<void>).call(page)
    take('/auth/wechat/me').success({ statusCode: 401, data: {} })
    await expired
    assert.equal(storage.has('animallink.miniapp.user-session'), false)
    assert.equal(page.data.user, null)
    assert.equal(page.data.identity, null)
    assert.equal(page.data.sessionRetryAvailable, false)

    storage.set('animallink.miniapp.user-session', TOKEN)
    page.setData({ user: { userId: 'user-2', displayName: '新用户' }, identity: { memberships: [], applications: [] },
      campuses: [{ id: '22000000-0000-4000-8000-000000000001', name: '韩山师范学院社区' }],
      form: { applicantName: '测试用户', affiliationNote: '', expectedGraduationDate: '' }, showIdentityForm: true })
    const rejectedApplication = (page.submitIdentity as () => Promise<void>).call(page)
    take('/auth/wechat/campus-identities/applications').success({ statusCode: 401, data: {} })
    await rejectedApplication
    assert.equal(storage.has('animallink.miniapp.user-session'), false)
    assert.equal(page.data.user, null)
    assert.equal(page.data.identity, null)
    assert.equal((page.data.form as { applicantName: string }).applicantName, '')

    storage.set('animallink.miniapp.user-session', TOKEN)
    page.setData({ user: { userId: 'old-user' }, identity: { memberships: [], applications: [] },
      campuses: [{ id: '22000000-0000-4000-8000-000000000001', name: '韩山师范学院社区' }],
      form: { applicantName: '旧用户', affiliationNote: '', expectedGraduationDate: '' }, showIdentityForm: true })
    const staleApplication = (page.submitIdentity as () => Promise<void>).call(page)
    page.setData({ sessionEpoch: Number(page.data.sessionEpoch) + 1, user: { userId: 'new-user' },
      identity: null, authPhase: 'authenticated', loading: false,
      showIdentityForm: false, form: { applicantName: '', affiliationNote: '', expectedGraduationDate: '' } })
    take('/auth/wechat/campus-identities/applications').success({ statusCode: 201, data: { status: 'PENDING_REVIEW' } })
    await staleApplication
    assert.equal((page.data.user as unknown as { userId: string }).userId, 'new-user')
    assert.equal(page.data.identity, null)

    storage.set('animallink.miniapp.user-session', TOKEN)
    const oldRestore = (page.restoreSession as () => Promise<void>).call(page)
    const oldRequest = take('/auth/wechat/me')
    const newerToken = 'b'.repeat(43)
    storage.set('animallink.miniapp.user-session', newerToken)
    page.setData({ sessionEpoch: Number(page.data.sessionEpoch) + 1, user: { userId: 'latest-user' },
      identity: { memberships: [], applications: [] }, loading: false })
    oldRequest.success({ statusCode: 401, data: {} })
    await oldRestore
    assert.equal(storage.get('animallink.miniapp.user-session'), newerToken)
    assert.equal((page.data.user as unknown as { userId: string }).userId, 'latest-user')
    assert.deepEqual(page.data.identity, { memberships: [], applications: [] })
  } finally {
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
    if (previousWx === undefined) delete global.wx; else global.wx = previousWx
  }
})

test('My page exposes network retry only in API mode and disables actions while loading', async () => {
  const wxml = await readFile('pages/my/index.wxml', 'utf8')
  assert.match(wxml, /sessionRetryAvailable && !loading/)
  assert.match(wxml, /bindtap="restoreSession"/)
  assert.match(wxml, /wx:if="\{\{!offline && !sessionRetryAvailable && authPhase === 'unauthenticated'\}\}"[^>]*bindtap="login"/)
  assert.match(wxml, /authPhase === 'loggingOut'/)
  assert.match(wxml, /退出当前会话并切换账号/)
  assert.match(wxml, /disabled="\{\{loading\}\}"[^>]*bindtap="submitIdentity"/)
})

test('logout blocks onShow restoration and clears all old identity even while revocation is pending', async () => {
  const h = await controlledPage(TOKEN)
  try {
    h.page.setData({ offline: false, authPhase: 'authenticated', user: { userId: 'old-user' },
      identity: { memberships: [{ id: 'old-membership' }], applications: [] },
      campuses: [{ id: 'old-campus' }], form: { applicantName: '旧用户' }, showIdentityForm: true })
    const logout = (h.page.logout as () => Promise<void>).call(h.page)
    assert.equal(h.page.data.authPhase, 'loggingOut')
    assert.equal(h.page.data.user, null)
    assert.equal(h.page.data.identity, null)
    assert.deepEqual(h.page.data.campuses, [])
    assert.equal((h.page.data.form as { applicantName: string }).applicantName, '')
    ;(h.page.onShow as () => void).call(h.page)
    assert.equal(h.requests.filter(value => value.path === '/auth/wechat/me').length, 0)
    const pendingLogout = h.take('/auth/wechat/logout')
    assert.equal(pendingLogout.header['X-Miniapp-Session'], TOKEN)
    pendingLogout.outcome.resolve({ response: { statusCode: 200, data: { revoked: true } } })
    await logout
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.equal(h.page.data.authPhase, 'unauthenticated')
    assert.equal(h.page.data.user, null)
    assert.equal(h.page.data.identity, null)
  } finally { h.restoreGlobals() }
})

test('old session restoration cannot revive identity whether it returns before or after logout completes', async () => {
  for (const oldResponseFirst of [true, false]) {
    const h = await controlledPage(TOKEN)
    try {
      h.page.setData({ offline: false, authPhase: 'authenticated', user: { userId: 'old-user' },
        identity: { memberships: [{ id: 'old-membership' }], applications: [] } })
      const restore = (h.page.restoreSession as () => Promise<void>).call(h.page)
      const oldMe = h.take('/auth/wechat/me')
      const logout = (h.page.logout as () => Promise<void>).call(h.page)
      const revoke = h.take('/auth/wechat/logout')
      if (oldResponseFirst) {
        oldMe.outcome.resolve({ response: { statusCode: 200, data: { userId: 'old-user', systemRole: 'USER' } } })
        await restore
        assert.equal(h.page.data.authPhase, 'loggingOut')
        assert.equal(h.page.data.user, null)
      }
      revoke.outcome.resolve({ response: { statusCode: 200, data: { revoked: true } } })
      await logout
      if (!oldResponseFirst) {
        oldMe.outcome.resolve({ response: { statusCode: 200, data: { userId: 'old-user', systemRole: 'USER' } } })
        await restore
      }
      assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
      assert.equal(h.page.data.user, null)
      assert.equal(h.page.data.identity, null)
      assert.equal(h.page.data.authPhase, 'unauthenticated')
      assert.equal(h.requests.length, 0)
    } finally { h.restoreGlobals() }
  }
})

test('failed server revocation leaves a local logout, not old user or membership', async () => {
  const h = await controlledPage(TOKEN)
  try {
    h.page.setData({ offline: false, authPhase: 'authenticated', user: { userId: 'old-user' },
      identity: { memberships: [{ id: 'old-membership' }], applications: [] } })
    const logout = (h.page.logout as () => Promise<void>).call(h.page)
    h.fail('/auth/wechat/logout')
    await logout
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.equal(h.page.data.user, null)
    assert.equal(h.page.data.identity, null)
    assert.equal(h.page.data.authPhase, 'unauthenticated')
    assert.match(String(h.page.data.error), /服务端会话撤销未得到确认/)
  } finally { h.restoreGlobals() }
})

test('one or two onShow calls and repeated taps during login preserve one successful session', async () => {
  const h = await controlledPage()
  try {
    h.page.setData({ offline: false })
    const login = (h.page.login as () => Promise<void>).call(h.page)
    assert.equal(h.page.data.authPhase, 'loggingIn')
    ;(h.page.onShow as () => void).call(h.page)
    ;(h.page.onShow as () => void).call(h.page)
    await (h.page.login as () => Promise<void>).call(h.page)
    assert.equal(h.logins.length, 1)
    assert.equal(h.requests.length, 0)
    h.logins[0].resolve({ code: 'one-time-code' })
    await later()
    const loginRequest = h.take('/auth/wechat/login')
    assert.equal(loginRequest.header['X-Miniapp-Session'], undefined)
    loginRequest.outcome.resolve({ response: { statusCode: 200, data: {
      token: TOKEN, userId: 'new-user', displayName: '新用户', systemRole: 'USER',
    } } })
    await later()
    h.respond('/auth/wechat/campus-identities', 200, { memberships: [], applications: [] })
    await later()
    h.respond('/identity/api/v1/campuses', 200, [])
    await login
    assert.equal(h.storage.get('animallink.miniapp.user-session'), TOKEN)
    assert.equal((h.page.data.user as { userId: string }).userId, 'new-user')
    assert.equal(h.page.data.authPhase, 'authenticated')
    assert.equal(h.requests.length, 0)
  } finally { h.restoreGlobals() }
})

test('login failure is shown and allows retry without saving a session', async () => {
  const h = await controlledPage()
  try {
    h.page.setData({ offline: false })
    const login = (h.page.login as () => Promise<void>).call(h.page)
    h.logins[0].resolve({ code: 'one-time-code' })
    await later()
    h.fail('/auth/wechat/login')
    await login
    assert.equal(h.page.data.authPhase, 'unauthenticated')
    assert.match(String(h.page.data.error), /timeout/)
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.equal(h.page.data.loading, false)
  } finally { h.restoreGlobals() }
})

test('a late old restore cannot replace a new user after logout and a fresh login', async () => {
  const h = await controlledPage(TOKEN)
  try {
    h.page.setData({ offline: false, authPhase: 'authenticated', user: { userId: 'old-user' },
      identity: { memberships: [{ id: 'old-membership' }], applications: [] } })
    const restore = (h.page.restoreSession as () => Promise<void>).call(h.page)
    const oldMe = h.take('/auth/wechat/me')
    const logout = (h.page.logout as () => Promise<void>).call(h.page)
    h.respond('/auth/wechat/logout', 200, { revoked: true })
    await logout
    const login = (h.page.login as () => Promise<void>).call(h.page)
    h.logins[0].resolve({ code: 'new-account-code' })
    await later()
    const newToken = 'b'.repeat(43)
    h.respond('/auth/wechat/login', 200, { token: newToken, userId: 'new-user', displayName: '新用户', systemRole: 'USER' })
    await later()
    h.respond('/auth/wechat/campus-identities', 200, { memberships: [{ id: 'new-membership' }], applications: [] })
    await later()
    h.respond('/identity/api/v1/campuses', 200, [])
    await login
    oldMe.outcome.resolve({ response: { statusCode: 200, data: { userId: 'old-user', systemRole: 'USER' } } })
    await restore
    assert.equal(h.storage.get('animallink.miniapp.user-session'), newToken)
    assert.equal((h.page.data.user as { userId: string }).userId, 'new-user')
    assert.deepEqual(h.page.data.identity, { memberships: [{ id: 'new-membership' }], applications: [] })
    assert.equal(h.page.data.authPhase, 'authenticated')
  } finally { h.restoreGlobals() }
})

test('explicit logout cancels an in-flight issued login and revokes that new server session', async () => {
  const h = await controlledPage()
  try {
    h.page.setData({ offline: false })
    const login = (h.page.login as () => Promise<void>).call(h.page)
    h.logins[0].resolve({ code: 'one-time-code' })
    await later()
    const issuedLogin = h.take('/auth/wechat/login')
    const logout = (h.page.logout as () => Promise<void>).call(h.page)
    assert.equal(h.page.data.authPhase, 'loggingOut')
    issuedLogin.outcome.resolve({ response: { statusCode: 200, data: {
      token: TOKEN, userId: 'cancelled-user', displayName: '取消用户', systemRole: 'USER',
    } } })
    await later()
    const cleanup = h.take('/auth/wechat/logout')
    assert.equal(cleanup.header['X-Miniapp-Session'], TOKEN)
    cleanup.outcome.resolve({ response: { statusCode: 200, data: { revoked: true } } })
    await Promise.all([login, logout])
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.equal(h.page.data.user, null)
    assert.equal(h.page.data.authPhase, 'unauthenticated')
    assert.equal(h.requests.length, 0)
  } finally { h.restoreGlobals() }
})

test('logout before wx.login returns never sends a code to the Gateway', async () => {
  const h = await controlledPage()
  try {
    h.page.setData({ offline: false })
    const login = (h.page.login as () => Promise<void>).call(h.page)
    const logout = (h.page.logout as () => Promise<void>).call(h.page)
    h.logins[0].resolve({ code: 'cancelled-code' })
    await Promise.all([login, logout])
    assert.equal(h.requests.length, 0)
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.equal(h.page.data.user, null)
    assert.equal(h.page.data.authPhase, 'unauthenticated')
  } finally { h.restoreGlobals() }
})

test('cancelled login reports an unconfirmed revocation when its cleanup request fails', async () => {
  const h = await controlledPage()
  try {
    h.page.setData({ offline: false })
    const login = (h.page.login as () => Promise<void>).call(h.page)
    h.logins[0].resolve({ code: 'one-time-code' })
    await later()
    const issuedLogin = h.take('/auth/wechat/login')
    const logout = (h.page.logout as () => Promise<void>).call(h.page)
    issuedLogin.outcome.resolve({ response: { statusCode: 200, data: {
      token: TOKEN, userId: 'cancelled-user', displayName: '取消用户', systemRole: 'USER',
    } } })
    await later()
    h.fail('/auth/wechat/logout')
    await Promise.all([login, logout])
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.equal(h.page.data.user, null)
    assert.match(String(h.page.data.error), /服务端会话撤销未得到确认/)
  } finally { h.restoreGlobals() }
})

test('a 403 from current-user lookup keeps the session restricted until retry verifies the user', async () => {
  const h = await controlledPage(TOKEN)
  try {
    h.page.setData({ offline: false, user: { userId: 'stale-user' },
      identity: { memberships: [{ id: 'stale-membership' }], applications: [] } })
    const restricted = (h.page.restoreSession as () => Promise<void>).call(h.page)
    h.respond('/auth/wechat/me', 403)
    await restricted
    assert.equal(h.page.data.authPhase, 'restricted')
    assert.equal(h.page.data.sessionRetryAvailable, true)
    assert.equal(h.page.data.user, null)
    assert.equal(h.page.data.identity, null)
    assert.equal(h.storage.get('animallink.miniapp.user-session'), TOKEN)
    assert.match(String(h.page.data.error), /身份查询被拒绝/)
    await (h.page.login as () => Promise<void>).call(h.page)
    assert.equal(h.logins.length, 0)
    assert.equal(h.requests.length, 0)

    const retry = (h.page.restoreSession as () => Promise<void>).call(h.page)
    h.respond('/auth/wechat/me', 403)
    await retry
    assert.equal(h.page.data.authPhase, 'restricted')
    assert.equal(h.page.data.sessionRetryAvailable, true)
    assert.equal(h.storage.get('animallink.miniapp.user-session'), TOKEN)

    const recovered = (h.page.restoreSession as () => Promise<void>).call(h.page)
    h.respond('/auth/wechat/me', 200, { userId: 'verified-user', displayName: '已验证用户', systemRole: 'USER' })
    await later()
    h.respond('/auth/wechat/campus-identities', 200, { memberships: [{ id: 'verified-membership' }], applications: [] })
    await later()
    h.respond('/identity/api/v1/campuses', 200, [])
    await recovered
    assert.equal(h.page.data.authPhase, 'authenticated')
    assert.equal(h.page.data.sessionRetryAvailable, false)
    assert.equal((h.page.data.user as unknown as { userId: string }).userId, 'verified-user')
    assert.deepEqual(h.page.data.identity, { memberships: [{ id: 'verified-membership' }], applications: [] })
  } finally { h.restoreGlobals() }
})

test('a restricted session can be actively revoked and a 401 still clears its expired token', async () => {
  const h = await controlledPage(TOKEN)
  try {
    h.page.setData({ offline: false })
    const restricted = (h.page.restoreSession as () => Promise<void>).call(h.page)
    h.respond('/auth/wechat/me', 403)
    await restricted
    const logout = (h.page.logout as () => Promise<void>).call(h.page)
    const revocation = h.take('/auth/wechat/logout')
    assert.equal(revocation.header['X-Miniapp-Session'], TOKEN)
    revocation.outcome.resolve({ response: { statusCode: 200, data: { revoked: true } } })
    await logout
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.equal(h.page.data.authPhase, 'unauthenticated')
    assert.equal(h.page.data.user, null)
    assert.equal(h.page.data.identity, null)
    assert.equal(h.page.data.sessionRetryAvailable, false)

    h.storage.set('animallink.miniapp.user-session', TOKEN)
    const expired = (h.page.restoreSession as () => Promise<void>).call(h.page)
    h.respond('/auth/wechat/me', 401)
    await expired
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.equal(h.page.data.authPhase, 'unauthenticated')
    assert.equal(h.page.data.sessionRetryAvailable, false)
  } finally { h.restoreGlobals() }
})

test('restricted My state exposes both recovery actions without an ordinary login action', async () => {
  const wxml = await readFile('pages/my/index.wxml', 'utf8')
  assert.match(wxml, /authPhase === 'restricted'/)
  assert.match(wxml, /sessionRetryAvailable && !loading[^>]*bindtap="restoreSession"/)
  assert.match(wxml, /sessionRetryAvailable && !loading[^>]*bindtap="logout"/)
  assert.match(wxml, /!sessionRetryAvailable && authPhase === 'unauthenticated'[^>]*bindtap="login"/)
})

test('My page never displays a user when local token storage fails after issuance', async () => {
  for (const revocationConfirmed of [true, false]) {
    const h = await controlledPage()
    try {
      h.page.setData({ offline: false })
      const mock = (globalThis as unknown as { wx: { setStorageSync: () => void } }).wx
      mock.setStorageSync = () => { throw new Error('storage unavailable') }
      const login = (h.page.login as () => Promise<void>).call(h.page)
      h.logins[0].resolve({ code: 'one-time-code' })
      await later()
      h.respond('/auth/wechat/login', 200, {
        token: TOKEN, userId: 'issued-user', displayName: '签发用户', systemRole: 'USER',
      })
      await later()
      const revoke = h.take('/auth/wechat/logout')
      assert.equal(revoke.header['X-Miniapp-Session'], TOKEN)
      if (revocationConfirmed) revoke.outcome.resolve({ response: { statusCode: 200, data: { revoked: true } } })
      else revoke.outcome.resolve({ failure: { errMsg: 'request:fail timeout' } })
      await login
      assert.equal(h.page.data.authPhase, 'unauthenticated')
      assert.equal(h.page.data.user, null)
      assert.equal(h.page.data.identity, null)
      assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
      assert.match(String(h.page.data.error), revocationConfirmed ? /新会话已撤销/ : /服务端 Session 撤销未确认/)
      assert.doesNotMatch(String(h.page.data.error), new RegExp(TOKEN))
    } finally { h.restoreGlobals() }
  }
})

test('500 and unexpected current-user errors keep the session recoverable without accepting another login', async () => {
  for (const statusCode of [500, 418]) {
    const h = await controlledPage(TOKEN)
    try {
      h.page.setData({ offline: false, user: { userId: 'stale-user' },
        identity: { memberships: [{ id: 'stale-membership' }], applications: [] } })
      const restore = (h.page.restoreSession as () => Promise<void>).call(h.page)
      h.respond('/auth/wechat/me', statusCode)
      await restore
      assert.equal(h.page.data.authPhase, 'sessionUnverified')
      assert.equal(h.page.data.sessionRetryAvailable, true)
      assert.equal(h.page.data.user, null)
      assert.equal(h.page.data.identity, null)
      assert.equal(h.storage.get('animallink.miniapp.user-session'), TOKEN)
      assert.match(String(h.page.data.error), new RegExp(String(statusCode)))
      await (h.page.login as () => Promise<void>).call(h.page)
      assert.equal(h.logins.length, 0)
      assert.equal(h.requests.length, 0)

      const retry = (h.page.restoreSession as () => Promise<void>).call(h.page)
      h.respond('/auth/wechat/me', statusCode)
      await retry
      assert.equal(h.page.data.authPhase, 'sessionUnverified')
      assert.equal(h.page.data.sessionRetryAvailable, true)
      assert.equal(h.storage.get('animallink.miniapp.user-session'), TOKEN)

      const recovered = (h.page.restoreSession as () => Promise<void>).call(h.page)
      h.respond('/auth/wechat/me', 200, { userId: 'verified-user', displayName: '已验证用户', systemRole: 'USER' })
      await later()
      h.respond('/auth/wechat/campus-identities', 200, { memberships: [{ id: 'verified-membership' }], applications: [] })
      await later()
      h.respond('/identity/api/v1/campuses', 200, [])
      await recovered
      assert.equal(h.page.data.authPhase, 'authenticated')
      assert.equal((h.page.data.user as unknown as { userId: string }).userId, 'verified-user')
      assert.deepEqual(h.page.data.identity, { memberships: [{ id: 'verified-membership' }], applications: [] })
      assert.equal(h.page.data.sessionRetryAvailable, false)
    } finally { h.restoreGlobals() }
  }
})

test('500, 503 and timeout remain retryable, while 401 clears the token', async () => {
  const h = await controlledPage(TOKEN)
  try {
    h.page.setData({ offline: false })
    for (const statusCode of [500, 503]) {
      const restore = (h.page.restoreSession as () => Promise<void>).call(h.page)
      h.respond('/auth/wechat/me', statusCode)
      await restore
      assert.equal(h.page.data.authPhase, 'sessionUnverified')
      assert.equal(h.page.data.sessionRetryAvailable, true)
      assert.equal(h.storage.get('animallink.miniapp.user-session'), TOKEN)
    }
    const timeout = (h.page.restoreSession as () => Promise<void>).call(h.page)
    h.fail('/auth/wechat/me')
    await timeout
    assert.equal(h.page.data.authPhase, 'sessionUnverified')
    assert.equal(h.page.data.sessionRetryAvailable, true)
    assert.equal(h.storage.get('animallink.miniapp.user-session'), TOKEN)
    const expired = (h.page.restoreSession as () => Promise<void>).call(h.page)
    h.respond('/auth/wechat/me', 401)
    await expired
    assert.equal(h.page.data.authPhase, 'unauthenticated')
    assert.equal(h.page.data.sessionRetryAvailable, false)
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
  } finally { h.restoreGlobals() }
})

test('an unverified 500 session can be actively logged out without reviving old identity', async () => {
  const h = await controlledPage(TOKEN)
  try {
    h.page.setData({ offline: false })
    const restore = (h.page.restoreSession as () => Promise<void>).call(h.page)
    h.respond('/auth/wechat/me', 500)
    await restore
    const logout = (h.page.logout as () => Promise<void>).call(h.page)
    const revoke = h.take('/auth/wechat/logout')
    assert.equal(revoke.header['X-Miniapp-Session'], TOKEN)
    revoke.outcome.resolve({ response: { statusCode: 200, data: { revoked: true } } })
    await logout
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.equal(h.page.data.user, null)
    assert.equal(h.page.data.identity, null)
    assert.equal(h.page.data.authPhase, 'unauthenticated')
  } finally { h.restoreGlobals() }
})

test('a signed session is revoked when the second storage read fails, without displaying a user', async () => {
  const h = await controlledPage()
  try {
    h.page.setData({ offline: false })
    const mock = (globalThis as unknown as { wx: { getStorageSync: (key: string) => string | undefined } }).wx
    let reads = 0
    mock.getStorageSync = key => {
      reads += 1
      if (reads === 2) throw new Error('storage read unavailable')
      return h.storage.get(key)
    }
    const login = (h.page.login as () => Promise<void>).call(h.page)
    h.logins[0].resolve({ code: 'one-time-code' })
    await later()
    const request = h.take('/auth/wechat/login')
    assert.equal(request.header['X-Miniapp-Session'], undefined)
    request.outcome.resolve({ response: { statusCode: 200, data: {
      token: TOKEN, userId: 'issued-user', displayName: '签发用户', systemRole: 'USER',
    } } })
    await later()
    const revoke = h.take('/auth/wechat/logout')
    assert.equal(revoke.header['X-Miniapp-Session'], TOKEN)
    revoke.outcome.resolve({ response: { statusCode: 200, data: { revoked: true } } })
    await login
    assert.equal(h.page.data.authPhase, 'unauthenticated')
    assert.equal(h.page.data.user, null)
    assert.equal(h.page.data.identity, null)
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.match(String(h.page.data.error), /新会话已撤销/)
  } finally { h.restoreGlobals() }
})

test('unverified session actions remain visible separately from ordinary login', async () => {
  const wxml = await readFile('pages/my/index.wxml', 'utf8')
  assert.match(wxml, /authPhase === 'sessionUnverified'/)
  assert.match(wxml, /sessionRetryAvailable && !loading[^>]*bindtap="restoreSession"/)
  assert.match(wxml, /sessionRetryAvailable && !loading[^>]*bindtap="logout"/)
  assert.match(wxml, /!sessionRetryAvailable && authPhase === 'unauthenticated'[^>]*bindtap="login"/)
})

test('persistent storage read errors still show the compensated login failure without a verified user', async () => {
  const h = await controlledPage()
  try {
    h.page.setData({ offline: false })
    const mock = (globalThis as unknown as { wx: { getStorageSync: (key: string) => string | undefined } }).wx
    let reads = 0
    mock.getStorageSync = key => {
      reads += 1
      if (reads > 1) throw new Error('storage read unavailable')
      return h.storage.get(key)
    }
    const login = (h.page.login as () => Promise<void>).call(h.page)
    h.logins[0].resolve({ code: 'one-time-code' })
    await later()
    h.respond('/auth/wechat/login', 200, {
      token: TOKEN, userId: 'issued-user', displayName: '签发用户', systemRole: 'USER',
    })
    await later()
    const revoke = h.take('/auth/wechat/logout')
    assert.equal(revoke.header['X-Miniapp-Session'], TOKEN)
    revoke.outcome.resolve({ response: { statusCode: 200, data: { revoked: true } } })
    await login
    assert.equal(h.page.data.user, null)
    assert.equal(h.page.data.identity, null)
    assert.equal(h.page.data.authPhase, 'sessionUnverified')
    assert.equal(h.page.data.sessionRetryAvailable, true)
    assert.match(String(h.page.data.error), /新会话已撤销，但本地清理未确认/)

    const logout = (h.page.logout as () => Promise<void>).call(h.page)
    await logout
    assert.equal(h.page.data.authPhase, 'sessionUnverified')
    assert.equal(h.page.data.user, null)
    assert.match(String(h.page.data.error), /本机登录信息清理未确认/)
  } finally { h.restoreGlobals() }
})

test('campus identity 401 clears the current session and permits a fresh login', async () => {
  const h = await controlledPage(TOKEN)
  try {
    h.page.setData({ offline: false, authPhase: 'authenticated', user: { userId: 'old-user' },
      identity: { memberships: [{ id: 'old-membership' }], applications: [{ id: 'old-application' }] },
      campuses: [{ id: 'old-campus', name: '旧校园' }], showIdentityForm: true,
      form: { applicantName: '旧用户', affiliationNote: '', expectedGraduationDate: '' } })
    const expired = (h.page.retryCampusIdentity as () => Promise<void>).call(h.page)
    const request = h.take('/auth/wechat/campus-identities')
    assert.equal(request.header['X-Miniapp-Session'], TOKEN)
    request.outcome.resolve({ response: { statusCode: 401, data: {} } })
    await expired
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.equal(h.page.data.user, null)
    assert.equal(h.page.data.identity, null)
    assert.deepEqual(h.page.data.campuses, [])
    assert.equal((h.page.data.form as { applicantName: string }).applicantName, '')
    assert.equal(h.page.data.showIdentityForm, false)
    assert.equal(h.page.data.authPhase, 'unauthenticated')
    assert.equal(h.page.data.sessionRetryAvailable, false)
    const wxml = await readFile('pages/my/index.wxml', 'utf8')
    assert.match(wxml, /!sessionRetryAvailable && authPhase === 'unauthenticated'[^>]*bindtap="login"/)

    const login = (h.page.login as () => Promise<void>).call(h.page)
    assert.equal(h.logins.length, 1)
    h.logins[0].resolve({ code: 'fresh-code' })
    await later()
    h.respond('/auth/wechat/login', 200, { token: 'b'.repeat(43), userId: 'new-user', displayName: '新用户', systemRole: 'USER' })
    await later()
    h.respond('/auth/wechat/campus-identities', 200, { memberships: [{ id: 'new-membership' }], applications: [] })
    await later()
    h.respond('/identity/api/v1/campuses', 200, [])
    await login
    assert.equal(h.storage.get('animallink.miniapp.user-session'), 'b'.repeat(43))
    assert.equal((h.page.data.user as unknown as { userId: string }).userId, 'new-user')
    assert.deepEqual(h.page.data.identity, { memberships: [{ id: 'new-membership' }], applications: [] })
    assert.equal(h.page.data.authPhase, 'authenticated')
  } finally { h.restoreGlobals() }
})

test('campus application 401 clears the current session and permits a fresh login', async () => {
  const h = await controlledPage(TOKEN)
  try {
    h.page.setData({ offline: false, authPhase: 'authenticated', user: { userId: 'old-user' },
      identity: { memberships: [{ id: 'old-membership' }], applications: [] },
      campuses: [{ id: 'old-campus', name: '旧校园' }], showIdentityForm: true,
      form: { applicantName: '旧用户', affiliationNote: '', expectedGraduationDate: '' } })
    const expired = (h.page.submitIdentity as () => Promise<void>).call(h.page)
    const request = h.take('/auth/wechat/campus-identities/applications')
    assert.equal(request.header['X-Miniapp-Session'], TOKEN)
    request.outcome.resolve({ response: { statusCode: 401, data: {} } })
    await expired
    assert.equal(h.storage.has('animallink.miniapp.user-session'), false)
    assert.equal(h.page.data.user, null)
    assert.equal(h.page.data.identity, null)
    assert.deepEqual(h.page.data.campuses, [])
    assert.equal(h.page.data.showIdentityForm, false)
    assert.equal((h.page.data.form as { applicantName: string }).applicantName, '')
    assert.equal(h.page.data.authPhase, 'unauthenticated')
    assert.equal(h.page.data.sessionRetryAvailable, false)
    assert.equal(h.page.data.loading, false)

    const login = (h.page.login as () => Promise<void>).call(h.page)
    assert.equal(h.logins.length, 1)
    h.logins[0].resolve({ code: 'fresh-code' })
    await later()
    h.respond('/auth/wechat/login', 200, { token: 'b'.repeat(43), userId: 'new-user', displayName: '新用户', systemRole: 'USER' })
    await later()
    h.respond('/auth/wechat/campus-identities', 200, { memberships: [], applications: [{ id: 'new-application' }] })
    await later()
    h.respond('/identity/api/v1/campuses', 200, [])
    await login
    assert.equal(h.storage.get('animallink.miniapp.user-session'), 'b'.repeat(43))
    assert.equal((h.page.data.user as unknown as { userId: string }).userId, 'new-user')
    assert.deepEqual(h.page.data.identity, { memberships: [], applications: [{ id: 'new-application' }] })
    assert.equal(h.page.data.authPhase, 'authenticated')
  } finally { h.restoreGlobals() }
})

test('late campus identity and application 401 responses cannot clear a newer session', async () => {
  for (const application of [false, true]) {
    for (const advanceEpoch of [false, true]) {
      const h = await controlledPage(TOKEN)
      try {
        h.page.setData({ offline: false, authPhase: 'authenticated', user: { userId: 'old-user' },
          identity: { memberships: [], applications: [] },
          campuses: [{ id: 'old-campus', name: '旧校园' }],
          form: { applicantName: '旧用户', affiliationNote: '', expectedGraduationDate: '' } })
        const pending = application
          ? (h.page.submitIdentity as () => Promise<void>).call(h.page)
          : (h.page.retryCampusIdentity as () => Promise<void>).call(h.page)
        const request = h.take(application ? '/auth/wechat/campus-identities/applications' : '/auth/wechat/campus-identities')
        h.storage.set('animallink.miniapp.user-session', 'b'.repeat(43))
        h.page.setData({ sessionEpoch: Number(h.page.data.sessionEpoch) + Number(advanceEpoch), authPhase: 'authenticated',
          loading: false, user: { userId: advanceEpoch ? 'new-user' : 'old-user' },
          identity: { memberships: [{ id: 'new-membership' }], applications: [] } })
        request.outcome.resolve({ response: { statusCode: 401, data: {} } })
        await pending
        assert.equal(h.storage.get('animallink.miniapp.user-session'), 'b'.repeat(43))
        assert.equal((h.page.data.user as { userId: string }).userId, advanceEpoch ? 'new-user' : 'old-user')
        assert.deepEqual(h.page.data.identity, { memberships: [{ id: 'new-membership' }], applications: [] })
        assert.equal(h.page.data.authPhase, 'authenticated')
      } finally { h.restoreGlobals() }
    }
  }
})

test('campus identity 403, server errors and timeout retain the verified session', async () => {
  for (const outcome of [403, 500, 503, 'timeout'] as const) {
    const h = await controlledPage(TOKEN)
    try {
      h.page.setData({ offline: false, authPhase: 'authenticated', user: { userId: 'verified-user' },
        identity: { memberships: [], applications: [] } })
      const pending = (h.page.retryCampusIdentity as () => Promise<void>).call(h.page)
      if (outcome === 'timeout') h.fail('/auth/wechat/campus-identities')
      else h.respond('/auth/wechat/campus-identities', outcome)
      await pending
      assert.equal(h.storage.get('animallink.miniapp.user-session'), TOKEN)
      assert.equal((h.page.data.user as { userId: string }).userId, 'verified-user')
      assert.equal(h.page.data.authPhase, 'authenticated')
      assert.equal(h.page.data.identity, null)
      assert.ok(h.page.data.error)
    } finally { h.restoreGlobals() }
  }
})
