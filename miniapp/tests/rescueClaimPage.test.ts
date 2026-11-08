import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'

type PageDefinition = { data: Record<string, unknown>; [name: string]: unknown }
const campus = (id: string) => ({ id, name: id, shortName: null, city: 'city', region: null, status: 'ACTIVE' })

test('claim blocks duplicate clicks and ignores a result after campus changes', async () => {
  const global = globalThis as unknown as { wx?: Record<string, unknown>; Page?: (value: PageDefinition) => void }
  const oldWx = global.wx, oldPage = global.Page, oldMode = runtimeConfig.userAuthenticationMode, oldBase = runtimeConfig.gatewayBaseUrl
  const storage = new Map<string, unknown>([['animallink.miniapp.user-session', 'a'.repeat(43)], ['animallink.selectedCampus.v1', campus('campus-a')]])
  let reply: ((value: { statusCode: number; data: unknown }) => void) | undefined
  let calls = 0, definition: PageDefinition | undefined
  global.wx = { getStorageSync: (key: string) => storage.get(key), removeStorageSync: (key: string) => storage.delete(key), request: (options: { success: typeof reply }) => { calls++; reply = options.success } }
  global.Page = value => { definition = value }
  runtimeConfig.userAuthenticationMode = 'api'; runtimeConfig.gatewayBaseUrl = 'https://example.test'
  try {
    await import('../pages/rescue-detail/index')
    assert.ok(definition)
    const page = Object.assign({}, definition, { eventId: 'event', campusId: 'campus-a', detailRequestVersion: 0,
      data: { ...definition.data, offline: false, detail: { rescue: {} } },
      setData(update: Record<string, unknown>) { Object.assign(this.data, update) },
    }) as unknown as PageDefinition & { onClaim: () => Promise<void> }
    const first = page.onClaim(); await page.onClaim(); assert.equal(calls, 1)
    storage.set('animallink.selectedCampus.v1', campus('campus-b'))
    assert.ok(reply); reply({ statusCode: 200, data: { id: 'case', eventId: 'event', campusId: 'campus-a' } })
    await first
    assert.equal(page.data.claimedCase, null)
    assert.equal(page.data.claimMessage, '')
    await page.onClaim(); assert.equal(calls, 1)
    assert.match(String(page.data.claimMessage), /高校已切换/)
  } finally { global.wx = oldWx; global.Page = oldPage; runtimeConfig.userAuthenticationMode = oldMode; runtimeConfig.gatewayBaseUrl = oldBase }
})
