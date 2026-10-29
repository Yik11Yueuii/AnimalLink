import { getRescueDataSource, isRescueOfflineDemo } from '../../services/sourceFactory'
import type { PublicRescueDetail, PublicRescueStatus } from '../../types/rescue'
import { readableError } from '../../utils/presentation'

const source = getRescueDataSource()
const labels: Record<PublicRescueStatus, string> = { WAITING_CLAIM: '等待处理', IN_PROGRESS: '处理中', RESOLVED: '结果已登记', CLOSED: '已完成', CLOSED_UNRESOLVED: '未解决结束', CANCELLED: '已取消' }
const dateText = (value: string) => value ? value.replace('T', ' ').slice(0, 16) : '时间暂未公开'

Page({
  data: { offline: isRescueOfflineDemo(), detail: null as PublicRescueDetail | null, statusText: '', loading: true, errorMessage: '' },
  eventId: '',
  campusId: '',
  onLoad(options: Record<string, string | undefined>) { this.eventId = options.eventId ? decodeURIComponent(options.eventId) : ''; this.campusId = options.campusId ? decodeURIComponent(options.campusId) : ''; void this.load() },
  onRetry() { void this.load() },
  openAnimal() { const id = this.data.detail?.rescue.animal?.id; if (id) wx.navigateTo({ url: `/pages/animal-detail/index?animalId=${encodeURIComponent(id)}` }) },
  async load() {
    if (!this.eventId || !this.campusId) { this.setData({ loading: false, errorMessage: '缺少救助事件或高校信息。' }); return }
    this.setData({ loading: true, errorMessage: '' })
    try {
      const detail = await source.getDetail(this.campusId, this.eventId)
      this.setData({ detail: { ...detail, rescue: { ...detail.rescue, updatedAt: dateText(detail.rescue.updatedAt) }, progress: detail.progress.map((item) => ({ ...item, occurredAt: dateText(item.occurredAt) })) }, statusText: labels[detail.rescue.rescueStatus] })
    } catch (error) { this.setData({ detail: null, errorMessage: readableError(error) }) } finally { this.setData({ loading: false }) }
  },
})
