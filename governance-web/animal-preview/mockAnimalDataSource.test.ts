import { describe, expect, it } from 'vitest'
import type { AnimalSearchCriteria } from '@/features/animals/types'
import { MockAnimalGovernanceDataSource, previewAnimals, previewCampusOptions } from './mockAnimalDataSource'

const baseCriteria = (): AnimalSearchCriteria => ({
  keyword: '',
  campusId: '',
  species: '',
  identityStatus: '',
  adoptionStatus: '',
  currentContext: '',
  sort: 'UPDATED_DESC',
  page: 1,
  size: 5,
})

describe('MockAnimalGovernanceDataSource', () => {
  const source = new MockAnimalGovernanceDataSource()

  it('searches descriptive fields and combines exact filters', async () => {
    const result = await source.search({
      ...baseCriteria(),
      keyword: '橘子',
      campusId: previewCampusOptions[1].id,
      identityStatus: 'ACTIVE',
      adoptionStatus: 'OPEN',
      currentContext: 'CAMPUS',
    })

    expect(result.total).toBe(1)
    expect(result.items[0].displayName).toBe('橘子')
  })

  it('sorts and paginates without mutating the source records', async () => {
    const first = await source.search({ ...baseCriteria(), sort: 'NAME_ASC', size: 4 })
    const second = await source.search({ ...baseCriteria(), sort: 'NAME_ASC', page: 2, size: 4 })

    expect(first.items).toHaveLength(4)
    expect(second.items).toHaveLength(4)
    expect(new Set([...first.items, ...second.items].map((item) => item.id)).size).toBe(8)
    expect(previewAnimals).toHaveLength(16)
  })

  it('returns full governance details including merge metadata', async () => {
    const merged = previewAnimals.find((animal) => animal.identityStatus === 'MERGED')!
    const detail = await source.getDetail(merged.id)

    expect(detail.version).toBeGreaterThan(0)
    expect(detail.canonicalAnimalId).not.toBe(detail.id)
    expect(detail.mergeId).toBeTruthy()
    expect(detail.mergedAt).toBeTruthy()
  })
})
