import { getRescueDataSource, isRescueOfflineDemo } from '../../services/sourceFactory'
import { resolveCurrentCampusContext } from '../../services/campusContext'
import { getSelectedCampus } from '../../state/campus'
import type { PublicRescueDetail, PublicRescueStatus } from '../../types/rescue'
import { readableError } from '../../utils/presentation'

const source = getRescueDataSource()
const labels: Record<PublicRescueStatus, string> = { WAITING_CLAIM: '等待处理', IN_PROGRESS: '处理中', RESOLVED: '结果已登记', CLOSED: '已完成', CLOSED_UNRESOLVED: '未解决结束', CANCELLED: '已取消' }
const dateText = (value: string) => value ? value.replace('T', ' ').slice(0, 16) : '时间暂未公开'

Page({
  detailRequestVersion: 0,
  data: { offline: isRescueOfflineDemo(), detail: null as PublicRescueDetail | null, statusText: '', loading: true, errorMessage: '' },
  eventId: '',
  campusId: '',
  onLoad(options: Record<string, string | undefined>) { this.eventId = options.eventId ? decodeURIComponent(options.eventId) : ''; this.campusId = options.campusId ? decodeURIComponent(options.campusId) : ''; void this.load() },
  onRetry() { void this.load() },
  onUnload() { this.detailRequestVersion += 1 },
  openAnimal() { const id = this.data.detail?.rescue.animal?.id; if (id) wx.navigateTo({ url: `/pages/animal-detail/index?animalId=${encodeURIComponent(id)}` }) },
  async load() {
    if (!this.eventId || !this.campusId) { this.setData({ loading: false, errorMessage: '缺少救助事件或高校信息。' }); return }
    const requestVersion = ++this.detailRequestVersion
    this.setData({ loading: true, errorMessage: '' })
    try {
      const resolution = await resolveCurrentCampusContext()
      if (requestVersion !== this.detailRequestVersion || resolution.status === 'superseded') return
      if (resolution.status === 'missing') throw new Error('当前高校已失效，请重新选择高校。')
      if (resolution.status === 'unavailable') throw new Error('暂时无法验证当前高校，请检查网络后重试。')
      if (this.campusId !== resolution.campus.id) throw new Error('该救助事件不属于当前高校。')
      const detail = await source.getDetail(this.campusId, this.eventId)
      if (requestVersion !== this.detailRequestVersion || getSelectedCampus()?.id !== resolution.campus.id) return
      if (detail.rescue.campusId !== resolution.campus.id) throw new Error('该救助事件不属于当前高校。')
      this.setData({ detail: { ...detail, rescue: { ...detail.rescue, updatedAt: dateText(detail.rescue.updatedAt) }, progress: detail.progress.map((item) => ({ ...item, occurredAt: dateText(item.occurredAt) })) }, statusText: labels[detail.rescue.rescueStatus] })
    } catch (error) { if (requestVersion === this.detailRequestVersion) this.setData({ detail: null, errorMessage: readableError(error) }) } finally { if (requestVersion === this.detailRequestVersion) this.setData({ loading: false }) }
  },
})
