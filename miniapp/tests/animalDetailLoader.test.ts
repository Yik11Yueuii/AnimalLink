import assert from 'node:assert/strict'
import test from 'node:test'
import { ApiRequestError } from '../services/apiClient'
import { loadAnimalDetail } from '../services/animalDetailLoader'
import type { PublicAnimalDataSource } from '../services/dataSource'
import type { PublicAnimalResponse, TimelineEntryResponse } from '../types/api'

const animal: PublicAnimalResponse = {
  id: 'animal-1', campusId: 'campus-1', displayName: '橘子', species: 'CAT', sex: 'UNKNOWN',
  coatColor: '橘白', adoptionStatus: 'NOT_OPEN', currentContext: 'CAMPUS',
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-02T00:00:00Z',
}

const timeline: TimelineEntryResponse = {
  id: 'timeline-1', animalId: animal.id, sourceType: 'ANIMAL', sourceId: animal.id,
  entryType: 'PROFILE_CREATED', title: '建立档案', summary: '公开记录',
  occurredAt: '2026-01-01T00:00:00Z', createdAt: '2026-01-01T00:00:00Z',
}

const sourceWithTimeline = (getTimeline: PublicAnimalDataSource['getTimeline']): PublicAnimalDataSource => ({
  searchUniversities: async () => ({ items: [], page: 0, size: 20, total: 0 }),
  getUniversityCommunity: async () => { throw new Error('not used') },
  listCampuses: async () => [],
  searchAnimals: async () => ({ items: [], page: 0, size: 20, total: 0 }),
  getAnimal: async () => animal,
  getTimeline,
})

test('a protected timeline does not hide a successfully loaded public Animal', async () => {
  const result = await loadAnimalDetail(sourceWithTimeline(async () => {
    throw new ApiRequestError('未认证', 401)
  }), animal.id)
  assert.equal(result.animal, animal)
  assert.equal(result.timelineStatus, 'protected')
  assert.deepEqual(result.timeline, [])
})

test('a timeline service failure remains visible without replacing Animal data', async () => {
  const failure = new ApiRequestError('服务不可用', 503)
  const result = await loadAnimalDetail(sourceWithTimeline(async () => { throw failure }), animal.id)
  assert.equal(result.animal, animal)
  assert.equal(result.timelineStatus, 'failed')
  assert.equal(result.timelineError, failure)
  assert.deepEqual(result.timeline, [])
})

test('a successful timeline read is returned normally', async () => {
  const result = await loadAnimalDetail(sourceWithTimeline(async () => ({
    items: [timeline], page: 0, size: 20, total: 1,
  })), animal.id)
  assert.equal(result.timelineStatus, 'loaded')
  assert.deepEqual(result.timeline, [timeline])
})

test('an Animal detail failure is not converted to empty data or an offline fallback', async () => {
  const failure = new ApiRequestError('Animal 请求失败', 503)
  const source = sourceWithTimeline(async () => ({ items: [], page: 0, size: 20, total: 0 }))
  source.getAnimal = async () => { throw failure }
  await assert.rejects(() => loadAnimalDetail(source, animal.id), (error) => error === failure)
})
