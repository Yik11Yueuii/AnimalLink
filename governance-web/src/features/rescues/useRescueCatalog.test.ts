import { describe, expect, it } from 'vitest'
import { AuthenticationApiError } from '@/api/authentication'
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
      verify: async () => { throw new Error('unused') },
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
      verify: async () => { throw new Error('unused') },
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
      verify: async () => { throw new Error('unused') },
    }
    const catalog = useRescueCatalog(dataSource)
    catalog.criteria.page = 3
    await catalog.load(true)
    expect(catalog.criteria.page).toBe(1)
  })
  it('refreshes the list and detail after verification and blocks another submission', async () => {
    const reported = { ...result('reported').items[0], rescueStatus: 'REPORTED' as const, eventStatus: 'REPORTED' as const }
    const verified = { ...reported, rescueStatus: 'WAITING_CLAIM' as const, eventStatus: 'VERIFIED' as const }
    const detail = { ...reported, claimedAt: null, resultSubmittedAt: null, closedAt: null, progress: [] }
    let verifyCalls = 0
    const dataSource: RescueGovernanceDataSource = {
      search: async () => ({ ...result('reported'), items: [verified] }),
      getDetail: async () => detail,
      verify: async () => { verifyCalls++; return { ...detail, ...verified } },
    }
    const catalog = useRescueCatalog(dataSource)
    await catalog.openDetail('reported')
    await catalog.verify()
    await catalog.verify()
    expect(verifyCalls).toBe(1)
    expect(catalog.detail.value?.eventStatus).toBe('VERIFIED')
    expect(catalog.items.value[0].rescueStatus).toBe('WAITING_CLAIM')
    expect(catalog.verifyMessage.value).toContain('已核实')
  })
  it('refreshes after a conflicting verification and reports the changed state', async () => {
    const reported = { ...result('reported').items[0], rescueStatus: 'REPORTED' as const, eventStatus: 'REPORTED' as const }
    const verified = { ...reported, rescueStatus: 'WAITING_CLAIM' as const, eventStatus: 'VERIFIED' as const }
    let detailReads = 0
    const dataSource: RescueGovernanceDataSource = {
      search: async () => ({ ...result('reported'), items: [verified] }),
      getDetail: async () => ({ ...(detailReads++ === 0 ? reported : verified), claimedAt: null, resultSubmittedAt: null, closedAt: null, progress: [] }),
      verify: async () => { throw new AuthenticationApiError('conflict', 409) },
    }
    const catalog = useRescueCatalog(dataSource)
    await catalog.openDetail('reported')
    await catalog.verify()
    expect(catalog.verifyError.value).toContain('状态已变化')
    expect(catalog.detail.value?.eventStatus).toBe('VERIFIED')
    expect(catalog.items.value[0].eventStatus).toBe('VERIFIED')
  })
  it('does not show an old verification failure in a newly opened event', async () => {
    const old = { ...result('old').items[0], rescueStatus: 'REPORTED' as const, eventStatus: 'REPORTED' as const, claimedAt: null, resultSubmittedAt: null, closedAt: null, progress: [] }
    const next = { ...old, eventId: 'next' }
    let reject!: (reason: unknown) => void
    const failure = new Promise<RescueDetail>((_, fail) => { reject = fail })
    const dataSource: RescueGovernanceDataSource = {
      search: async () => result('next'),
      getDetail: async (id) => id === 'old' ? old : next,
      verify: async () => failure,
    }
    const catalog = useRescueCatalog(dataSource)
    await catalog.openDetail('old')
    const verify = catalog.verify()
    await catalog.openDetail('next')
    reject(new AuthenticationApiError('conflict', 409))
    await verify
    expect(catalog.detail.value?.eventId).toBe('next')
    expect(catalog.verifyError.value).toBe('')
  })
})
