import { describe, expect, it } from 'vitest'
import type { AdoptionGovernanceDataSource } from './dataSource'
import type { AdoptionSearchResult, GovernanceAdoptionApplication } from './types'
import { useAdoptionCatalog } from './useAdoptionCatalog'

const deferred = <T>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

const item = (applicationId: string): GovernanceAdoptionApplication => ({
  applicationId,
  listingId: 'listing',
  applicantReference: 'user-…applicant',
  status: 'SUBMITTED',
  message: applicationId,
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-01T00:00:00Z',
  reviewerReference: null,
  reviewedAt: null,
  reviewComment: null,
  listing: {
    listingId: 'listing', animalId: 'animal', status: 'PUBLISHED', title: applicationId,
    description: applicationId, createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z', publishedAt: '2026-09-02T00:00:00Z', closedAt: null,
  },
})

const result = (applicationId: string): AdoptionSearchResult => ({
  items: [item(applicationId)], page: 1, size: 6, total: 1,
  statusCounts: { SUBMITTED: 1, APPROVED: 0, REJECTED: 0, WITHDRAWN: 0 },
})

describe('useAdoptionCatalog', () => {
  it('keeps the newest search and statistics when requests finish out of order', async () => {
    const first = deferred<AdoptionSearchResult>()
    const second = deferred<AdoptionSearchResult>()
    let calls = 0
    const dataSource: AdoptionGovernanceDataSource = {
      search: () => (++calls === 1 ? first.promise : second.promise),
      getDetail: async () => { throw new Error('unused') },
    }
    const catalog = useAdoptionCatalog(dataSource)
    const older = catalog.load()
    const newer = catalog.load()
    second.resolve(result('newer'))
    await newer
    first.resolve(result('older'))
    await older
    expect(catalog.items.value[0].applicationId).toBe('newer')
    expect(catalog.statusCounts.value.SUBMITTED).toBe(1)
    expect(catalog.loading.value).toBe(false)
  })

  it('keeps the newest detail when applications switch quickly', async () => {
    const first = deferred<GovernanceAdoptionApplication>()
    const second = deferred<GovernanceAdoptionApplication>()
    const dataSource: AdoptionGovernanceDataSource = {
      search: async () => result('list'),
      getDetail: (applicationId) => applicationId === 'older' ? first.promise : second.promise,
    }
    const catalog = useAdoptionCatalog(dataSource)
    const older = catalog.openDetail('older')
    const newer = catalog.openDetail('newer')
    second.resolve(item('newer'))
    await newer
    first.resolve(item('older'))
    await older
    expect(catalog.detail.value?.applicationId).toBe('newer')
  })

  it('resets pagination when a filter or page size changes', async () => {
    const dataSource: AdoptionGovernanceDataSource = {
      search: async (criteria) => ({ ...result('item'), page: criteria.page, size: criteria.size }),
      getDetail: async () => { throw new Error('unused') },
    }
    const catalog = useAdoptionCatalog(dataSource)
    catalog.criteria.page = 3
    await catalog.load(true)
    expect(catalog.criteria.page).toBe(1)
    catalog.criteria.page = 2
    await catalog.changePageSize(9)
    expect(catalog.criteria.page).toBe(1)
    expect(catalog.criteria.size).toBe(9)
  })
})
