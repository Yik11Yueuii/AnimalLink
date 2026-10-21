import { beforeEach, describe, expect, it, vi } from 'vitest'
import { gatewayClient } from './client'
import {
  AuthenticationApiError,
  loginAdmin,
  logoutAdmin,
  resetAuthenticationApiForTests,
  restoreAdminSession,
} from './authentication'

const session = {
  sessionId: 'session-1',
  userId: 'user-1',
  systemRole: 'GOVERNANCE_ADMIN',
  idleExpiresAt: '2099-01-01T00:30:00Z',
  absoluteExpiresAt: '2099-01-01T08:00:00Z',
} as const

describe('administrator authentication API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    resetAuthenticationApiForTests()
  })

  it('gets CSRF once and sends the server-provided header for login and logout', async () => {
    const get = vi.spyOn(gatewayClient, 'get')
      .mockResolvedValueOnce({ data: { token: 'server-bound-csrf-token', headerName: 'X-SERVER-CSRF' } })
    const post = vi.spyOn(gatewayClient, 'post')
      .mockResolvedValueOnce({ data: session })
      .mockResolvedValueOnce({ data: undefined })

    await loginAdmin('admin.root', 'secret password')
    await logoutAdmin()

    expect(get).toHaveBeenCalledTimes(1)
    expect(post).toHaveBeenNthCalledWith(1, '/auth/admin/login', {
      username: 'admin.root', password: 'secret password',
    }, { headers: { 'X-SERVER-CSRF': 'server-bound-csrf-token' } })
    expect(post).toHaveBeenNthCalledWith(2, '/auth/admin/logout', undefined, {
      headers: { 'X-SERVER-CSRF': 'server-bound-csrf-token' },
    })
    expect(gatewayClient.defaults.withCredentials).toBe(true)
  })

  it.each([
    [401, 'unauthenticated'],
    [403, 'forbidden'],
    [429, 'rate-limited'],
    [503, 'unavailable'],
  ] as const)('preserves HTTP %s authentication semantics', async (status, kind) => {
    vi.spyOn(gatewayClient, 'get').mockRejectedValue({ isAxiosError: true, response: { status } })
    await expect(restoreAdminSession()).rejects.toMatchObject<Partial<AuthenticationApiError>>({ kind, status })
  })

  it('rejects an untrusted session response', async () => {
    vi.spyOn(gatewayClient, 'get').mockResolvedValue({ data: { ...session, systemRole: 'USER' } })
    await expect(restoreAdminSession()).rejects.toMatchObject({ kind: 'invalid-response' })
  })
})
