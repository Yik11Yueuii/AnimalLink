import { getCommunityDataSource, isCommunityOfflineDemo } from '../../services/sourceFactory'
import { resolveCurrentCampusContext } from '../../services/campusContext'
import { CommentThread } from '../../state/communityFeed'
import { getSelectedCampus } from '../../state/campus'
import type { CommunityPostView, CommunityCommentView } from '../../utils/communityPresentation'
import { toCommunityCommentView, toCommunityPostView } from '../../utils/communityPresentation'
import { readableError } from '../../utils/presentation'

const source = getCommunityDataSource()
const comments = new CommentThread(source, 3)

Page({
  detailRequestVersion: 0,
  data: {
    offline: isCommunityOfflineDemo(),
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
  onUnload() { this.detailRequestVersion += 1 },
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
    const requestVersion = ++this.detailRequestVersion
    this.setData({ loading: true, errorMessage: '', comments: [], commentTotal: 0, commentsError: '' })
    try {
      const resolution = await resolveCurrentCampusContext()
      if (requestVersion !== this.detailRequestVersion || resolution.status === 'superseded') return
      if (resolution.status === 'missing') throw new Error('当前高校已失效，请重新选择高校。')
      if (resolution.status === 'unavailable') throw new Error('暂时无法验证当前高校，请检查网络后重试。')
      const post = await source.getPost(this.data.postId)
      if (requestVersion !== this.detailRequestVersion || getSelectedCampus()?.id !== resolution.campus.id) return
      if (post.campusId !== resolution.campus.id) throw new Error('该动态不属于当前高校。')
      this.setData({ post: toCommunityPostView(post) })
      const commentPending = comments.refresh()
      this.applyComments(comments.snapshot)
      const snapshot = await commentPending
      if (requestVersion === this.detailRequestVersion && getSelectedCampus()?.id === resolution.campus.id) this.applyComments(snapshot)
    } catch (error) {
      if (requestVersion === this.detailRequestVersion) this.setData({ post: null, errorMessage: readableError(error) })
    } finally {
      if (requestVersion === this.detailRequestVersion) this.setData({ loading: false })
    }
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
