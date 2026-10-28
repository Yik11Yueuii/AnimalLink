import { beforeEach, describe, expect, it, vi } from 'vitest'
import { gatewayClient } from '@/api/client'
import { adoptionApiDataSource } from './apiDataSource'

const application = { applicationId: 'application', listingId: 'listing', applicantReference: 'user-…12345678', status: 'SUBMITTED',
  message: 'message', createdAt: '2026-10-28T00:00:00Z', updatedAt: '2026-10-28T00:00:00Z', reviewerReference: null,
  reviewedAt: null, reviewComment: null, listing: { listingId: 'listing', animalId: 'animal', status: 'PUBLISHED', title: 'title',
    description: 'description', createdAt: '2026-10-28T00:00:00Z', updatedAt: '2026-10-28T00:00:00Z', publishedAt: '2026-10-28T00:00:00Z', closedAt: null } }

describe('adoption API data source', () => {
  beforeEach(() => vi.restoreAllMocks())
  it('uses the exact read endpoint and validates status counts', async () => {
    const get = vi.spyOn(gatewayClient, 'get').mockResolvedValue({ data: { items: [application], page: 0, size: 6, total: 1,
      statusCounts: { SUBMITTED: 1, APPROVED: 0, REJECTED: 0, WITHDRAWN: 0 } } })
    const result = await adoptionApiDataSource.search({ keyword: '', status: 'SUBMITTED', sort: 'UPDATED_DESC', page: 1, size: 6 })
    expect(get).toHaveBeenCalledWith('/adoption/api/v1/admin/applications', { params: { status: 'SUBMITTED', keyword: undefined, sort: 'UPDATED_DESC', page: 0, size: 6 } })
    expect(result.items[0].applicantReference).toBe('user-…12345678'); expect(result.statusCounts.SUBMITTED).toBe(1)
  })
  it('rejects malformed responses instead of inventing listing data', async () => {
    vi.spyOn(gatewayClient, 'get').mockResolvedValue({ data: { ...application, listing: null } })
    await expect(adoptionApiDataSource.getDetail('application')).rejects.toThrow('无法验证')
  })
})
