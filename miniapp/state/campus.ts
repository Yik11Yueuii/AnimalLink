import type { CampusResponse } from '../types/api'
import { ApiRequestError } from '../services/apiClient'

interface UniversityCommunityLookup {
  getUniversityCommunity(universityId: string): Promise<CampusResponse>
}

export type CampusResolution =
  | { status: 'valid'; campus: CampusResponse }
  | { status: 'missing'; campus: null }
  | { status: 'unavailable'; campus: CampusResponse; error: unknown }
  | { status: 'superseded'; campus: null }

const CAMPUS_STORAGE_KEY = 'animallink.selectedCampus.v1'

const isCampus = (value: unknown): value is CampusResponse => {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<CampusResponse>
  return typeof candidate.id === 'string'
    && typeof candidate.name === 'string'
    && (typeof candidate.shortName === 'string' || candidate.shortName === null)
    && typeof candidate.city === 'string'
    && (typeof candidate.region === 'string' || candidate.region === null)
    && typeof candidate.status === 'string'
}

export const getSelectedCampus = (): CampusResponse | null => {
  const stored = wx.getStorageSync<unknown>(CAMPUS_STORAGE_KEY)
  return isCampus(stored) ? stored : null
}

export const saveSelectedCampus = (campus: CampusResponse) => {
  validationGeneration += 1
  validatedKey = ''
  validatedCampus = null
  validationInFlight = null
  wx.setStorageSync(CAMPUS_STORAGE_KEY, campus)
}

let validationGeneration = 0
let validatedKey = ''
let validatedCampus: CampusResponse | null = null
let validationInFlight: { key: string; promise: Promise<CampusResolution> } | null = null

const campusKey = (campus: CampusResponse) => [campus.id, campus.universityId ?? '', campus.status, campus.campusType].join('|')

const clearIfCurrent = (expectedKey: string) => {
  const current = getSelectedCampus()
  if (!current || campusKey(current) !== expectedKey) return
  validationGeneration += 1
  validatedKey = ''
  validatedCampus = null
  validationInFlight = null
  wx.removeStorageSync(CAMPUS_STORAGE_KEY)
}

const isUsableCommunity = (campus: CampusResponse, universityId: string) => campus.id.length > 0
  && campus.universityId === universityId
  && campus.status === 'ACTIVE'
  && campus.campusType === 'UNIVERSITY_COMMUNITY'

export const resolveSelectedCampus = (
  lookup: UniversityCommunityLookup,
  validateRemotely: boolean,
): Promise<CampusResolution> => {
  const stored = getSelectedCampus()
  if (!stored) return Promise.resolve({ status: 'missing', campus: null })
  if (!validateRemotely) return Promise.resolve({ status: 'valid', campus: stored })
  const universityId = stored.universityId
  const key = campusKey(stored)
  if (!universityId || !isUsableCommunity(stored, universityId)) {
    clearIfCurrent(key)
    return Promise.resolve({ status: 'missing', campus: null })
  }
  if (validatedKey === key && validatedCampus) return Promise.resolve({ status: 'valid', campus: validatedCampus })
  if (validationInFlight?.key === key) return validationInFlight.promise

  const generation = validationGeneration
  const promise = lookup.getUniversityCommunity(universityId).then<CampusResolution>((remote) => {
    const current = getSelectedCampus()
    if (generation !== validationGeneration || !current || campusKey(current) !== key) {
      return { status: 'superseded', campus: null }
    }
    if (!isUsableCommunity(remote, universityId) || remote.id !== stored.id) {
      clearIfCurrent(key)
      return { status: 'missing', campus: null }
    }
    wx.setStorageSync(CAMPUS_STORAGE_KEY, remote)
    validatedKey = campusKey(remote)
    validatedCampus = remote
    return { status: 'valid', campus: remote }
  }).catch<CampusResolution>((error: unknown) => {
    const current = getSelectedCampus()
    if (generation !== validationGeneration || !current || campusKey(current) !== key) {
      return { status: 'superseded', campus: null }
    }
    if (error instanceof ApiRequestError && error.statusCode === 404) {
      clearIfCurrent(key)
      return { status: 'missing', campus: null }
    }
    return { status: 'unavailable', campus: stored, error }
  }).finally(() => {
    if (validationInFlight?.promise === promise) validationInFlight = null
  })
  validationInFlight = { key, promise }
  return promise
}

export const resetCampusValidationCacheForTest = () => {
  validationGeneration += 1
  validatedKey = ''
  validatedCampus = null
  validationInFlight = null
}
