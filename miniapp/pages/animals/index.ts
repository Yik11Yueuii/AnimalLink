import { resolveCurrentCampusContext } from '../../services/campusContext'
import { getPublicAnimalDataSource, isAnimalOfflineDemo, isPublicDataApiMode } from '../../services/sourceFactory'
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
  campusResolutionVersion: 0,
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
    campusValidationFailed: false,
    errorMessage: '',
  },
  onShow() {
    const campus = getSelectedCampus()
    if (!campus) {
      wx.navigateTo({ url: '/pages/campus/index?returnTo=animals' })
      return
    }
    if (!isPublicDataApiMode()) { this.activateCampus(campus); return }
    const version = ++this.campusResolutionVersion
    if (campus.id !== this.data.campus?.id) {
      catalog.selectCampus('')
      this.setData({ campus, animals: [], total: 0, loading: true, campusValidationFailed: false, errorMessage: '' })
    }
    void resolveCurrentCampusContext().then((resolution) => {
      if (version !== this.campusResolutionVersion || resolution.status === 'superseded') return
      if (resolution.status === 'missing') {
        catalog.selectCampus('')
        this.setData({ campus: null, animals: [], total: 0, loading: false, campusValidationFailed: false, errorMessage: '' })
        wx.navigateTo({ url: '/pages/campus/index?returnTo=animals' })
        return
      }
      if (resolution.status === 'unavailable') {
        this.setData({ loading: false, campusValidationFailed: true, errorMessage: '暂时无法验证当前高校，请检查网络后重试。' })
        return
      }
      this.activateCampus(resolution.campus)
    })
  },
  onHide() { this.campusResolutionVersion += 1 },
  onUnload() { this.campusResolutionVersion += 1 },
  onPullDownRefresh() {
    void this.refresh().finally(() => wx.stopPullDownRefresh())
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
    if (this.data.campusValidationFailed) { this.onShow(); return }
    void this.refresh()
  },
  loadMore() {
    void this.loadNextPage()
  },
  activateCampus(campus: CampusResponse) {
    if (campus.id !== catalog.snapshot.campusId) {
      catalog.selectCampus(campus.id)
      this.setData({ campus, animals: [], total: 0, campusValidationFailed: false, errorMessage: '' })
      void this.refresh()
      return
    }
    this.setData({ campus, campusValidationFailed: false })
    this.applySnapshot(catalog.snapshot)
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
