import type { AdoptionStatus, AnimalSpecies, CurrentContext, PageResponse } from './api'

export type PostType = 'CAMPUS_POST' | 'RESCUE_UPDATE' | 'ADOPTION' | 'POST_ADOPTION'

export interface CommunityAnimalCardResponse {
  id: string
  displayName: string
  species: AnimalSpecies
  adoptionStatus: AdoptionStatus
  currentContext: CurrentContext
}

export interface CommunityPostResponse {
  id: string
  campusId: string
  postType: PostType
  textContent: string
  authorDisplayName: string
  animal: CommunityAnimalCardResponse | null
  likeCount: number
  commentCount: number
  createdAt: string
  updatedAt: string
}

export interface CommunityCommentResponse {
  id: string
  postId: string
  content: string
  authorDisplayName: string
  createdAt: string
}

export type CommunityPostPage = PageResponse<CommunityPostResponse>
export type CommunityCommentPage = PageResponse<CommunityCommentResponse>
