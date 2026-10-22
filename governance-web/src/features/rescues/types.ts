export type RescueStatus = 'WAITING_CLAIM' | 'IN_PROGRESS' | 'COMPLETED'
export type RescueSort = 'UPDATED_DESC' | 'REPORTED_DESC' | 'OCCURRED_DESC'
export type EventStatus = 'REPORTED' | 'VERIFIED' | 'REJECTED'
export type CaseStatus = 'OPEN' | 'ACTIVE' | 'RESOLVED' | 'CLOSED' | 'CLOSED_UNRESOLVED' | 'CANCELLED'
export type ClaimStatus = 'WAITING_CLAIM' | 'CLAIMED'
export type RescueResultCode = 'CONTINUE_OBSERVATION' | 'RESOLVED' | 'UNRESOLVED' | 'CANCELLED'
export interface RescueAnimal { id: string; displayName: string | null; species: string | null; identityStatus: string | null }
export interface RescueListItem {
  eventId: string; caseId: string | null; campusId: string; animalId: string | null
  rescueStatus: RescueStatus; eventStatus: EventStatus; caseStatus: CaseStatus | null
  claimStatus: ClaimStatus | null; hasOwner: boolean; abnormalitySummary: string | null
  description: string; publicLocationDescription: string | null; occurredAt: string; reportedAt: string
  evidenceCount: number; actionCount: number; resultCode: RescueResultCode | null
  resultSummary: string | null; updatedAt: string; animal: RescueAnimal | null
}
export interface RescueProgress { id: string; description: string; occurredAt: string; createdAt: string; resultCode: RescueResultCode | null }
export interface RescueDetail extends RescueListItem {
  claimedAt: string | null; resultSubmittedAt: string | null; closedAt: string | null; progress: RescueProgress[]
}
export interface RescueSearchCriteria {
  keyword: string; campusId: string; rescueStatus: RescueStatus | ''; sort: RescueSort; page: number; size: number
}
export interface RescueSearchResult { items: RescueListItem[]; page: number; size: number; total: number }
export const rescueStatusLabels: Record<RescueStatus, string> = { WAITING_CLAIM: '等待认领', IN_PROGRESS: '处理中', COMPLETED: '已完成' }
export const eventStatusLabels: Record<EventStatus, string> = { REPORTED: '待审核', VERIFIED: '已核实', REJECTED: '已驳回' }
export const caseStatusLabels: Record<CaseStatus, string> = {
  OPEN: '已建案', ACTIVE: '处理中', RESOLVED: '已解决', CLOSED: '已关闭',
  CLOSED_UNRESOLVED: '已关闭 · 未解决', CANCELLED: '已取消',
}
export const claimStatusLabels: Record<ClaimStatus, string> = { WAITING_CLAIM: '等待认领', CLAIMED: '已认领' }
export const resultCodeLabels: Record<RescueResultCode, string> = {
  CONTINUE_OBSERVATION: '继续观察', RESOLVED: '问题已解决', UNRESOLVED: '未能解决', CANCELLED: '已取消',
}
