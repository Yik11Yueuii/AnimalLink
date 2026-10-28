import { beforeEach, describe, expect, it, vi } from 'vitest'
import { gatewayClient } from '@/api/client'
import { rescueApiDataSource } from './apiDataSource'

const rescue = { eventId: 'event', caseId: null, campusId: 'campus', animalId: 'animal-list', rescueStatus: 'WAITING_CLAIM',
  eventStatus: 'VERIFIED', caseStatus: null, claimStatus: null, hasOwner: false, abnormalitySummary: null,
  description: 'description', publicLocationDescription: 'campus area', occurredAt: '2026-10-28T00:00:00Z',
  reportedAt: '2026-10-28T00:00:00Z', evidenceCount: 0, actionCount: 0, resultCode: null,
  resultSummary: null, updatedAt: '2026-10-28T00:00:00Z' }

describe('rescue API data source', () => {
  beforeEach(() => vi.restoreAllMocks())
  it('maps one-based UI pagination to the exact incident endpoint', async () => {
    const get = vi.spyOn(gatewayClient, 'get')
      .mockResolvedValueOnce({ data: { items: [rescue], page: 1, size: 6, total: 7 } })
      .mockResolvedValueOnce({ data: { id: 'animal-list', campusId: 'campus', displayName: '小橘', species: 'CAT' } })
    const result = await rescueApiDataSource.search({ keyword: '橘', campusId: 'campus', rescueStatus: 'IN_PROGRESS', sort: 'UPDATED_DESC', page: 2, size: 6 })
    expect(get).toHaveBeenCalledWith('/incident/api/v1/admin/rescues', { params: { campusId: 'campus', status: 'IN_PROGRESS', keyword: '橘', sort: 'UPDATED_DESC', page: 1, size: 6 } })
    expect(result.page).toBe(2)
    expect(get).toHaveBeenCalledWith('/animal/api/v1/public/animals/animal-list')
    expect(result.items[0].animal).toMatchObject({ id: 'animal-list', displayName: '小橘', species: 'CAT' })
  })
  it('keeps detail usable without private location or media fields', async () => {
    vi.spyOn(gatewayClient, 'get')
      .mockResolvedValueOnce({ data: { rescue: { ...rescue, animalId: 'animal-detail' }, claimedAt: null, resultSubmittedAt: null, closedAt: null, progress: [] } })
      .mockResolvedValueOnce({ data: { id: 'animal-detail', campusId: 'campus', displayName: '墨墨', species: 'CAT' } })
    const detail = await rescueApiDataSource.getDetail('event')
    expect(detail.description).toBe('description'); expect(detail.progress).toEqual([])
    expect(detail.animal?.displayName).toBe('墨墨')
  })
  it('keeps the rescue visible when the optional public Animal lookup fails', async () => {
    vi.spyOn(gatewayClient, 'get')
      .mockResolvedValueOnce({ data: { items: [{ ...rescue, animalId: 'animal-failure' }], page: 0, size: 6, total: 1 } })
      .mockRejectedValueOnce(new Error('public animal unavailable'))
    const result = await rescueApiDataSource.search({ keyword: '', campusId: 'campus', rescueStatus: '', sort: 'UPDATED_DESC', page: 1, size: 6 })
    expect(result.items[0]).toMatchObject({ eventId: 'event', animal: null })
  })
})
