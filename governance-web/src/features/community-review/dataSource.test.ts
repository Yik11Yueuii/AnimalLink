import { beforeEach, describe, expect, it, vi } from 'vitest'
import { gatewayClient } from '@/api/client'
import { resetAuthenticationApiForTests } from '@/api/authentication'
import { CommunityReviewApiError, listReviewPosts, reviewPost } from './dataSource'

describe('community review API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    resetAuthenticationApiForTests()
  })

  it('uses the exact posts path and only sends defined filters', async () => {
    const get = vi.spyOn(gatewayClient, 'get').mockResolvedValue({ data: { items: [], page: 0, size: 20, total: 0 } })
    await listReviewPosts({ reviewStatus: 'PENDING', campusId: ' ', page: 0, size: 20, sort: 'NEWEST' })
    expect(get).toHaveBeenCalledWith('/animal/api/v1/admin/community-review/posts', {
      params: { reviewStatus: 'PENDING', campusId: undefined, page: 0, size: 20, sort: 'NEWEST' },
    })
  })

  it('obtains the server CSRF token and sends its declared header for an approval', async () => {
    vi.spyOn(gatewayClient, 'get').mockResolvedValueOnce({ data: { token: 'server-bound-csrf-token', headerName: 'X-SERVER-CSRF' } })
    const post = vi.spyOn(gatewayClient, 'post').mockResolvedValue({ data: { postId: 'post-1' } })
    await reviewPost('post-1', 'approve', '公开内容符合规范')
    expect(post).toHaveBeenCalledWith('/animal/api/v1/admin/community-review/posts/post-1/approve',
      { reason: '公开内容符合规范' }, { headers: { 'X-SERVER-CSRF': 'server-bound-csrf-token' } })
    expect(gatewayClient.defaults.withCredentials).toBe(true)
  })

  it.each([[401, 'unauthenticated'], [403, 'forbidden'], [409, 'conflict'], [503, 'unavailable']] as const)(
    'preserves HTTP %s as %s', async (status, kind) => {
      vi.spyOn(gatewayClient, 'get').mockRejectedValue({ isAxiosError: true, response: { status } })
      await expect(listReviewPosts({ reviewStatus: 'PENDING', campusId: '', page: 0, size: 20, sort: 'NEWEST' }))
        .rejects.toMatchObject<Partial<CommunityReviewApiError>>({ kind, status })
    },
  )
})
