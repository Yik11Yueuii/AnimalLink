import type { AdoptionStatus, AnimalSpecies, IdentityStatus, PageResponse } from './api'

export type PostType = 'CAMPUS_POST' | 'RESCUE_UPDATE' | 'ADOPTION' | 'POST_ADOPTION'

export interface CommunityAuthorResponse {
  id: string
  displayName: string
}

export interface CommunityAnimalCardResponse {
  id: string
  displayName: string
  species: AnimalSpecies
  identityStatus: IdentityStatus
  adoptionStatus: AdoptionStatus
  coverObjectKey: string | null
}

export interface CommunityMediaResponse {
  id: string
  objectKey: string
  contentType: string
  mediaType: string
  sizeBytes: number | null
  sortOrder: number
}

export interface CommunityPostResponse {
  id: string
  campusId: string
  postType: PostType
  textContent: string
  author: CommunityAuthorResponse
  animal: CommunityAnimalCardResponse | null
  media: CommunityMediaResponse[]
  likeCount: number
  commentCount: number
  likedByMe: boolean
  createdAt: string
  updatedAt: string
}

export interface CommunityCommentResponse {
  id: string
  postId: string
  content: string
  author: CommunityAuthorResponse
  createdAt: string
}

export type CommunityPostPage = PageResponse<CommunityPostResponse>
export type CommunityCommentPage = PageResponse<CommunityCommentResponse>
