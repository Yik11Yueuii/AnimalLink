import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'

type Definition = { data: Record<string, unknown>; [key: string]: unknown }
const campus = (id: string) => ({ id, name: id, shortName: null, city: 'city', region: null, status: 'ACTIVE' })
test('case actions reject duplicate submission and discard an old account response', async () => {
  const global = globalThis as unknown as { wx?: Record<string, unknown>; Page?: (v: Definition) => void }
  const oldWx = global.wx, oldPage = global.Page, oldMode = runtimeConfig.userAuthenticationMode, oldBase = runtimeConfig.gatewayBaseUrl
  const storage = new Map<string, unknown>([['animallink.miniapp.user-session', 'a'.repeat(43)], ['animallink.selectedCampus.v1', campus('c')]])
  let definition: Definition | undefined, calls = 0, reply: ((v: { statusCode: number; data: unknown }) => void) | undefined
  global.wx = { getStorageSync: (key: string) => storage.get(key), removeStorageSync: (key: string) => storage.delete(key), request: (options: { success: typeof reply }) => { calls++; reply = options.success } }
  global.Page = value => { definition = value }; runtimeConfig.userAuthenticationMode = 'api'; runtimeConfig.gatewayBaseUrl = 'https://example.test'
  try {
    await import('../pages/case-actions/index'); assert.ok(definition)
    const page = Object.assign({}, definition, { caseId: 'case', campusId: 'c', session: 'a'.repeat(43), generation: 0,
      data: { ...definition.data, status: 'ACTIVE', description: 'help', date: '2026-10-03', time: '10:00' },
      setData(value: Record<string, unknown>) { Object.assign(this.data, value) },
    }) as unknown as Definition & { onSubmit: () => Promise<void> }
    const first = page.onSubmit(); await page.onSubmit(); assert.equal(calls, 1)
    storage.set('animallink.miniapp.user-session', 'b'.repeat(43)); assert.ok(reply); reply({ statusCode: 200, data: { id: 'action' } }); await first
    assert.deepEqual(page.data.items, []); assert.equal(page.data.description, ''); assert.equal(page.data.message, '')
  } finally { global.wx = oldWx; global.Page = oldPage; runtimeConfig.userAuthenticationMode = oldMode; runtimeConfig.gatewayBaseUrl = oldBase }
})
