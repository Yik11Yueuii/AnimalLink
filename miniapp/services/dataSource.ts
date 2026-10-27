import type {
  AnimalSearchCriteria,
  AnimalSummaryResponse,
  CampusResponse,
  PageResponse,
  PublicAnimalResponse,
  TimelineEntryResponse,
  UniversityResponse,
  UniversitySearchCriteria,
} from '../types/api'
import type { JsonRequester } from './apiClient'

export interface PublicAnimalDataSource {
  listCampuses(q?: string, page?: number, size?: number): Promise<CampusResponse[]>
  searchUniversities(criteria: UniversitySearchCriteria): Promise<PageResponse<UniversityResponse>>
  getUniversityCommunity(universityId: string): Promise<CampusResponse>
  searchAnimals(criteria: AnimalSearchCriteria): Promise<PageResponse<AnimalSummaryResponse>>
  getAnimal(animalId: string): Promise<PublicAnimalResponse>
  getTimeline(animalId: string, page: number, size: number): Promise<PageResponse<TimelineEntryResponse>>
}

export class ApiPublicAnimalDataSource implements PublicAnimalDataSource {
  constructor(private readonly request: JsonRequester) {}

  listCampuses(q = '', page = 0, size = 50) {
    return this.request<CampusResponse[]>('/identity/api/v1/campuses', { q, page, size })
  }

  searchUniversities(criteria: UniversitySearchCriteria) {
    return this.request<PageResponse<UniversityResponse>>('/identity/api/v1/universities', {
      province: criteria.province,
      q: criteria.q,
      page: criteria.page,
      size: criteria.size,
    })
  }

  getUniversityCommunity(universityId: string) {
    return this.request<CampusResponse>(
      `/identity/api/v1/universities/${encodeURIComponent(universityId)}/community`,
    )
  }

  searchAnimals(criteria: AnimalSearchCriteria) {
    return this.request<PageResponse<AnimalSummaryResponse>>('/animal/api/v1/public/animals', {
      campusId: criteria.campusId,
      q: criteria.q,
      species: criteria.species,
      page: criteria.page,
      size: criteria.size,
    })
  }

  getAnimal(animalId: string) {
    return this.request<PublicAnimalResponse>(`/animal/api/v1/public/animals/${encodeURIComponent(animalId)}`)
  }

  getTimeline(animalId: string, page: number, size: number) {
    return this.request<PageResponse<TimelineEntryResponse>>(
      `/animal/api/v1/animals/${encodeURIComponent(animalId)}/timeline`,
      { page, size },
    )
  }
}
