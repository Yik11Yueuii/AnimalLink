import assert from 'node:assert/strict'
import test from 'node:test'
import { AnimalCatalog } from '../state/animalCatalog'
import type { PublicAnimalDataSource } from '../services/dataSource'
import type { AnimalSearchCriteria, AnimalSummaryResponse, PageResponse } from '../types/api'

const animal = (id: string, campusId: string): AnimalSummaryResponse => ({
  id, campusId, displayName: id, species: 'CAT', sex: 'UNKNOWN', coatColor: '橘色', distinctiveFeatures: '测试特征',
  sterilizationStatus: 'UNKNOWN', typicalArea: '测试区域', identityStatus: 'ACTIVE', adoptionStatus: 'NOT_OPEN',
  currentContext: 'CAMPUS', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
})

const sourceWithSearch = (search: (criteria: AnimalSearchCriteria) => Promise<PageResponse<AnimalSummaryResponse>>): PublicAnimalDataSource => ({
  listCampuses: async () => [],
  searchAnimals: search,
  getAnimal: async () => { throw new Error('unused') },
  getTimeline: async () => ({ items: [], page: 0, size: 20, total: 0 }),
})

test('refresh starts at page zero and loadMore appends the next page', async () => {
  const pages: number[] = []
  const catalog = new AnimalCatalog(sourceWithSearch(async (criteria) => {
    pages.push(criteria.page)
    return { items: [animal(`a-${criteria.page}`, criteria.campusId)], page: criteria.page, size: 1, total: 2 }
  }), 1)
  catalog.selectCampus('campus-a')
  await catalog.refresh()
  await catalog.loadMore()
  assert.deepEqual(pages, [0, 1])
  assert.deepEqual(catalog.snapshot.items.map((item) => item.id), ['a-0', 'a-1'])
  assert.equal(catalog.snapshot.hasMore, false)
})

test('campus switch clears old results and ignores an older in-flight response', async () => {
  let resolveFirst: ((value: PageResponse<AnimalSummaryResponse>) => void) | undefined
  const catalog = new AnimalCatalog(sourceWithSearch((criteria) => {
    if (criteria.campusId === 'campus-a') return new Promise((resolve) => { resolveFirst = resolve })
    return Promise.resolve({ items: [animal('b-1', 'campus-b')], page: 0, size: 4, total: 1 })
  }))
  catalog.selectCampus('campus-a')
  const first = catalog.refresh()
  catalog.selectCampus('campus-b')
  assert.equal(catalog.snapshot.items.length, 0)
  await catalog.refresh()
  resolveFirst?.({ items: [animal('a-late', 'campus-a')], page: 0, size: 4, total: 1 })
  await first
  assert.deepEqual(catalog.snapshot.items.map((item) => item.id), ['b-1'])
})

test('filter changes reset pagination and preserve an actionable request error', async () => {
  const catalog = new AnimalCatalog(sourceWithSearch(async () => { throw new Error('Gateway 暂不可用') }))
  catalog.selectCampus('campus-a')
  catalog.setQuery('橘子')
  catalog.setSpecies('CAT')
  await catalog.refresh()
  assert.equal(catalog.snapshot.page, 0)
  assert.equal(catalog.snapshot.errorMessage, 'Gateway 暂不可用')
  assert.deepEqual(catalog.snapshot.items, [])
})
