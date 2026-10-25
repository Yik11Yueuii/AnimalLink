import axios from 'axios'
import { AuthenticationApiError, authenticatedAdminMutation } from '@/api/authentication'
import { gatewayClient } from '@/api/client'
import type {
  CommunityReviewComment,
  CommunityReviewCriteria,
  CommunityReviewPage,
  CommunityReviewPost,
  ReviewDecision,
} from './types'

export type CommunityReviewFailure = 'unauthenticated' | 'forbidden' | 'conflict' | 'unavailable' | 'invalid-response'

export class CommunityReviewApiError extends Error {
  constructor(public readonly kind: CommunityReviewFailure, public readonly status?: number) {
    super(kind)
    this.name = 'CommunityReviewApiError'
  }
}

const basePath = '/animal/api/v1/admin/community-review'

const asApiError = (error: unknown): CommunityReviewApiError => {
  if (error instanceof CommunityReviewApiError) return error
  if (error instanceof AuthenticationApiError) {
    return new CommunityReviewApiError(error.kind === 'rate-limited' ? 'unavailable' : error.kind, error.status)
  }
  if (!axios.isAxiosError(error) || !error.response || error.code === 'ECONNABORTED') {
    return new CommunityReviewApiError('unavailable')
  }
  const status = error.response.status
  if (status === 401) return new CommunityReviewApiError('unauthenticated', status)
  if (status === 403) return new CommunityReviewApiError('forbidden', status)
  if (status === 409) return new CommunityReviewApiError('conflict', status)
  return new CommunityReviewApiError('unavailable', status)
}

const validPage = <T>(value: unknown): value is CommunityReviewPage<T> => {
  if (!value || typeof value !== 'object') return false
  const page = value as Partial<CommunityReviewPage<T>>
  return Array.isArray(page.items) && Number.isInteger(page.page) && Number.isInteger(page.size)
    && typeof page.total === 'number' && page.total >= 0
}

const params = (criteria: CommunityReviewCriteria) => ({
  reviewStatus: criteria.reviewStatus,
  campusId: criteria.campusId.trim() || undefined,
  page: criteria.page,
  size: criteria.size,
  sort: criteria.sort,
})

export const listReviewPosts = async (criteria: CommunityReviewCriteria): Promise<CommunityReviewPage<CommunityReviewPost>> => {
  try {
    const { data } = await gatewayClient.get<unknown>(`${basePath}/posts`, { params: params(criteria) })
    if (!validPage<CommunityReviewPost>(data)) throw new CommunityReviewApiError('invalid-response')
    return data
  } catch (error) {
    throw asApiError(error)
  }
}

export const getReviewPost = async (postId: string): Promise<CommunityReviewPost> => {
  try {
    const { data } = await gatewayClient.get<CommunityReviewPost>(`${basePath}/posts/${encodeURIComponent(postId)}`)
    return data
  } catch (error) {
    throw asApiError(error)
  }
}

export const listReviewComments = async (criteria: CommunityReviewCriteria): Promise<CommunityReviewPage<CommunityReviewComment>> => {
  try {
    const { data } = await gatewayClient.get<unknown>(`${basePath}/comments`, { params: params(criteria) })
    if (!validPage<CommunityReviewComment>(data)) throw new CommunityReviewApiError('invalid-response')
    return data
  } catch (error) {
    throw asApiError(error)
  }
}

export const getReviewComment = async (commentId: string): Promise<CommunityReviewComment> => {
  try {
    const { data } = await gatewayClient.get<CommunityReviewComment>(`${basePath}/comments/${encodeURIComponent(commentId)}`)
    return data
  } catch (error) {
    throw asApiError(error)
  }
}

export const reviewPost = async (postId: string, decision: ReviewDecision, reason: string): Promise<CommunityReviewPost> => {
  try {
    return await authenticatedAdminMutation<CommunityReviewPost>(`${basePath}/posts/${encodeURIComponent(postId)}/${decision}`, { reason })
  } catch (error) {
    throw asApiError(error)
  }
}

export const reviewComment = async (commentId: string, decision: ReviewDecision, reason: string): Promise<CommunityReviewComment> => {
  try {
    return await authenticatedAdminMutation<CommunityReviewComment>(`${basePath}/comments/${encodeURIComponent(commentId)}/${decision}`, { reason })
  } catch (error) {
    throw asApiError(error)
  }
}
