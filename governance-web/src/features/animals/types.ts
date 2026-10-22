export type AnimalSpecies = 'CAT' | 'DOG' | 'OTHER'
export type AnimalSex = 'MALE' | 'FEMALE' | 'UNKNOWN'
export type SterilizationStatus = 'STERILIZED' | 'NOT_STERILIZED' | 'UNKNOWN'
export type AnimalIdentityStatus = 'ACTIVE' | 'MERGED' | 'ARCHIVED'
export type AnimalAdoptionStatus = 'NOT_OPEN' | 'OPEN' | 'HANDOVER_PENDING' | 'ADOPTED'
export type AnimalCurrentContext = 'CAMPUS' | 'ADOPTED_HOME'
export type AnimalSort = 'UPDATED_DESC' | 'UPDATED_ASC' | 'NAME_ASC' | 'NAME_DESC'

export interface GovernanceAnimal {
  requestedAnimalId: string
  canonicalAnimalId: string
  id: string
  campusId: string
  displayName: string
  species: AnimalSpecies
  sex: AnimalSex
  coatColor: string | null
  distinctiveFeatures: string | null
  description: string | null
  sterilizationStatus: SterilizationStatus
  typicalArea: string | null
  identityStatus: AnimalIdentityStatus
  adoptionStatus: AnimalAdoptionStatus
  currentContext: AnimalCurrentContext
  version: number
  mergeId: string | null
  mergedAt: string | null
  createdAt: string
  updatedAt: string
}

export type AnimalListItem = Pick<
  GovernanceAnimal,
  | 'id'
  | 'campusId'
  | 'displayName'
  | 'species'
  | 'sex'
  | 'coatColor'
  | 'typicalArea'
  | 'identityStatus'
  | 'adoptionStatus'
  | 'currentContext'
  | 'updatedAt'
>

export interface AnimalSearchCriteria {
  keyword: string
  campusId: string
  species: AnimalSpecies | ''
  identityStatus: AnimalIdentityStatus | ''
  adoptionStatus: AnimalAdoptionStatus | ''
  currentContext: AnimalCurrentContext | ''
  sort: AnimalSort
  page: number
  size: number
}

export interface AnimalSearchResult {
  items: AnimalListItem[]
  page: number
  size: number
  total: number
}

export interface CampusOption {
  id: string
  label: string
}

export const speciesLabels: Record<AnimalSpecies, string> = {
  CAT: '猫',
  DOG: '狗',
  OTHER: '其他',
}

export const sexLabels: Record<AnimalSex, string> = {
  MALE: '公',
  FEMALE: '母',
  UNKNOWN: '未知',
}

export const sterilizationLabels: Record<SterilizationStatus, string> = {
  STERILIZED: '已绝育',
  NOT_STERILIZED: '未绝育',
  UNKNOWN: '未知',
}

export const identityStatusLabels: Record<AnimalIdentityStatus, string> = {
  ACTIVE: '正常档案',
  MERGED: '已合并',
  ARCHIVED: '已归档',
}

export const adoptionStatusLabels: Record<AnimalAdoptionStatus, string> = {
  NOT_OPEN: '未开放领养',
  OPEN: '开放领养',
  HANDOVER_PENDING: '等待交接',
  ADOPTED: '已领养',
}

export const currentContextLabels: Record<AnimalCurrentContext, string> = {
  CAMPUS: '校园',
  ADOPTED_HOME: '领养家庭',
}
