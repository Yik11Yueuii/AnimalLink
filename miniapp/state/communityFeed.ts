import type { CommunityDataSource } from '../services/communityDataSource'
import type { CommunityCommentResponse, CommunityPostResponse } from '../types/community'

const errorText = (error: unknown) => error instanceof Error ? error.message : '校园社区暂时无法访问，请稍后重试。'

interface PageSnapshot<T> {
  items: T[]
  page: number
  size: number
  total: number
  loading: boolean
  loadingMore: boolean
  errorMessage: string
  hasMore: boolean
}

export interface CommunityFeedSnapshot extends PageSnapshot<CommunityPostResponse> {
  campusId: string
}

export class CommunityFeed {
  private campusId = ''
  private page = 0
  private items: CommunityPostResponse[] = []
  private total = 0
  private loading = false
  private loadingMore = false
  private errorMessage = ''
  private generation = 0

  constructor(private readonly source: CommunityDataSource, private readonly size = 3) {}

  selectCampus(campusId: string) {
    if (campusId === this.campusId) return
    this.campusId = campusId
    this.invalidate()
  }

  refresh() {
    return this.load(0, false)
  }

  loadMore() {
    if (!this.campusId || this.loading || this.loadingMore || !this.snapshot.hasMore) return Promise.resolve(this.snapshot)
    return this.load(this.page + 1, true)
  }

  get snapshot(): CommunityFeedSnapshot {
    return {
      campusId: this.campusId,
      items: [...this.items],
      page: this.page,
      size: this.size,
      total: this.total,
      loading: this.loading,
      loadingMore: this.loadingMore,
      errorMessage: this.errorMessage,
      hasMore: this.items.length < this.total,
    }
  }

  private async load(page: number, append: boolean) {
    if (!this.campusId) return this.snapshot
    const generation = ++this.generation
    this.loading = !append
    this.loadingMore = append
    this.errorMessage = ''
    try {
      const result = await this.source.getFeed(this.campusId, page, this.size)
      if (generation !== this.generation) return this.snapshot
      this.items = append ? [...this.items, ...result.items] : result.items
      this.page = result.page
      this.total = result.total
    } catch (error) {
      if (generation === this.generation) this.errorMessage = errorText(error)
    } finally {
      if (generation === this.generation) {
        this.loading = false
        this.loadingMore = false
      }
    }
    return this.snapshot
  }

  private invalidate() {
    this.generation += 1
    this.page = 0
    this.items = []
    this.total = 0
    this.loading = false
    this.loadingMore = false
    this.errorMessage = ''
  }
}

export interface CommentThreadSnapshot extends PageSnapshot<CommunityCommentResponse> {
  postId: string
}

export class CommentThread {
  private postId = ''
  private page = 0
  private items: CommunityCommentResponse[] = []
  private total = 0
  private loading = false
  private loadingMore = false
  private errorMessage = ''
  private generation = 0

  constructor(private readonly source: CommunityDataSource, private readonly size = 3) {}

  selectPost(postId: string) {
    if (postId === this.postId) return
    this.postId = postId
    this.generation += 1
    this.page = 0
    this.items = []
    this.total = 0
    this.loading = false
    this.loadingMore = false
    this.errorMessage = ''
  }

  refresh() {
    return this.load(0, false)
  }

  loadMore() {
    if (!this.postId || this.loading || this.loadingMore || !this.snapshot.hasMore) return Promise.resolve(this.snapshot)
    return this.load(this.page + 1, true)
  }

  get snapshot(): CommentThreadSnapshot {
    return {
      postId: this.postId,
      items: [...this.items],
      page: this.page,
      size: this.size,
      total: this.total,
      loading: this.loading,
      loadingMore: this.loadingMore,
      errorMessage: this.errorMessage,
      hasMore: this.items.length < this.total,
    }
  }

  private async load(page: number, append: boolean) {
    if (!this.postId) return this.snapshot
    const generation = ++this.generation
    this.loading = !append
    this.loadingMore = append
    this.errorMessage = ''
    try {
      const result = await this.source.getComments(this.postId, page, this.size)
      if (generation !== this.generation) return this.snapshot
      this.items = append ? [...this.items, ...result.items] : result.items
      this.page = result.page
      this.total = result.total
    } catch (error) {
      if (generation === this.generation) this.errorMessage = errorText(error)
    } finally {
      if (generation === this.generation) {
        this.loading = false
        this.loadingMore = false
      }
    }
    return this.snapshot
  }
}
