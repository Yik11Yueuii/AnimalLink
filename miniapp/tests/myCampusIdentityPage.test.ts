import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { runtimeConfig } from '../config/runtime'

type PageDefinition = { data: Record<string, unknown>; [name: string]: unknown }

const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

test('a late campus-identity response cannot restore a logged-out or switched users identity', async () => {
  let definition: PageDefinition | undefined
  ;(globalThis as unknown as { Page: (options: PageDefinition) => void }).Page = options => { definition = options }
  await import('../pages/my/index')
  assert.ok(definition)

  const page = Object.assign({
    data: copy(definition.data),
    setData(update: Record<string, unknown>) { Object.assign(this.data, update) },
  }, definition) as PageDefinition & { setData: (update: Record<string, unknown>) => void }
  const pending: Array<{ url: string; success: (response: { statusCode: number; data: unknown }) => void }> = []
  const storage = new Map<string, string>([['animallink.miniapp.user-session', 'a'.repeat(43)]])
  const previousMode = runtimeConfig.userAuthenticationMode
  const previousBaseUrl = runtimeConfig.gatewayBaseUrl
  ;(globalThis as unknown as { wx: Record<string, unknown> }).wx = {
    getStorageSync: (key: string) => storage.get(key),
    removeStorageSync: (key: string) => storage.delete(key),
    request: (options: { url: string; success: (response: { statusCode: number; data: unknown }) => void }) => pending.push(options),
  }
  try {
    runtimeConfig.userAuthenticationMode = 'api'
    runtimeConfig.gatewayBaseUrl = 'https://localhost:8443'
    page.setData({ sessionEpoch: 1, user: { userId: 'old-user', displayName: '旧用户' }, identity: null, campuses: [] })
    const lateLoad = (page.loadCampusIdentity as (epoch: number, userId: string) => Promise<void>).call(page, 1, 'old-user')
    page.setData({ sessionEpoch: 2, user: { userId: 'new-user', displayName: '新用户' } })
    for (const request of pending) {
      request.success({ statusCode: 200, data: request.url.endsWith('/campus-identities')
        ? { memberships: [{ id: 'old-membership' }], applications: [] }
        : [{ id: 'campus-1', name: '旧校园', status: 'ACTIVE' }] })
    }
    await lateLoad
    assert.equal((page.data.user as { userId: string }).userId, 'new-user')
    assert.equal(page.data.identity, null)
    assert.deepEqual(page.data.campuses, [])
  } finally {
    runtimeConfig.userAuthenticationMode = previousMode
    runtimeConfig.gatewayBaseUrl = previousBaseUrl
  }
})

test('My page renders memberships and applications independently, with inactive memberships not styled as active', async () => {
  const wxml = await readFile('pages/my/index.wxml', 'utf8')
  assert.match(wxml, /wx:if="\{\{identity\.memberships\.length\}\}"/)
  assert.match(wxml, /wx:if="\{\{identity\.applications\.length\}\}"/)
  assert.doesNotMatch(wxml, /wx:elif="\{\{identity\.applications\.length\}\}"/)
  assert.match(wxml, /!identity\.memberships\.length && !identity\.applications\.length/)
  assert.match(wxml, /item\.status === 'ACTIVE' \? 'status-active' : 'status-inactive'/)
  assert.match(wxml, /item\.status === 'ACTIVE' \? '有效成员身份' : item\.status/)
})
