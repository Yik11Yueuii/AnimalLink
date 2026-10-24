export type AnimalSpecies = 'CAT' | 'DOG' | 'OTHER'
export type AnimalSex = 'MALE' | 'FEMALE' | 'UNKNOWN'
export type IdentityStatus = 'ACTIVE' | 'MERGED' | 'ARCHIVED'
export type SterilizationStatus = 'STERILIZED' | 'NOT_STERILIZED' | 'UNKNOWN'
export type AdoptionStatus = 'NOT_OPEN' | 'OPEN' | 'HANDOVER_PENDING' | 'ADOPTED'
export type CurrentContext = 'CAMPUS' | 'ADOPTED_HOME'

export interface CampusResponse {
  id: string
  name: string
  shortName: string
  city: string
  region: string
  status: string
}

export interface AnimalSummaryResponse {
  id: string
  campusId: string
  displayName: string
  species: AnimalSpecies
  sex: AnimalSex
  coatColor: string
  adoptionStatus: AdoptionStatus
  currentContext: CurrentContext
  updatedAt: string
}

export interface PublicAnimalResponse {
  id: string
  campusId: string
  displayName: string
  species: AnimalSpecies
  sex: AnimalSex
  coatColor: string
  adoptionStatus: AdoptionStatus
  currentContext: CurrentContext
  createdAt: string
  updatedAt: string
}

export interface TimelineEntryResponse {
  id: string
  animalId: string
  sourceType: string
  sourceId: string
  entryType: string
  title: string
  summary: string
  occurredAt: string
  createdAt: string
}

export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface AnimalSearchCriteria {
  campusId: string
  q: string
  species: AnimalSpecies | ''
  page: number
  size: number
}
