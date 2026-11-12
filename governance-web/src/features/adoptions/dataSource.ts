import type { AdoptionSearchCriteria, AdoptionSearchResult, GovernanceAdoptionApplication } from './types'

export interface AdoptionGovernanceDataSource {
  search(criteria: AdoptionSearchCriteria): Promise<AdoptionSearchResult>
  getDetail(applicationId: string): Promise<GovernanceAdoptionApplication>
  review?(applicationId: string, decision: 'approve' | 'reject', comment: string): Promise<void>
  selection?(listingId: string): Promise<{ selectionId: string; applicationId: string; status: string } | null>
  select?(listingId: string, applicationId: string): Promise<void>
  handover?(selectionId: string): Promise<{ handoverId: string; status: string; scheduledAt: string; completedAt: string | null } | null>
  initiate?(selectionId: string, scheduledAt: string): Promise<void>
  complete?(handoverId: string): Promise<void>
  cancel?(handoverId: string, reason: string): Promise<void>
}
