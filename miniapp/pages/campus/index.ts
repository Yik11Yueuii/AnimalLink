import { getPublicAnimalDataSource, isOfflineDemo } from '../../services/sourceFactory'
import { getSelectedCampus, saveSelectedCampus } from '../../state/campus'
import type { CampusResponse } from '../../types/api'
import { readableError } from '../../utils/presentation'

Page({
  data: {
    offline: isOfflineDemo(),
    q: '',
    campuses: [] as CampusResponse[],
    selectedCampusId: '',
    loading: true,
    errorMessage: '',
  },
  onLoad() {
    this.setData({ selectedCampusId: getSelectedCampus()?.id ?? '' })
    void this.loadCampuses()
  },
  onKeywordInput(event: WechatMiniprogram.Input) {
    this.setData({ q: event.detail.value })
  },
  onSearch() {
    void this.loadCampuses()
  },
  onRetry() {
    void this.loadCampuses()
  },
  chooseCampus(event: WechatMiniprogram.TouchEvent) {
    const campus = this.data.campuses.find((item) => item.id === event.currentTarget.dataset.id)
    if (!campus) return
    saveSelectedCampus(campus)
    wx.switchTab({ url: '/pages/animals/index' })
  },
  async loadCampuses() {
    this.setData({ loading: true, errorMessage: '' })
    try {
      const campuses = await getPublicAnimalDataSource().listCampuses(this.data.q, 0, 50)
      this.setData({ campuses })
    } catch (error) {
      this.setData({ campuses: [], errorMessage: readableError(error) })
    } finally {
      this.setData({ loading: false })
    }
  },
})
