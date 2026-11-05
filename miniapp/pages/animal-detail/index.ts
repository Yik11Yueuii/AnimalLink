import { loadAnimalDetail } from '../../services/animalDetailLoader'
import { resolveCurrentCampusContext } from '../../services/campusContext'
import { getPublicAnimalDataSource, isAnimalOfflineDemo } from '../../services/sourceFactory'
import { getSelectedCampus } from '../../state/campus'
import type { PublicAnimalResponse, TimelineEntryResponse } from '../../types/api'
import { adoptionLabel, contextLabel, formatDate, readableError, sexLabel, speciesLabel } from '../../utils/presentation'

interface DetailView extends PublicAnimalResponse {
  speciesText: string
  sexText: string
  adoptionText: string
  contextText: string
  createdDate: string
  updatedDate: string
}
interface TimelineView extends TimelineEntryResponse { occurredDate: string }

Page({
  detailRequestVersion: 0,
  data: {
    offline: isAnimalOfflineDemo(),
    animalId: '',
    animal: null as DetailView | null,
    timeline: [] as TimelineView[],
    timelineMessage: '',
    loading: true,
    errorMessage: '',
  },
  onLoad(options: Record<string, string | undefined>) {
    const animalId = options.animalId ? decodeURIComponent(options.animalId) : ''
    this.setData({ animalId })
    void this.loadDetail()
  },
  onRetry() {
    void this.loadDetail()
  },
  onUnload() { this.detailRequestVersion += 1 },
  async loadDetail() {
    if (!this.data.animalId) {
      this.setData({ loading: false, errorMessage: '缺少 Animal 档案 ID。' })
      return
    }
    const requestVersion = ++this.detailRequestVersion
    this.setData({ loading: true, errorMessage: '', timelineMessage: '' })
    try {
      const resolution = await resolveCurrentCampusContext()
      if (requestVersion !== this.detailRequestVersion || resolution.status === 'superseded') return
      if (resolution.status === 'missing') throw new Error('当前高校已失效，请重新选择高校。')
      if (resolution.status === 'unavailable') throw new Error('暂时无法验证当前高校，请检查网络后重试。')
      const source = getPublicAnimalDataSource()
      const result = await loadAnimalDetail(source, this.data.animalId, isAnimalOfflineDemo())
      if (requestVersion !== this.detailRequestVersion || getSelectedCampus()?.id !== resolution.campus.id) return
      const animal = result.animal
      if (animal.campusId !== resolution.campus.id) throw new Error('该 Animal 不属于当前高校。')
      const view: DetailView = {
        ...animal,
        speciesText: speciesLabel[animal.species],
        sexText: sexLabel[animal.sex],
        adoptionText: adoptionLabel[animal.adoptionStatus],
        contextText: contextLabel[animal.currentContext],
        createdDate: formatDate(animal.createdAt),
        updatedDate: formatDate(animal.updatedAt),
      }
      this.setData({
        animal: view,
        timeline: result.timeline.map((entry) => ({ ...entry, occurredDate: formatDate(entry.occurredAt) })),
        timelineMessage: result.timelineStatus === 'protected'
          ? '动态暂未开放'
          : result.timelineStatus === 'failed'
            ? `动态加载失败：${readableError(result.timelineError)}`
            : '',
      })
      wx.setNavigationBarTitle({ title: animal.displayName })
    } catch (error) {
      if (requestVersion === this.detailRequestVersion) this.setData({ animal: null, timeline: [], timelineMessage: '', errorMessage: readableError(error) })
    } finally {
      if (requestVersion === this.detailRequestVersion) this.setData({ loading: false })
    }
  },
})
