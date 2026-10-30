import assert from 'node:assert/strict'
import test from 'node:test'
import { ApiRescueDataSource } from '../services/rescueDataSource'
import { OfflineRescueDataSource } from '../services/offlineRescueDataSource'
import { RescueCatalog } from '../state/rescueCatalog'
import type { JsonRequester, QueryValue } from '../services/apiClient'
import type { PublicRescueItem, PublicRescuePage } from '../types/rescue'

const item = (eventId: string, campusId: string): PublicRescueItem => ({
  eventId, campusId, rescueStatus: 'WAITING_CLAIM', occurredAt: '2026-10-01T00:00:00Z', reportedAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z', publicSummary: '已核查。', animal: null,
})

test('rescue API adapter uses the precise public paths and zero-based pagination', async () => {
  const calls: Array<{ path: string; query?: Record<string, QueryValue> }> = []
  const request: JsonRequester = async <T>(path: string, query?: Record<string, QueryValue>) => {
    calls.push({ path, query })
    return (path.endsWith('/rescues') ? { items: [], page: 0, size: 3, total: 0 } : { rescue: item('event-1', 'campus-1'), progress: [] }) as T
  }
  const source = new ApiRescueDataSource(request)
  await source.search('campus/a', 'IN_PROGRESS', 0, 3)
  await source.getDetail('campus/a', 'event/a')
  assert.deepEqual(calls, [
    { path: '/incident/api/v1/public/rescues', query: { campusId: 'campus/a', status: 'IN_PROGRESS', page: 0, size: 3 } },
    { path: '/incident/api/v1/public/rescues/event%2Fa', query: { campusId: 'campus/a' } },
  ])
})

test('rescue catalog isolates a campus switch from an older in-flight response', async () => {
  let resolveFirst: ((value: PublicRescuePage) => void) | undefined
  const source = {
    search: (campusId: string) => campusId === 'campus-a'
      ? new Promise<PublicRescuePage>((resolve) => { resolveFirst = resolve })
      : Promise.resolve({ items: [item('event-b', 'campus-b')], page: 0, size: 3, total: 1 }),
    getDetail: async () => { throw new Error('unused') },
  }
  const catalog = new RescueCatalog(source)
  catalog.selectCampus('campus-a')
  const first = catalog.refresh()
  catalog.selectCampus('campus-b')
  await catalog.refresh()
  resolveFirst?.({ items: [item('event-a', 'campus-a')], page: 0, size: 3, total: 1 })
  await first
  assert.deepEqual(catalog.snapshot.items.map((entry) => entry.eventId), ['event-b'])
})

test('offline rescue data is explicitly scoped to Hanshan and supports status filtering', async () => {
  const source = new OfflineRescueDataSource()
  const all = await source.search('22000000-0000-4000-8000-000000000001', '', 0, 20)
  const active = await source.search('22000000-0000-4000-8000-000000000001', 'IN_PROGRESS', 0, 20)
  assert.equal(all.total, 3)
  assert.equal(active.total, 1)
  assert.ok(all.items.every((entry) => entry.publicSummary.startsWith('【虚构演示】')))
  assert.equal((await source.search('campus-other', '', 0, 20)).total, 0)
  await assert.rejects(source.getDetail('campus-other', all.items[0].eventId))
})
