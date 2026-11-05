import assert from 'node:assert/strict'
import test, { beforeEach } from 'node:test'
import { ApiRequestError } from '../services/apiClient'
import {
  getSelectedCampus,
  resetCampusValidationCacheForTest,
  resolveSelectedCampus,
  saveSelectedCampus,
} from '../state/campus'
import type { CampusResponse } from '../types/api'

const key = 'animallink.selectedCampus.v1'
let storage: Map<string, unknown>

const campus = (id = 'campus-1', universityId = 'university-1'): CampusResponse => ({
  id, universityId, name: '韩山师范学院社区', shortName: '韩师社区', city: '潮州市', region: null,
  campusType: 'UNIVERSITY_COMMUNITY', status: 'ACTIVE',
})

const deferred = <T>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

beforeEach(() => {
  storage = new Map()
  ;(globalThis as unknown as { wx: unknown }).wx = {
    getStorageSync: (storageKey: string) => storage.get(storageKey),
    setStorageSync: (storageKey: string, value: unknown) => storage.set(storageKey, value),
    removeStorageSync: (storageKey: string) => storage.delete(storageKey),
  }
  resetCampusValidationCacheForTest()
})

test('API Campus validation uses University community mapping and caches a valid result', async () => {
  const selected = campus()
  saveSelectedCampus(selected)
  let calls = 0
  const lookup = { getUniversityCommunity: async () => { calls += 1; return { ...selected, name: '服务端名称' } } }
  const first = await resolveSelectedCampus(lookup, true)
  const second = await resolveSelectedCampus(lookup, true)
  assert.equal(first.status, 'valid')
  assert.equal(second.status, 'valid')
  assert.equal(calls, 1)
  assert.equal(getSelectedCampus()?.name, '服务端名称')
})

test('missing University association or mismatched community clears the stale Campus', async () => {
  const invalid = { ...campus(), universityId: null }
  saveSelectedCampus(invalid)
  let calls = 0
  assert.equal((await resolveSelectedCampus({ getUniversityCommunity: async () => { calls += 1; return campus() } }, true)).status, 'missing')
  assert.equal(calls, 0)
  assert.equal(getSelectedCampus(), null)

  saveSelectedCampus(campus())
  const mismatch = await resolveSelectedCampus({ getUniversityCommunity: async () => campus('campus-other') }, true)
  assert.equal(mismatch.status, 'missing')
  assert.equal(getSelectedCampus(), null)
})

test('404 removes an unavailable Campus while network failure preserves the selection', async () => {
  const selected = campus()
  saveSelectedCampus(selected)
  const missing = await resolveSelectedCampus({
    getUniversityCommunity: async () => { throw new ApiRequestError('not found', 404) },
  }, true)
  assert.equal(missing.status, 'missing')
  assert.equal(getSelectedCampus(), null)

  saveSelectedCampus(selected)
  const unavailable = await resolveSelectedCampus({
    getUniversityCommunity: async () => { throw new ApiRequestError('network unavailable', null) },
  }, true)
  assert.equal(unavailable.status, 'unavailable')
  assert.deepEqual(getSelectedCampus(), selected)
})

test('late validation cannot restore or validate a Campus replaced by a newer selection', async () => {
  const first = campus('campus-a', 'university-a')
  const second = campus('campus-b', 'university-b')
  const pending = deferred<CampusResponse>()
  saveSelectedCampus(first)
  const validation = resolveSelectedCampus({ getUniversityCommunity: () => pending.promise }, true)
  saveSelectedCampus(second)
  pending.resolve(first)
  assert.equal((await validation).status, 'superseded')
  assert.deepEqual(getSelectedCampus(), second)
})

test('offline mode does not contact the University API or alter existing demo Campus', async () => {
  const selected = campus('offline-campus', 'offline-university')
  saveSelectedCampus(selected)
  let calls = 0
  const result = await resolveSelectedCampus({ getUniversityCommunity: async () => { calls += 1; return selected } }, false)
  assert.equal(result.status, 'valid')
  assert.equal(calls, 0)
  assert.deepEqual(getSelectedCampus(), selected)
})
