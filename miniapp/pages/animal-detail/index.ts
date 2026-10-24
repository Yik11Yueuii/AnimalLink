import { loadAnimalDetail } from '../../services/animalDetailLoader'
import { getPublicAnimalDataSource, isAnimalOfflineDemo } from '../../services/sourceFactory'
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
  async loadDetail() {
    if (!this.data.animalId) {
      this.setData({ loading: false, errorMessage: '缺少 Animal 档案 ID。' })
      return
    }
    this.setData({ loading: true, errorMessage: '', timelineMessage: '' })
    try {
      const source = getPublicAnimalDataSource()
      const result = await loadAnimalDetail(source, this.data.animalId)
      const animal = result.animal
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
      this.setData({ animal: null, timeline: [], timelineMessage: '', errorMessage: readableError(error) })
    } finally {
      this.setData({ loading: false })
    }
  },
})
