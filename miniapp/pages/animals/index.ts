import { getPublicAnimalDataSource, isAnimalOfflineDemo } from '../../services/sourceFactory'
import { AnimalCatalog } from '../../state/animalCatalog'
import { getSelectedCampus } from '../../state/campus'
import type { AnimalSpecies, AnimalSummaryResponse, CampusResponse } from '../../types/api'
import { adoptionLabel, contextLabel, speciesLabel } from '../../utils/presentation'

interface AnimalCard extends AnimalSummaryResponse {
  speciesText: string
  adoptionText: string
  contextText: string
}

const speciesOptions: Array<{ label: string; value: AnimalSpecies | '' }> = [
  { label: '全部物种', value: '' },
  { label: '猫', value: 'CAT' },
  { label: '狗', value: 'DOG' },
  { label: '其他', value: 'OTHER' },
]

const catalog = new AnimalCatalog(getPublicAnimalDataSource(), 3)
const toCard = (animal: AnimalSummaryResponse): AnimalCard => ({
  ...animal,
  speciesText: speciesLabel[animal.species],
  adoptionText: adoptionLabel[animal.adoptionStatus],
  contextText: contextLabel[animal.currentContext],
})

Page({
  data: {
    offline: isAnimalOfflineDemo(),
    campus: null as CampusResponse | null,
    q: '',
    speciesIndex: 0,
    speciesLabels: speciesOptions.map((item) => item.label),
    animals: [] as AnimalCard[],
    total: 0,
    loading: false,
    loadingMore: false,
    hasMore: false,
    errorMessage: '',
  },
  onShow() {
    const campus = getSelectedCampus()
    if (!campus) {
      wx.navigateTo({ url: '/pages/campus/index' })
      return
    }
    if (campus.id !== this.data.campus?.id) {
      catalog.selectCampus(campus.id)
      this.setData({ campus, animals: [], total: 0, errorMessage: '' })
      void this.refresh()
    }
  },
  onPullDownRefresh() {
    void this.refresh().finally(() => wx.stopPullDownRefresh())
  },
  changeCampus() {
    wx.navigateTo({ url: '/pages/campus/index' })
  },
  onKeywordInput(event: WechatMiniprogram.Input) {
    this.setData({ q: event.detail.value })
  },
  onSearch() {
    catalog.setQuery(this.data.q)
    void this.refresh()
  },
  onSpeciesChange(event: WechatMiniprogram.PickerChange) {
    const index = Number(event.detail.value)
    catalog.setSpecies(speciesOptions[index]?.value ?? '')
    this.setData({ speciesIndex: index })
    void this.refresh()
  },
  openDetail(event: WechatMiniprogram.TouchEvent) {
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
    const pending = catalog.refresh()
    this.applySnapshot(catalog.snapshot)
    this.applySnapshot(await pending)
  },
  async loadNextPage() {
    const pending = catalog.loadMore()
    this.applySnapshot(catalog.snapshot)
    this.applySnapshot(await pending)
  },
  applySnapshot(snapshot: ReturnType<typeof catalogSnapshot>) {
    this.setData({
      animals: snapshot.items.map(toCard),
      total: snapshot.total,
      loading: snapshot.loading,
      loadingMore: snapshot.loadingMore,
      hasMore: snapshot.hasMore,
      errorMessage: snapshot.errorMessage,
    })
  },
})

function catalogSnapshot() {
  return catalog.snapshot
}
