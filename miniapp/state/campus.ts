import type { CampusResponse } from '../types/api'

const CAMPUS_STORAGE_KEY = 'animallink.selectedCampus.v1'

const isCampus = (value: unknown): value is CampusResponse => {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<CampusResponse>
  return typeof candidate.id === 'string'
    && typeof candidate.name === 'string'
    && typeof candidate.shortName === 'string'
    && typeof candidate.city === 'string'
    && typeof candidate.region === 'string'
    && typeof candidate.status === 'string'
}

export const getSelectedCampus = (): CampusResponse | null => {
  const stored = wx.getStorageSync<unknown>(CAMPUS_STORAGE_KEY)
  return isCampus(stored) ? stored : null
}

export const saveSelectedCampus = (campus: CampusResponse) => {
  wx.setStorageSync(CAMPUS_STORAGE_KEY, campus)
}
