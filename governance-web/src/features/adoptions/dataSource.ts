import type { AdoptionAnimalOption, AdoptionListingPage, AdoptionListingSummary, AdoptionSearchCriteria, AdoptionSearchResult, GovernanceAdoptionApplication } from './types'

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
  listings?(status: string, page: number, size: number): Promise<AdoptionListingPage>
  listing?(listingId: string): Promise<AdoptionListingSummary>
  animals?(campusId: string, keyword: string): Promise<AdoptionAnimalOption[]>
  createListing?(animalId: string, title: string, description: string): Promise<AdoptionListingSummary>
  publishListing?(listingId: string): Promise<AdoptionListingSummary>
}
