import { describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import type { AnimalGovernanceDataSource } from './dataSource'
import type { GovernanceAnimal } from './types'
import { useAnimalCatalog } from './useAnimalCatalog'

const animal = (id: string, displayName: string): GovernanceAnimal => ({
  requestedAnimalId: id,
  canonicalAnimalId: id,
  id,
  campusId: 'campus-1',
  displayName,
  species: 'CAT',
  sex: 'UNKNOWN',
  coatColor: null,
  distinctiveFeatures: null,
  description: null,
  sterilizationStatus: 'UNKNOWN',
  typicalArea: null,
  identityStatus: 'ACTIVE',
  adoptionStatus: 'NOT_OPEN',
  currentContext: 'CAMPUS',
  version: 1,
  mergeId: null,
  mergedAt: null,
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-02T00:00:00Z',
})

describe('useAnimalCatalog', () => {
  it('resets pagination when applying a new page size or filter load', async () => {
    const search = vi.fn(async (criteria) => ({ items: [], page: criteria.page, size: criteria.size, total: 0 }))
    const source: AnimalGovernanceDataSource = { search, getDetail: vi.fn() }
    const catalog = useAnimalCatalog(source)

    catalog.criteria.page = 3
    await catalog.load(true)
    expect(search).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))

    catalog.criteria.page = 4
    await catalog.changePageSize(10)
    expect(search).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 10 }))
  })

  it('keeps the latest detail when requests resolve out of order', async () => {
    let resolveFirst!: (value: GovernanceAnimal) => void
    const first = new Promise<GovernanceAnimal>((resolve) => { resolveFirst = resolve })
    const source: AnimalGovernanceDataSource = {
      search: vi.fn(),
      getDetail: vi.fn((id: string) => id === 'first' ? first : Promise.resolve(animal('second', '雪团'))),
    }
    const catalog = useAnimalCatalog(source)

    const staleRequest = catalog.openDetail('first')
    await catalog.openDetail('second')
    resolveFirst(animal('first', '橘子'))
    await staleRequest
    await nextTick()

    expect(catalog.detail.value?.id).toBe('second')
    expect(catalog.detail.value?.displayName).toBe('雪团')
  })
})
