import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  listReviewPosts: vi.fn(), listReviewComments: vi.fn(), getReviewPost: vi.fn(), getReviewComment: vi.fn(), reviewPost: vi.fn(), reviewComment: vi.fn(),
}))

vi.mock('./dataSource', async (importOriginal) => ({ ...(await importOriginal<typeof import('./dataSource')>()), ...api }))

import { useCommunityReview } from './useCommunityReview'

const post = { postId: 'post-1', campusId: 'campus-1', animalId: null, postType: 'CAMPUS_POST', textContent: '内容', status: 'ACTIVE', reviewStatus: 'PENDING', reviewedAt: null, version: 0, likeCount: 0, activeCommentCount: 0, hiddenCommentCount: 0, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' } as const
const comment = { commentId: 'comment-1', postId: 'post-1', campusId: 'campus-1', content: '评论', status: 'ACTIVE', reviewStatus: 'PENDING', reviewedAt: null, parentPostStatus: 'ACTIVE', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' } as const
const deferred = <T>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

describe('useCommunityReview', () => {
  beforeEach(() => vi.resetAllMocks())

  it('uses pending posts by default and resets paging when filtering', async () => {
    api.listReviewPosts.mockResolvedValue({ items: [post], page: 0, size: 20, total: 1 })
    const catalog = useCommunityReview()
    await catalog.load()
    expect(api.listReviewPosts).toHaveBeenCalledWith(expect.objectContaining({ reviewStatus: 'PENDING', page: 0 }))
    catalog.criteria.page = 2
    await catalog.changeStatus('APPROVED')
    expect(api.listReviewPosts).toHaveBeenLastCalledWith(expect.objectContaining({ reviewStatus: 'APPROVED', page: 0 }))
  })

  it('switches to the independent comment queue and refreshes after a review', async () => {
    api.listReviewComments.mockResolvedValue({ items: [comment], page: 0, size: 20, total: 1 })
    api.getReviewComment.mockResolvedValue(comment)
    api.reviewComment.mockResolvedValue({ ...comment, reviewStatus: 'APPROVED', reviewedAt: '2026-10-02T00:00:00Z' })
    const catalog = useCommunityReview()
    await catalog.changeTab('comments')
    await catalog.openDetail('comment-1')
    await catalog.review('approve', '符合公开规范')
    expect(api.reviewComment).toHaveBeenCalledWith('comment-1', 'approve', '符合公开规范')
    expect(api.listReviewComments).toHaveBeenCalledTimes(2)
  })

  it('ignores an older queue response after a newer filter request', async () => {
    const older = deferred<{ items: typeof post[]; page: number; size: number; total: number }>()
    const newer = deferred<{ items: typeof post[]; page: number; size: number; total: number }>()
    api.listReviewPosts.mockImplementationOnce(() => older.promise).mockImplementationOnce(() => newer.promise)
    const catalog = useCommunityReview()
    const first = catalog.load()
    const second = catalog.changeStatus('APPROVED')
    newer.resolve({ items: [{ ...post, postId: 'newer', reviewStatus: 'APPROVED' }], page: 0, size: 20, total: 1 })
    await second
    older.resolve({ items: [post], page: 0, size: 20, total: 1 })
    await first
    expect(catalog.items.value[0]?.postId).toBe('newer')
  })
})
