import { getCommunityDataSource, isOfflineDemo } from '../../services/sourceFactory'
import { CommunityFeed } from '../../state/communityFeed'
import { getSelectedCampus } from '../../state/campus'
import type { CampusResponse } from '../../types/api'
import type { CommunityPostView } from '../../utils/communityPresentation'
import { toCommunityPostView } from '../../utils/communityPresentation'

const feed = new CommunityFeed(getCommunityDataSource(), 3)

Page({
  data: {
    offline: isOfflineDemo(),
    campus: null as CampusResponse | null,
    posts: [] as CommunityPostView[],
    total: 0,
    loading: false,
    loadingMore: false,
    hasMore: false,
    errorMessage: '',
  },
  onShow() {
    const campus = getSelectedCampus()
    if (!campus) {
      wx.navigateTo({ url: '/pages/campus/index?returnTo=circle' })
      return
    }
    if (campus.id !== this.data.campus?.id) {
      feed.selectCampus(campus.id)
      this.setData({ campus, posts: [], total: 0, errorMessage: '' })
      void this.refresh()
      return
    }
    this.applySnapshot(feed.snapshot)
  },
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
    void this.refresh()
  },
  loadMore() {
    void this.loadNextPage()
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
