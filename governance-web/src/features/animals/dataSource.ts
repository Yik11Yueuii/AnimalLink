import type { AnimalSearchCriteria, AnimalSearchResult, GovernanceAnimal } from './types'

export interface AnimalGovernanceDataSource {
  search(criteria: AnimalSearchCriteria): Promise<AnimalSearchResult>
  getDetail(animalId: string): Promise<GovernanceAnimal>
}
