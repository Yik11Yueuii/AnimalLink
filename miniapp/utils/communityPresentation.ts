import type { CommunityCommentResponse, CommunityPostResponse, PostType } from '../types/community'
import { speciesLabel } from './presentation'

export const postTypeLabel: Record<PostType, string> = {
  CAMPUS_POST: '校园日常',
  RESCUE_UPDATE: '救助进展',
  ADOPTION: '领养信息',
  POST_ADOPTION: '领养后近况',
}

export interface CommunityPostView extends CommunityPostResponse {
  typeText: string
  createdText: string
  authorInitial: string
  animalSpeciesText: string
  hasMediaMetadata: boolean
}

export interface CommunityCommentView extends CommunityCommentResponse {
  createdText: string
  authorInitial: string
}

const initial = (name: string) => name.trim().slice(0, 1) || 'A'

const formatDateTime = (value: string) => {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export const toCommunityPostView = (post: CommunityPostResponse): CommunityPostView => ({
  ...post,
  typeText: postTypeLabel[post.postType],
  createdText: formatDateTime(post.createdAt),
  authorInitial: initial(post.author.displayName),
  animalSpeciesText: post.animal ? speciesLabel[post.animal.species] : '',
  hasMediaMetadata: post.media.length > 0,
})

export const toCommunityCommentView = (comment: CommunityCommentResponse): CommunityCommentView => ({
  ...comment,
  createdText: formatDateTime(comment.createdAt),
  authorInitial: initial(comment.author.displayName),
})
