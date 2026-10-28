export type AdoptionApplicationStatus = 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
export type AdoptionListingStatus = 'DRAFT' | 'PUBLISHED' | 'CLOSED'
export type AdoptionSort = 'UPDATED_DESC' | 'CREATED_DESC' | 'CREATED_ASC'

export interface AdoptionListingSummary {
  listingId: string
  animalId: string
  status: AdoptionListingStatus
  title: string
  description: string
  createdAt: string
  updatedAt: string
  publishedAt: string | null
  closedAt: string | null
}

export interface GovernanceAdoptionApplication {
  applicationId: string
  listingId: string
  applicantReference: string
  status: AdoptionApplicationStatus
  message: string
  createdAt: string
  updatedAt: string
  reviewerReference: string | null
  reviewedAt: string | null
  reviewComment: string | null
  listing: AdoptionListingSummary
}

export interface AdoptionSearchCriteria {
  keyword: string
  status: AdoptionApplicationStatus | ''
  sort: AdoptionSort
  page: number
  size: number
}

export type AdoptionStatusCounts = Record<AdoptionApplicationStatus, number>

export interface AdoptionSearchResult {
  items: GovernanceAdoptionApplication[]
  page: number
  size: number
  total: number
  statusCounts: AdoptionStatusCounts
}

export const adoptionApplicationStatusLabels: Record<AdoptionApplicationStatus, string> = {
  SUBMITTED: '待审核',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  WITHDRAWN: '已撤回',
}

export const adoptionListingStatusLabels: Record<AdoptionListingStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  CLOSED: '已关闭',
}
