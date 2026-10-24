import { describe, expect, it } from 'vitest'
import type { RescueGovernanceDataSource } from './dataSource'
import type { RescueDetail, RescueSearchResult } from './types'
import { useRescueCatalog } from './useRescueCatalog'

const deferred = <T>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

const result = (eventId: string): RescueSearchResult => ({
  items: [{
    eventId, caseId: null, campusId: 'campus', animalId: null, rescueStatus: 'WAITING_CLAIM',
    eventStatus: 'VERIFIED', caseStatus: null, claimStatus: null, hasOwner: false,
    abnormalitySummary: eventId, description: eventId, publicLocationDescription: '公开位置',
    occurredAt: '2026-10-01T00:00:00Z', reportedAt: '2026-10-01T00:00:00Z',
    evidenceCount: 0, actionCount: 0, resultCode: null, resultSummary: null,
    updatedAt: '2026-10-01T00:00:00Z', animal: null,
  }],
  page: 1, size: 6, total: 1,
})

describe('useRescueCatalog', () => {
  it('keeps the newest search response when requests finish out of order', async () => {
    const first = deferred<RescueSearchResult>()
    const second = deferred<RescueSearchResult>()
    let calls = 0
    const dataSource: RescueGovernanceDataSource = {
      search: () => (++calls === 1 ? first.promise : second.promise),
      getDetail: async () => { throw new Error('unused') },
    }
    const catalog = useRescueCatalog(dataSource)
    const older = catalog.load()
    const newer = catalog.load()
    second.resolve(result('newer'))
    await newer
    first.resolve(result('older'))
    await older
    expect(catalog.items.value[0].eventId).toBe('newer')
    expect(catalog.loading.value).toBe(false)
  })

  it('keeps the newest detail response when drawers switch quickly', async () => {
    const first = deferred<RescueDetail>()
    const second = deferred<RescueDetail>()
    const base = result('newer').items[0]
    const dataSource: RescueGovernanceDataSource = {
      search: async () => result('list'),
      getDetail: (eventId) => eventId === 'older' ? first.promise : second.promise,
    }
    const catalog = useRescueCatalog(dataSource)
    const older = catalog.openDetail('older')
    const newer = catalog.openDetail('newer')
    second.resolve({ ...base, claimedAt: null, resultSubmittedAt: null, closedAt: null, progress: [] })
    await newer
    first.resolve({ ...base, eventId: 'older', claimedAt: null, resultSubmittedAt: null, closedAt: null, progress: [] })
    await older
    expect(catalog.detail.value?.eventId).toBe('newer')
  })

  it('resets pagination when filters change', async () => {
    const dataSource: RescueGovernanceDataSource = {
      search: async (criteria) => ({ ...result('item'), page: criteria.page, size: criteria.size }),
      getDetail: async () => { throw new Error('unused') },
    }
    const catalog = useRescueCatalog(dataSource)
    catalog.criteria.page = 3
    await catalog.load(true)
    expect(catalog.criteria.page).toBe(1)
  })
})
