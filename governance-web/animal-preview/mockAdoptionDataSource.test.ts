import { describe, expect, it } from 'vitest'
import {
  adoptionApplicationStatusLabels,
  adoptionListingStatusLabels,
  type AdoptionSearchCriteria,
} from '@/features/adoptions/types'
import {
  MockAdoptionGovernanceDataSource,
  previewAdoptionApplications,
} from './mockAdoptionDataSource'

const criteria = (overrides: Partial<AdoptionSearchCriteria> = {}): AdoptionSearchCriteria => ({
  keyword: '', status: '', sort: 'UPDATED_DESC', page: 1, size: 6, ...overrides,
})

describe('MockAdoptionGovernanceDataSource', () => {
  const dataSource = new MockAdoptionGovernanceDataSource()

  it('covers all application and Listing status values without inventing selection state', () => {
    expect(new Set(previewAdoptionApplications.map((item) => item.status))).toEqual(new Set(['SUBMITTED', 'APPROVED', 'REJECTED', 'WITHDRAWN']))
    expect(new Set(previewAdoptionApplications.map((item) => item.listing.status))).toEqual(new Set(['DRAFT', 'PUBLISHED', 'CLOSED']))
    for (const item of previewAdoptionApplications) expect(item).not.toHaveProperty('selected')
  })

  it('maps application and Listing status values to governance labels', () => {
    expect(adoptionApplicationStatusLabels).toEqual({
      SUBMITTED: '待审核', APPROVED: '已批准', REJECTED: '已拒绝', WITHDRAWN: '已撤回',
    })
    expect(adoptionListingStatusLabels).toEqual({ DRAFT: '草稿', PUBLISHED: '已发布', CLOSED: '已关闭' })
  })

  it('combines keyword and status filtering and calculates status counts from the current keyword scope', async () => {
    const target = previewAdoptionApplications.find((item) => item.status === 'APPROVED')!
    const result = await dataSource.search(criteria({ keyword: target.listing.title, status: 'APPROVED' }))
    expect(result.items).toHaveLength(1)
    expect(result.items[0].applicationId).toBe(target.applicationId)
    expect(result.statusCounts.APPROVED).toBe(1)
    expect(result.statusCounts.SUBMITTED).toBe(0)
  })

  it('paginates and sorts the complete preview dataset', async () => {
    const first = await dataSource.search(criteria({ page: 1, size: 6, sort: 'CREATED_DESC' }))
    const second = await dataSource.search(criteria({ page: 2, size: 6, sort: 'CREATED_DESC' }))
    expect(first.total).toBe(previewAdoptionApplications.length)
    expect(first.items).toHaveLength(6)
    expect(second.items).toHaveLength(6)
    expect(second.items[0].applicationId).not.toBe(first.items[0].applicationId)
    expect(Date.parse(first.items[0].createdAt)).toBeGreaterThanOrEqual(Date.parse(first.items[1].createdAt))
  })

  it('returns an isolated detail and preserves approval as review status only', async () => {
    const approved = previewAdoptionApplications.find((item) => item.status === 'APPROVED')!
    const detail = await dataSource.getDetail(approved.applicationId)
    expect(detail.status).toBe('APPROVED')
    expect(detail.reviewComment).toBeTruthy()
    expect(detail).not.toHaveProperty('selected')
    expect(detail.listing).not.toBe(approved.listing)
  })

  it('does not expose unnecessary applicant personal information', () => {
    for (const item of previewAdoptionApplications) {
      expect(item).not.toHaveProperty('applicantName')
      expect(item).not.toHaveProperty('phone')
      expect(item).not.toHaveProperty('email')
      expect(item).not.toHaveProperty('address')
    }
  })
})
