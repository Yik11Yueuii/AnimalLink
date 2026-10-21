import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthenticationApiError } from '@/api/authentication'

const api = vi.hoisted(() => ({
  loginAdmin: vi.fn(),
  logoutAdmin: vi.fn(),
  restoreAdminSession: vi.fn(),
}))

vi.mock('@/api/authentication', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/authentication')>()
  return { ...original, ...api }
})

import { resetAdminAuthenticationForTests, useAdminAuthentication } from './useAdminAuthentication'

const session = (id: string) => ({
  sessionId: id,
  userId: `user-${id}`,
  systemRole: 'GOVERNANCE_ADMIN' as const,
  idleExpiresAt: '2099-01-01T00:30:00Z',
  absoluteExpiresAt: '2099-01-01T08:00:00Z',
})

describe('administrator authentication state', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetAdminAuthenticationForTests()
  })

  afterEach(() => vi.useRealTimers())

  it('deduplicates startup restoration and restores a verified session', async () => {
    api.restoreAdminSession.mockResolvedValue(session('restored'))
    const authentication = useAdminAuthentication()

    await Promise.all([authentication.restore(), authentication.restore()])

    expect(api.restoreAdminSession).toHaveBeenCalledTimes(1)
    expect(authentication.state.status).toBe('authenticated')
    expect(authentication.state.session?.sessionId).toBe('restored')
  })

  it.each([
    ['unauthenticated', 401, 'unauthenticated'],
    ['forbidden', 403, 'forbidden'],
    ['rate-limited', 429, 'rate-limited'],
    ['unavailable', 503, 'unavailable'],
  ] as const)('keeps %s distinct', async (kind, status, expected) => {
    api.loginAdmin.mockRejectedValue(new AuthenticationApiError(kind, status))
    const authentication = useAdminAuthentication()

    await expect(authentication.login('admin', 'password')).rejects.toBeInstanceOf(AuthenticationApiError)
    expect(authentication.state.status).toBe(expected)
  })

  it('does not let an older restore response overwrite a newer login', async () => {
    let resolveRestore!: (value: ReturnType<typeof session>) => void
    api.restoreAdminSession.mockReturnValue(new Promise((resolve) => { resolveRestore = resolve }))
    api.loginAdmin.mockResolvedValue(session('new-login'))
    const authentication = useAdminAuthentication()

    const restoring = authentication.restore()
    await authentication.login('admin', 'password')
    resolveRestore(session('old-restore'))
    await restoring

    expect(authentication.state.session?.sessionId).toBe('new-login')
  })

  it('clears local identity only after server logout succeeds', async () => {
    api.loginAdmin.mockResolvedValue(session('active'))
    api.logoutAdmin.mockRejectedValue(new AuthenticationApiError('unavailable', 503))
    const authentication = useAdminAuthentication()
    await authentication.login('admin', 'password')

    await expect(authentication.logout()).rejects.toBeInstanceOf(AuthenticationApiError)
    expect(authentication.state.status).toBe('unavailable')
    expect(authentication.state.session).toBeNull()

    api.logoutAdmin.mockResolvedValue(undefined)
    await authentication.logout()
    expect(authentication.state.status).toBe('unauthenticated')
  })

  it('expires local state at the earliest server session deadline', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-21T00:00:00Z'))
    api.loginAdmin.mockResolvedValue({
      ...session('expiring'),
      idleExpiresAt: '2026-10-21T00:00:01Z',
      absoluteExpiresAt: '2026-10-21T08:00:00Z',
    })
    const authentication = useAdminAuthentication()
    await authentication.login('admin', 'password')

    vi.advanceTimersByTime(1_000)

    expect(authentication.state.status).toBe('unauthenticated')
    expect(authentication.state.expired).toBe(true)
    expect(authentication.state.session).toBeNull()
  })
})
