import { beforeEach, describe, expect, it, vi } from 'vitest'
import { gatewayClient } from '@/api/client'
import { rescueApiDataSource } from './apiDataSource'

const rescue = { eventId: 'event', caseId: null, campusId: 'campus', animalId: null, rescueStatus: 'WAITING_CLAIM',
  eventStatus: 'VERIFIED', caseStatus: null, claimStatus: null, hasOwner: false, abnormalitySummary: null,
  description: 'description', publicLocationDescription: 'campus area', occurredAt: '2026-10-28T00:00:00Z',
  reportedAt: '2026-10-28T00:00:00Z', evidenceCount: 0, actionCount: 0, resultCode: null,
  resultSummary: null, updatedAt: '2026-10-28T00:00:00Z' }

describe('rescue API data source', () => {
  beforeEach(() => vi.restoreAllMocks())
  it('maps one-based UI pagination to the exact incident endpoint', async () => {
    const get = vi.spyOn(gatewayClient, 'get').mockResolvedValue({ data: { items: [rescue], page: 1, size: 6, total: 7 } })
    const result = await rescueApiDataSource.search({ keyword: '橘', campusId: 'campus', rescueStatus: 'IN_PROGRESS', sort: 'UPDATED_DESC', page: 2, size: 6 })
    expect(get).toHaveBeenCalledWith('/incident/api/v1/admin/rescues', { params: { campusId: 'campus', status: 'IN_PROGRESS', keyword: '橘', sort: 'UPDATED_DESC', page: 1, size: 6 } })
    expect(result.page).toBe(2); expect(result.items[0].animal).toBeNull()
  })
  it('keeps detail usable without private location or media fields', async () => {
    vi.spyOn(gatewayClient, 'get').mockResolvedValue({ data: { rescue, claimedAt: null, resultSubmittedAt: null, closedAt: null, progress: [] } })
    const detail = await rescueApiDataSource.getDetail('event')
    expect(detail.description).toBe('description'); expect(detail.progress).toEqual([])
  })
})
