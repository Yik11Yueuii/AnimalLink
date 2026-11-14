import { beforeEach, describe, expect, it, vi } from 'vitest'
import { gatewayClient } from '@/api/client'
import * as auth from '@/api/authentication'
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
  it('sends review and handover mutations through authenticated admin requests', async () => {
    const mutation = vi.spyOn(auth, 'authenticatedAdminMutation').mockResolvedValue(undefined as never)
    await adoptionApiDataSource.review?.('application', 'reject', 'reason')
    await adoptionApiDataSource.initiate?.('selection', '2030-01-01T04:00:00.000Z')
    await adoptionApiDataSource.complete?.('handover')
    expect(mutation).toHaveBeenCalledWith('/adoption/api/v1/admin/applications/application/reject', { reason: 'reason' })
    expect(mutation).toHaveBeenCalledWith('/adoption/api/v1/admin/selections/selection/handover', { scheduledAt: '2030-01-01T04:00:00.000Z' })
    expect(mutation).toHaveBeenCalledWith('/adoption/api/v1/admin/handovers/handover/complete', undefined)
  })
  it('reads drafts through admin routes and creates and publishes through protected mutations', async () => {
    const listing = application.listing
    const get = vi.spyOn(gatewayClient, 'get').mockResolvedValueOnce({ data: { items: [listing], page: 0, size: 20, total: 1 } })
      .mockResolvedValueOnce({ data: listing })
    const mutation = vi.spyOn(auth, 'authenticatedAdminMutation').mockResolvedValueOnce(listing as never)
      .mockResolvedValueOnce({ ...listing, status: 'PUBLISHED' } as never)
    expect((await adoptionApiDataSource.listings?.('DRAFT', 0, 20))?.items[0].listingId).toBe('listing')
    expect((await adoptionApiDataSource.listing?.('listing'))?.listingId).toBe('listing')
    await adoptionApiDataSource.createListing?.('animal', 'title', 'description')
    await adoptionApiDataSource.publishListing?.('listing')
    expect(get).toHaveBeenCalledWith('/adoption/api/v1/admin/listings', { params: { status: 'DRAFT', page: 0, size: 20 } })
    expect(mutation).toHaveBeenCalledWith('/adoption/api/v1/admin/listings', { animalId: 'animal', title: 'title', description: 'description' })
    expect(mutation).toHaveBeenCalledWith('/adoption/api/v1/admin/listings/listing/publish', undefined)
  })
})
