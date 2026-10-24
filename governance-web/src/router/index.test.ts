import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const authentication = vi.hoisted(() => ({
  state: { status: 'unauthenticated' },
  isAuthenticated: { value: false },
  restore: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/composables/useAdminAuthentication', () => ({ useAdminAuthentication: () => authentication }))

import { createGovernanceRouter, safePostLoginRedirect } from './index'

describe('post-login redirect', () => {
  beforeEach(() => {
    authentication.state.status = 'unauthenticated'
    authentication.isAuthenticated.value = false
    authentication.restore.mockClear()
  })

  it.each([
    [undefined, '/'],
    ['https://evil.example', '/'],
    ['//evil.example/path', '/'],
    ['/\\evil.example', '/'],
    ['/login', '/'],
    ['/animals?campus=one#record', '/animals?campus=one#record'],
  ])('maps %s to %s', (candidate, expected) => {
    expect(safePostLoginRedirect(candidate)).toBe(expected)
  })

  it('redirects an unauthenticated governance route to login once session restoration finishes', async () => {
    const router = createGovernanceRouter(createMemoryHistory())
    await router.push('/animals?campus=one')
    expect(authentication.restore).toHaveBeenCalled()
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/animals?campus=one')
  })

  it('keeps the Rescue governance route behind the administrator session guard', async () => {
    const router = createGovernanceRouter(createMemoryHistory())
    await router.push('/incidents')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/incidents')
  })

  it('returns an authenticated administrator from login to the intended internal route', async () => {
    authentication.isAuthenticated.value = true
    authentication.state.status = 'authenticated'
    const router = createGovernanceRouter(createMemoryHistory())
    await router.push('/login?redirect=/audits')
    expect(router.currentRoute.value.name).toBe('audits')
  })

  it('routes a forbidden verified session to access denied', async () => {
    authentication.state.status = 'forbidden'
    const router = createGovernanceRouter(createMemoryHistory())
    await router.push('/support')
    expect(router.currentRoute.value.name).toBe('access-denied')
  })
})
