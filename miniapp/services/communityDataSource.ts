import type {
  CommunityCommentPage,
  CommunityPostPage,
  CommunityPostResponse,
} from '../types/community'
import type { JsonRequester } from './apiClient'

export interface CommunityDataSource {
  getFeed(campusId: string, page: number, size: number): Promise<CommunityPostPage>
  getPost(postId: string): Promise<CommunityPostResponse>
  getComments(postId: string, page: number, size: number): Promise<CommunityCommentPage>
}

export class ApiCommunityDataSource implements CommunityDataSource {
  constructor(private readonly request: JsonRequester) {}

  getFeed(campusId: string, page: number, size: number) {
    return this.request<CommunityPostPage>(
      `/animal/api/v1/public/campuses/${encodeURIComponent(campusId)}/feed`,
      { page, size },
    )
  }

  getPost(postId: string) {
    return this.request<CommunityPostResponse>(`/animal/api/v1/public/posts/${encodeURIComponent(postId)}`)
  }

  getComments(postId: string, page: number, size: number) {
    return this.request<CommunityCommentPage>(
      `/animal/api/v1/public/posts/${encodeURIComponent(postId)}/comments`,
      { page, size },
    )
  }
}
