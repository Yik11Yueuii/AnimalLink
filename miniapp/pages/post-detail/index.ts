import { getCommunityDataSource, isOfflineDemo } from '../../services/sourceFactory'
import { CommentThread } from '../../state/communityFeed'
import type { CommunityPostView, CommunityCommentView } from '../../utils/communityPresentation'
import { toCommunityCommentView, toCommunityPostView } from '../../utils/communityPresentation'
import { readableError } from '../../utils/presentation'

const source = getCommunityDataSource()
const comments = new CommentThread(source, 3)

Page({
  data: {
    offline: isOfflineDemo(),
    postId: '',
    post: null as CommunityPostView | null,
    loading: true,
    errorMessage: '',
    comments: [] as CommunityCommentView[],
    commentTotal: 0,
    commentsLoading: false,
    commentsLoadingMore: false,
    commentsError: '',
    commentsHasMore: false,
  },
  onLoad(options: Record<string, string | undefined>) {
    const postId = options.postId ? decodeURIComponent(options.postId) : ''
    comments.selectPost(postId)
    this.setData({ postId })
    void this.loadAll()
  },
  onRetry() {
    void this.loadAll()
  },
  loadMoreComments() {
    void this.loadNextComments()
  },
  openAnimal(event: WechatMiniprogram.TouchEvent) {
    const animalId = String(event.currentTarget.dataset.id ?? '')
    if (animalId) wx.navigateTo({ url: `/pages/animal-detail/index?animalId=${encodeURIComponent(animalId)}` })
  },
  async loadAll() {
    if (!this.data.postId) {
      this.setData({ loading: false, errorMessage: '缺少 Post ID。' })
      return
    }
    this.setData({ loading: true, errorMessage: '' })
    const commentPending = comments.refresh()
    this.applyComments(comments.snapshot)
    try {
      const post = await source.getPost(this.data.postId)
      this.setData({ post: toCommunityPostView(post) })
    } catch (error) {
      this.setData({ post: null, errorMessage: readableError(error) })
    } finally {
      this.setData({ loading: false })
    }
    this.applyComments(await commentPending)
  },
  async loadNextComments() {
    const pending = comments.loadMore()
    this.applyComments(comments.snapshot)
    this.applyComments(await pending)
  },
  applyComments(snapshot: ReturnType<typeof commentSnapshot>) {
    this.setData({
      comments: snapshot.items.map(toCommunityCommentView),
      commentTotal: snapshot.total,
      commentsLoading: snapshot.loading,
      commentsLoadingMore: snapshot.loadingMore,
      commentsError: snapshot.errorMessage,
      commentsHasMore: snapshot.hasMore,
    })
  },
})

function commentSnapshot() {
  return comments.snapshot
}
