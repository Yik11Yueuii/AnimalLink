import { resolveCurrentCampusContext } from '../../services/campusContext'
import { getCommunityDataSource, isCommunityOfflineDemo, isPublicDataApiMode } from '../../services/sourceFactory'
import { CommunityFeed } from '../../state/communityFeed'
import { getSelectedCampus } from '../../state/campus'
import type { CampusResponse } from '../../types/api'
import type { CommunityPostView } from '../../utils/communityPresentation'
import { toCommunityPostView } from '../../utils/communityPresentation'

const feed = new CommunityFeed(getCommunityDataSource(), 3)

Page({
  campusResolutionVersion: 0,
  data: {
    offline: isCommunityOfflineDemo(),
    campus: null as CampusResponse | null,
    posts: [] as CommunityPostView[],
    total: 0,
    loading: false,
    loadingMore: false,
    hasMore: false,
    campusValidationFailed: false,
    errorMessage: '',
  },
  onShow() {
    const campus = getSelectedCampus()
    if (!campus) {
      wx.navigateTo({ url: '/pages/campus/index?returnTo=circle' })
      return
    }
    if (!isPublicDataApiMode()) { this.activateCampus(campus); return }
    const version = ++this.campusResolutionVersion
    if (campus.id !== this.data.campus?.id) {
      feed.selectCampus('')
      this.setData({ campus, posts: [], total: 0, loading: true, campusValidationFailed: false, errorMessage: '' })
    }
    void resolveCurrentCampusContext().then((resolution) => {
      if (version !== this.campusResolutionVersion || resolution.status === 'superseded') return
      if (resolution.status === 'missing') {
        feed.selectCampus('')
        this.setData({ campus: null, posts: [], total: 0, loading: false, campusValidationFailed: false, errorMessage: '' })
        wx.navigateTo({ url: '/pages/campus/index?returnTo=circle' })
        return
      }
      if (resolution.status === 'unavailable') {
        this.setData({ loading: false, campusValidationFailed: true, errorMessage: '暂时无法验证当前高校，请检查网络后重试。' })
        return
      }
      this.activateCampus(resolution.campus)
    })
  },
  onHide() { this.campusResolutionVersion += 1 },
  onUnload() { this.campusResolutionVersion += 1 },
  onPullDownRefresh() {
    void this.refresh().finally(() => wx.stopPullDownRefresh())
  },
  changeCampus() {
    wx.navigateTo({ url: '/pages/campus/index?returnTo=circle' })
  },
  openPost(event: WechatMiniprogram.TouchEvent) {
    const postId = String(event.currentTarget.dataset.id ?? '')
    if (postId) wx.navigateTo({ url: `/pages/post-detail/index?postId=${encodeURIComponent(postId)}` })
  },
  openAnimal(event: WechatMiniprogram.TouchEvent) {
    const animalId = String(event.currentTarget.dataset.id ?? '')
    if (animalId) wx.navigateTo({ url: `/pages/animal-detail/index?animalId=${encodeURIComponent(animalId)}` })
  },
  onRetry() {
    if (this.data.campusValidationFailed) { this.onShow(); return }
    void this.refresh()
  },
  loadMore() {
    void this.loadNextPage()
  },
  activateCampus(campus: CampusResponse) {
    if (campus.id !== feed.snapshot.campusId) {
      feed.selectCampus(campus.id)
      this.setData({ campus, posts: [], total: 0, campusValidationFailed: false, errorMessage: '' })
      void this.refresh()
      return
    }
    this.setData({ campus, campusValidationFailed: false })
    this.applySnapshot(feed.snapshot)
  },
  async refresh() {
    const pending = feed.refresh()
    this.applySnapshot(feed.snapshot)
    this.applySnapshot(await pending)
  },
  async loadNextPage() {
    const pending = feed.loadMore()
    this.applySnapshot(feed.snapshot)
    this.applySnapshot(await pending)
  },
  applySnapshot(snapshot: ReturnType<typeof feedSnapshot>) {
    this.setData({
      posts: snapshot.items.map(toCommunityPostView),
      total: snapshot.total,
      loading: snapshot.loading,
      loadingMore: snapshot.loadingMore,
      hasMore: snapshot.hasMore,
      errorMessage: snapshot.errorMessage,
    })
  },
})

function feedSnapshot() {
  return feed.snapshot
}
