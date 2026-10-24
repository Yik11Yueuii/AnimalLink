import assert from 'node:assert/strict'
import test from 'node:test'
import type { CommunityDataSource } from '../services/communityDataSource'
import { CommentThread, CommunityFeed } from '../state/communityFeed'
import type { CommunityCommentPage, CommunityPostPage, CommunityPostResponse } from '../types/community'

const post = (id: string, campusId: string): CommunityPostResponse => ({
  id,
  campusId,
  postType: 'CAMPUS_POST',
  textContent: id,
  author: { id: 'author', displayName: '测试作者' },
  animal: null,
  media: [],
  likeCount: 0,
  commentCount: 0,
  likedByMe: false,
  createdAt: '2026-10-22T00:00:00Z',
  updatedAt: '2026-10-22T00:00:00Z',
})

const source = (feed: (campusId: string, page: number, size: number) => Promise<CommunityPostPage>, comments?: (postId: string, page: number, size: number) => Promise<CommunityCommentPage>): CommunityDataSource => ({
  getFeed: feed,
  getPost: async (postId) => post(postId, 'campus-a'),
  getComments: comments ?? (async (_postId, page, size) => ({ items: [], page, size, total: 0 })),
})

test('feed refreshes page zero and appends subsequent pages', async () => {
  const pages: number[] = []
  const feed = new CommunityFeed(source(async (campusId, page, size) => {
    pages.push(page)
    return { items: [post(`${campusId}-${page}`, campusId)], page, size, total: 2 }
  }), 1)
  feed.selectCampus('campus-a')
  await feed.refresh()
  await feed.loadMore()
  assert.deepEqual(pages, [0, 1])
  assert.deepEqual(feed.snapshot.items.map((item) => item.id), ['campus-a-0', 'campus-a-1'])
})

test('campus switch clears feed and ignores stale response', async () => {
  let resolveFirst: ((page: CommunityPostPage) => void) | undefined
  const feed = new CommunityFeed(source((campusId, page, size) => campusId === 'campus-a'
    ? new Promise((resolve) => { resolveFirst = resolve })
    : Promise.resolve({ items: [post('b-new', campusId)], page, size, total: 1 })))
  feed.selectCampus('campus-a')
  const stale = feed.refresh()
  feed.selectCampus('campus-b')
  assert.equal(feed.snapshot.items.length, 0)
  await feed.refresh()
  resolveFirst?.({ items: [post('a-stale', 'campus-a')], page: 0, size: 3, total: 1 })
  await stale
  assert.deepEqual(feed.snapshot.items.map((item) => item.id), ['b-new'])
})

test('feed error remains visible and retry can recover', async () => {
  let attempts = 0
  const feed = new CommunityFeed(source(async (campusId, page, size) => {
    attempts += 1
    if (attempts === 1) throw new Error('公共 Feed 暂不可用')
    return { items: [post('recovered', campusId)], page, size, total: 1 }
  }))
  feed.selectCampus('campus-a')
  await feed.refresh()
  assert.equal(feed.snapshot.errorMessage, '公共 Feed 暂不可用')
  await feed.refresh()
  assert.equal(feed.snapshot.errorMessage, '')
  assert.equal(feed.snapshot.items[0].id, 'recovered')
})

test('comment thread paginates and resets when post changes', async () => {
  const thread = new CommentThread(source(async (_campusId, page, size) => ({ items: [], page, size, total: 0 }), async (postId, page, size) => ({
    items: [{ id: `${postId}-${page}`, postId, content: '评论', author: { id: 'u', displayName: '用户' }, createdAt: '2026-10-22T00:00:00Z' }],
    page,
    size,
    total: 2,
  })), 1)
  thread.selectPost('post-a')
  await thread.refresh()
  await thread.loadMore()
  assert.deepEqual(thread.snapshot.items.map((item) => item.id), ['post-a-0', 'post-a-1'])
  thread.selectPost('post-b')
  assert.equal(thread.snapshot.items.length, 0)
  await thread.refresh()
  assert.deepEqual(thread.snapshot.items.map((item) => item.id), ['post-b-0'])
})
