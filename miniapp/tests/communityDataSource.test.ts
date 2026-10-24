import assert from 'node:assert/strict'
import test from 'node:test'
import { ApiCommunityDataSource } from '../services/communityDataSource'
import { OfflineCommunityDataSource, offlineCommunityPosts } from '../services/offlineCommunityDataSource'
import { getCommunityDataSource, isCommunityOfflineDemo } from '../services/sourceFactory'
import type { JsonRequester, QueryValue } from '../services/apiClient'

test('community API adapter uses public GET paths and zero-based pagination', async () => {
  const calls: Array<{ path: string; query?: Record<string, QueryValue> }> = []
  const request: JsonRequester = async <T>(path: string, query?: Record<string, QueryValue>) => {
    calls.push({ path, query })
    if (path.endsWith('/feed') || path.endsWith('/comments')) return { items: [], page: 0, size: 3, total: 0 } as T
    return offlineCommunityPosts[0] as T
  }
  const source = new ApiCommunityDataSource(request)
  await source.getFeed('campus/a', 0, 3)
  await source.getPost('post/a')
  await source.getComments('post/a', 1, 3)
  assert.deepEqual(calls, [
    { path: '/animal/api/v1/campuses/campus%2Fa/feed', query: { page: 0, size: 3 } },
    { path: '/animal/api/v1/posts/post%2Fa', query: undefined },
    { path: '/animal/api/v1/posts/post%2Fa/comments', query: { page: 1, size: 3 } },
  ])
})

test('offline feed covers all PostType values and optional Animal relation', async () => {
  const types = new Set(offlineCommunityPosts.map((post) => post.postType))
  assert.deepEqual([...types].sort(), ['ADOPTION', 'CAMPUS_POST', 'POST_ADOPTION', 'RESCUE_UPDATE'])
  assert.ok(offlineCommunityPosts.some((post) => post.animal === null))
  assert.ok(offlineCommunityPosts.some((post) => post.animal !== null))
  const source = new OfflineCommunityDataSource()
  const first = await source.getFeed('campus-mingde', 0, 3)
  const second = await source.getFeed('campus-mingde', 1, 3)
  assert.equal(first.total, 5)
  assert.equal(first.items.length, 3)
  assert.equal(second.items.length, 2)
  assert.ok(first.items[0].createdAt > first.items[1].createdAt)
})

test('offline post detail and comments remain separate paged reads', async () => {
  const source = new OfflineCommunityDataSource()
  const detail = await source.getPost('post-mingde-5')
  const first = await source.getComments(detail.id, 0, 3)
  const second = await source.getComments(detail.id, 1, 3)
  assert.equal(detail.commentCount, 5)
  assert.equal(first.total, 5)
  assert.deepEqual([...first.items, ...second.items].map((comment) => comment.id), [
    'comment-1', 'comment-2', 'comment-3', 'comment-4', 'comment-5',
  ])
})

test('runtime source selection is explicit offline mode with no API fallback', () => {
  assert.equal(isCommunityOfflineDemo(), true)
  assert.ok(getCommunityDataSource() instanceof OfflineCommunityDataSource)
})
