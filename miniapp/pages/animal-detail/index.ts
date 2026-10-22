import { getPublicAnimalDataSource, isOfflineDemo } from '../../services/sourceFactory'
import type { PublicAnimalResponse, TimelineEntryResponse } from '../../types/api'
import { adoptionLabel, contextLabel, formatDate, readableError, sexLabel, speciesLabel, sterilizationLabel } from '../../utils/presentation'

interface DetailView extends PublicAnimalResponse {
  speciesText: string
  sexText: string
  adoptionText: string
  contextText: string
  sterilizationText: string
  createdDate: string
  updatedDate: string
}
interface TimelineView extends TimelineEntryResponse { occurredDate: string }

Page({
  data: {
    offline: isOfflineDemo(),
    animalId: '',
    animal: null as DetailView | null,
    timeline: [] as TimelineView[],
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
    this.setData({ loading: true, errorMessage: '' })
    try {
      const source = getPublicAnimalDataSource()
      const [animal, timeline] = await Promise.all([
        source.getAnimal(this.data.animalId),
        source.getTimeline(this.data.animalId, 0, 20),
      ])
      const view: DetailView = {
        ...animal,
        speciesText: speciesLabel[animal.species],
        sexText: sexLabel[animal.sex],
        adoptionText: adoptionLabel[animal.adoptionStatus],
        contextText: contextLabel[animal.currentContext],
        sterilizationText: sterilizationLabel[animal.sterilizationStatus],
        createdDate: formatDate(animal.createdAt),
        updatedDate: formatDate(animal.updatedAt),
      }
      this.setData({
        animal: view,
        timeline: timeline.items.map((entry) => ({ ...entry, occurredDate: formatDate(entry.occurredAt) })),
      })
      wx.setNavigationBarTitle({ title: animal.displayName })
    } catch (error) {
      this.setData({ animal: null, timeline: [], errorMessage: readableError(error) })
    } finally {
      this.setData({ loading: false })
    }
  },
})
