import { getRescueDataSource, isRescueOfflineDemo } from '../../services/sourceFactory'
import { RescueCatalog } from '../../state/rescueCatalog'
import { getSelectedCampus } from '../../state/campus'
import type { CampusResponse } from '../../types/api'
import type { PublicRescueItem, PublicRescueStatus } from '../../types/rescue'

interface RescueCard extends PublicRescueItem { statusText: string; updatedText: string }
const options: Array<{ label: string; value: PublicRescueStatus | '' }> = [
  { label: '全部状态', value: '' }, { label: '等待处理', value: 'WAITING_CLAIM' },
  { label: '处理中', value: 'IN_PROGRESS' }, { label: '结果已登记', value: 'RESOLVED' },
  { label: '已完成', value: 'CLOSED' }, { label: '未解决结束', value: 'CLOSED_UNRESOLVED' }, { label: '已取消', value: 'CANCELLED' },
]
const labels: Record<PublicRescueStatus, string> = { WAITING_CLAIM: '等待处理', IN_PROGRESS: '处理中', RESOLVED: '结果已登记', CLOSED: '已完成', CLOSED_UNRESOLVED: '未解决结束', CANCELLED: '已取消' }
const dateText = (value: string) => value ? value.replace('T', ' ').slice(0, 16) : '时间暂未公开'
const catalog = new RescueCatalog(getRescueDataSource(), 3)

Page({
  data: { offline: isRescueOfflineDemo(), campus: null as CampusResponse | null, statusIndex: 0, statusLabels: options.map((item) => item.label), rescues: [] as RescueCard[], total: 0, loading: false, loadingMore: false, hasMore: false, errorMessage: '' },
  onShow() {
    const campus = getSelectedCampus()
    if (!campus) { wx.navigateTo({ url: '/pages/campus/index?returnTo=rescue' }); return }
    if (campus.id !== this.data.campus?.id) { catalog.selectCampus(campus.id); this.setData({ campus, rescues: [], total: 0, errorMessage: '' }); void this.refresh() }
  },
  onPullDownRefresh() { void this.refresh().finally(() => wx.stopPullDownRefresh()) },
  changeCampus() { wx.navigateTo({ url: '/pages/campus/index?returnTo=rescue' }) },
  reportRescue() { wx.navigateTo({ url: '/pages/rescue-report/index' }) },
  onStatusChange(event: WechatMiniprogram.PickerChange) { const statusIndex = Number(event.detail.value); catalog.setStatus(options[statusIndex]?.value ?? ''); this.setData({ statusIndex }); void this.refresh() },
  openDetail(event: WechatMiniprogram.TouchEvent) { const eventId = String(event.currentTarget.dataset.id ?? ''); const campusId = this.data.campus?.id; if (eventId && campusId) wx.navigateTo({ url: `/pages/rescue-detail/index?eventId=${encodeURIComponent(eventId)}&campusId=${encodeURIComponent(campusId)}` }) },
  onRetry() { void this.refresh() },
  loadMore() { void this.loadNextPage() },
  async refresh() { const pending = catalog.refresh(); this.applySnapshot(catalog.snapshot); this.applySnapshot(await pending) },
  async loadNextPage() { const pending = catalog.loadMore(); this.applySnapshot(catalog.snapshot); this.applySnapshot(await pending) },
  applySnapshot(snapshot: ReturnType<typeof rescueSnapshot>) { this.setData({ rescues: snapshot.items.map((item) => ({ ...item, statusText: labels[item.rescueStatus], updatedText: dateText(item.updatedAt) })), total: snapshot.total, loading: snapshot.loading, loadingMore: snapshot.loadingMore, hasMore: snapshot.hasMore, errorMessage: snapshot.errorMessage }) },
})
function rescueSnapshot() { return catalog.snapshot }
