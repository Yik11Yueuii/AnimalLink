import { getRescueDataSource, isRescueOfflineDemo } from '../../services/sourceFactory'
import { resolveCurrentCampusContext } from '../../services/campusContext'
import { getSelectedCampus } from '../../state/campus'
import type { PublicRescueDetail, PublicRescueStatus } from '../../types/rescue'
import { claimRescue, storedMiniappSession } from '../../services/userAuthentication'
import type { ClaimedRescueCase } from '../../services/userAuthentication'
import { ApiRequestError } from '../../services/apiClient'
import { readableError } from '../../utils/presentation'

const source = getRescueDataSource()
const labels: Record<PublicRescueStatus, string> = { WAITING_CLAIM: '等待处理', IN_PROGRESS: '处理中', RESOLVED: '结果已登记', CLOSED: '已完成', CLOSED_UNRESOLVED: '未解决结束', CANCELLED: '已取消' }
const dateText = (value: string) => value ? value.replace('T', ' ').slice(0, 16) : '时间暂未公开'

Page({
  detailRequestVersion: 0,
  data: { offline: isRescueOfflineDemo(), detail: null as PublicRescueDetail | null, statusText: '', loading: true, errorMessage: '', claiming: false, claimMessage: '', claimedCase: null as ClaimedRescueCase | null },
  eventId: '',
  campusId: '',
  onLoad(options: Record<string, string | undefined>) { this.eventId = options.eventId ? decodeURIComponent(options.eventId) : ''; this.campusId = options.campusId ? decodeURIComponent(options.campusId) : ''; void this.load() },
  onRetry() { void this.load() },
  onUnload() { this.detailRequestVersion += 1 },
  async onClaim() {
    if (this.data.claiming || !this.data.detail || this.data.offline) return
    const version = this.detailRequestVersion
    if (getSelectedCampus()?.id !== this.campusId) { this.setData({ claimMessage: '当前高校已切换，请重新打开救助详情。' }); return }
    if (!storedMiniappSession()) { this.setData({ claimMessage: '请先前往「我的」完成微信登录。' }); return }
    this.setData({ claiming: true, claimMessage: '' })
    try {
      const result = await claimRescue(this.campusId, this.eventId)
      if (version !== this.detailRequestVersion || getSelectedCampus()?.id !== this.campusId) return
      if (result.eventId !== this.eventId || result.campusId !== this.campusId) throw new Error('认领结果与当前高校不一致。')
      this.setData({ claimedCase: result, claimMessage: '认领成功；重复认领会返回同一案件。' })
    } catch (error) {
      if (version !== this.detailRequestVersion || getSelectedCampus()?.id !== this.campusId) return
      const status = error instanceof ApiRequestError ? error.statusCode : null
      const message = status === 401 ? '登录已失效，请重新登录。' : status === 403 ? '当前高校需要有效志愿者资格，或高校不匹配。' : status === 409 ? '案件已被其他志愿者认领，或事件当前不可认领。' : status === 503 ? '服务暂不可用，请稍后重试。' : '网络或服务异常，请检查后重试。'
      this.setData({ claimMessage: message })
    } finally { if (version === this.detailRequestVersion) this.setData({ claiming: false }) }
  },
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
