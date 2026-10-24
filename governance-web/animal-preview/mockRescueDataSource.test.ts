import { describe, expect, it } from 'vitest'
import { MockRescueGovernanceDataSource, previewRescues } from './mockRescueDataSource'
import type { RescueSearchCriteria } from '@/features/rescues/types'

const criteria = (overrides: Partial<RescueSearchCriteria> = {}): RescueSearchCriteria => ({
  keyword: '', campusId: '', rescueStatus: '', sort: 'UPDATED_DESC', page: 1, size: 6, ...overrides,
})

describe('MockRescueGovernanceDataSource', () => {
  const dataSource = new MockRescueGovernanceDataSource()

  it('covers each summary status while preserving distinct terminal Case outcomes', () => {
    expect(new Set(previewRescues.map((item) => item.rescueStatus))).toEqual(new Set(['WAITING_CLAIM', 'IN_PROGRESS', 'COMPLETED']))
    const completed = previewRescues.filter((item) => item.rescueStatus === 'COMPLETED')
    expect(completed.some((item) => item.caseStatus === 'RESOLVED')).toBe(true)
    expect(completed.some((item) => item.caseStatus === 'CLOSED_UNRESOLVED')).toBe(true)
    expect(completed.some((item) => item.caseStatus === 'CANCELLED')).toBe(true)
  })

  it('combines keyword, campus, status and pagination', async () => {
    const target = previewRescues.find((item) => item.rescueStatus === 'IN_PROGRESS')!
    const result = await dataSource.search(criteria({
      keyword: target.animal?.displayName ?? target.abnormalitySummary ?? '',
      campusId: target.campusId,
      rescueStatus: 'IN_PROGRESS',
      size: 1,
    }))
    expect(result.total).toBeGreaterThan(0)
    expect(result.items).toHaveLength(1)
    expect(result.items[0].eventId).toBe(target.eventId)
  })

  it('paginates the complete preview dataset', async () => {
    const first = await dataSource.search(criteria({ page: 1, size: 6 }))
    const second = await dataSource.search(criteria({ page: 2, size: 6 }))
    expect(first.total).toBe(previewRescues.length)
    expect(first.items).toHaveLength(6)
    expect(second.items).toHaveLength(6)
    expect(second.items[0].eventId).not.toBe(first.items[0].eventId)
  })

  it('returns a verified event with no Case and an explicit empty progress list', async () => {
    const item = previewRescues.find((candidate) => candidate.caseId === null)!
    const detail = await dataSource.getDetail(item.eventId)
    expect(detail.eventStatus).toBe('VERIFIED')
    expect(detail.caseId).toBeNull()
    expect(detail.progress).toEqual([])
  })

  it('contains only public location descriptions and no coordinate fields', () => {
    for (const item of previewRescues) {
      expect(item.publicLocationDescription).toBeTruthy()
      expect(item).not.toHaveProperty('latitude')
      expect(item).not.toHaveProperty('longitude')
      expect(item).not.toHaveProperty('exactLocationDescription')
    }
  })
})
