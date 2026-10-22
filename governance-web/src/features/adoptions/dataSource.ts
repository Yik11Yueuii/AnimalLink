import type { AdoptionSearchCriteria, AdoptionSearchResult, GovernanceAdoptionApplication } from './types'

export interface AdoptionGovernanceDataSource {
  search(criteria: AdoptionSearchCriteria): Promise<AdoptionSearchResult>
  getDetail(applicationId: string): Promise<GovernanceAdoptionApplication>
}
