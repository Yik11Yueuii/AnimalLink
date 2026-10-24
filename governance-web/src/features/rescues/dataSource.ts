import type { RescueDetail, RescueSearchCriteria, RescueSearchResult } from './types'
export interface RescueGovernanceDataSource {
  search(criteria: RescueSearchCriteria): Promise<RescueSearchResult>
  getDetail(eventId: string): Promise<RescueDetail>
}
