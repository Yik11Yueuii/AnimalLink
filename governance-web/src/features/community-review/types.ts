export type CommunityReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type CommunityPostStatus = 'ACTIVE' | 'HIDDEN' | 'REMOVED'
export type CommunityCommentStatus = 'ACTIVE' | 'HIDDEN' | 'REMOVED'
export type CommunityPostType = 'CAMPUS_POST' | 'RESCUE_UPDATE' | 'ADOPTION' | 'POST_ADOPTION'
export type CommunityReviewSort = 'NEWEST' | 'OLDEST'
export type ReviewDecision = 'approve' | 'reject'

export interface CommunityReviewPage<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface CommunityReviewPost {
  postId: string
  campusId: string
  animalId: string | null
  postType: CommunityPostType
  textContent: string
  status: CommunityPostStatus
  reviewStatus: CommunityReviewStatus
  reviewedAt: string | null
  version: number
  likeCount: number
  activeCommentCount: number
  hiddenCommentCount: number
  createdAt: string
  updatedAt: string
}

export interface CommunityReviewComment {
  commentId: string
  postId: string
  campusId: string
  content: string
  status: CommunityCommentStatus
  reviewStatus: CommunityReviewStatus
  reviewedAt: string | null
  parentPostStatus: CommunityPostStatus
  createdAt: string
  updatedAt: string
}

export interface CommunityReviewCriteria {
  reviewStatus: CommunityReviewStatus
  campusId: string
  page: number
  size: number
  sort: CommunityReviewSort
}

export const reviewStatusLabels: Record<CommunityReviewStatus, string> = {
  PENDING: '待审核',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
}

export const postTypeLabels: Record<CommunityPostType, string> = {
  CAMPUS_POST: '校园动态',
  RESCUE_UPDATE: '救助进展',
  ADOPTION: '领养动态',
  POST_ADOPTION: '领养后动态',
}
