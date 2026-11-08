import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'

type Definition = { data: Record<string, unknown>; [key: string]: unknown }
test('claim success exposes the history entry while server authorization remains required', async () => {
  const global = globalThis as unknown as { wx?: Record<string, unknown>; Page?: (value: Definition) => void }
  const oldWx = global.wx, oldPage = global.Page, oldMode = runtimeConfig.userAuthenticationMode, oldBase = runtimeConfig.gatewayBaseUrl
  const storage = new Map<string, unknown>([['animallink.miniapp.user-session', 'a'.repeat(43)], ['animallink.selectedCampus.v1', { id: 'campus', name: 'campus', shortName: null, city: 'city', region: null, status: 'ACTIVE' }]])
  let definition: Definition | undefined, accessStatus = 403, navigated = false, accessCalls = 0
  global.wx = {
    getStorageSync: (key: string) => storage.get(key),
    request: (options: { url: string; success: (response: { statusCode: number; data: unknown }) => void }) => {
      if (options.url.includes('/claim')) options.success({ statusCode: 200, data: { id: 'case', eventId: 'event', campusId: 'campus', status: 'ACTIVE' } })
      else { accessCalls++; options.success({ statusCode: accessStatus, data: { id: 'case', eventId: 'event', campusId: 'campus', status: 'ACTIVE' } }) }
    },
    navigateTo: () => { navigated = true },
  }
  global.Page = value => { definition = value }; runtimeConfig.userAuthenticationMode = 'api'; runtimeConfig.gatewayBaseUrl = 'https://example.test'
  try {
    await import('../pages/rescue-detail/index'); assert.ok(definition)
    const page = Object.assign({}, definition, { eventId: 'event', campusId: 'campus', detailRequestVersion: 0,
      data: { ...definition.data, offline: false, detail: { rescue: { rescueStatus: 'WAITING_CLAIM' } } },
      setData(update: Record<string, unknown>) { Object.assign(this.data, update) },
    }) as unknown as Definition & { onClaim: () => Promise<void>; openCaseActions: () => Promise<void> }
    const template = await readFile('pages/rescue-detail/index.wxml', 'utf8')
    const expression = /wx:if="{{([^}]+)}}" class="primary-button" bindtap="openCaseActions"/.exec(template)?.[1]
    assert.ok(expression)
    const visible = new Function('claimedCase', 'detail', `return !!(${expression})`) as (claim: unknown, detail: unknown) => boolean
    assert.equal(visible(page.data.claimedCase, page.data.detail), false)
    await page.onClaim()
    assert.equal(visible(page.data.claimedCase, page.data.detail), true)
    await page.openCaseActions(); assert.equal(accessCalls, 1); assert.equal(navigated, false)
    accessStatus = 200; await page.openCaseActions(); assert.equal(accessCalls, 2); assert.equal(navigated, true)
  } finally { global.wx = oldWx; global.Page = oldPage; runtimeConfig.userAuthenticationMode = oldMode; runtimeConfig.gatewayBaseUrl = oldBase }
})
