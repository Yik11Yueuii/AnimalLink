export type AnimalSpecies = 'CAT' | 'DOG' | 'OTHER'
export type AnimalSex = 'MALE' | 'FEMALE' | 'UNKNOWN'
export type IdentityStatus = 'ACTIVE' | 'MERGED' | 'ARCHIVED'
export type SterilizationStatus = 'STERILIZED' | 'NOT_STERILIZED' | 'UNKNOWN'
export type AdoptionStatus = 'NOT_OPEN' | 'OPEN' | 'HANDOVER_PENDING' | 'ADOPTED'
export type CurrentContext = 'CAMPUS' | 'ADOPTED_HOME'

export interface CampusResponse {
  id: string
  universityId: string | null
  name: string
  shortName: string | null
  city: string
  region: string | null
  campusType: 'LEGACY' | 'UNIVERSITY_COMMUNITY' | 'CAMPUS_AREA' | 'ADMINISTRATIVE_CAMPUS'
  status: string
}

export interface UniversityResponse {
  id: string
  moeInstitutionCode: string
  officialName: string
  shortName: string | null
  province: string
  city: string
  status: string
  communityAvailable: boolean
}

export interface UniversitySearchCriteria {
  province: string
  q: string
  page: number
  size: number
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
