import { getPublicAnimalDataSource, isAnimalOfflineDemo } from '../../services/sourceFactory'
import { getSelectedCampus, saveSelectedCampus } from '../../state/campus'
import type { UniversityResponse } from '../../types/api'
import { readableError } from '../../utils/presentation'

const dataSource = getPublicAnimalDataSource()
const pageSize = 8
let universityRequestVersion = 0
let communityRequestVersion = 0

Page({
  data: {
    offline: isAnimalOfflineDemo(),
    q: '',
    provinceOptions: isAnimalOfflineDemo() ? ['广东省', '浙江省', '江苏省'] : ['广东省'],
    provinceIndex: 0,
    universities: [] as UniversityResponse[],
    universityPage: 0,
    universityTotal: 0,
    hasMoreUniversities: false,
    selectedUniversityId: '',
    openingUniversityId: '',
    returnTo: 'circle' as 'animals' | 'circle',
    loading: true,
    loadingMore: false,
    errorMessage: '',
    selectionError: '',
  },
  onLoad(options: Record<string, string | undefined>) {
    this.setData({
      selectedUniversityId: getSelectedCampus()?.universityId ?? '',
      returnTo: options.returnTo === 'animals' ? 'animals' : 'circle',
    })
    void this.loadUniversities(true)
  },
  onUnload() {
    universityRequestVersion += 1
    communityRequestVersion += 1
  },
  onKeywordInput(event: WechatMiniprogram.Input) {
    this.setData({ q: event.detail.value })
  },
  onSearch() {
    void this.loadUniversities(true)
  },
  onRetry() {
    void this.loadUniversities(true)
  },
  onProvinceChange(event: WechatMiniprogram.PickerChange) {
    this.setData({ provinceIndex: Number(event.detail.value) })
    void this.loadUniversities(true)
  },
  loadMoreUniversities() {
    if (!this.data.loadingMore && this.data.hasMoreUniversities) void this.loadUniversities(false)
  },
  async chooseUniversity(event: WechatMiniprogram.TouchEvent) {
    const university = this.data.universities.find((item) => item.id === event.currentTarget.dataset.id)
    if (!university) return
    if (!university.communityAvailable) {
      this.setData({ selectionError: `${university.officialName}的社区尚未开放。` })
      return
    }

    const requestVersion = ++communityRequestVersion
    this.setData({ openingUniversityId: university.id, selectionError: '' })
    try {
      const community = await dataSource.getUniversityCommunity(university.id)
      if (requestVersion !== communityRequestVersion) return
      saveSelectedCampus(community)
      wx.switchTab({ url: this.data.returnTo === 'animals' ? '/pages/animals/index' : '/pages/circle/index' })
    } catch (error) {
      if (requestVersion !== communityRequestVersion) return
      this.setData({ selectionError: readableError(error) })
    } finally {
      if (requestVersion === communityRequestVersion) this.setData({ openingUniversityId: '' })
    }
  },
  async loadUniversities(reset: boolean) {
    if (reset) communityRequestVersion += 1
    const requestVersion = ++universityRequestVersion
    const page = reset ? 0 : this.data.universityPage + 1
    this.setData(reset
      ? { loading: true, loadingMore: false, errorMessage: '', selectionError: '', universities: [] }
      : { loadingMore: true, errorMessage: '' })
    try {
      const result = await dataSource.searchUniversities({
        province: this.data.provinceOptions[this.data.provinceIndex] ?? '',
        q: this.data.q,
        page,
        size: pageSize,
      })
      if (requestVersion !== universityRequestVersion) return
      const universities = reset ? result.items : [...this.data.universities, ...result.items]
      this.setData({
        universities,
        universityPage: result.page,
        universityTotal: result.total,
        hasMoreUniversities: universities.length < result.total,
      })
    } catch (error) {
      if (requestVersion !== universityRequestVersion) return
      this.setData({ errorMessage: readableError(error) })
    } finally {
      if (requestVersion === universityRequestVersion) this.setData({ loading: false, loadingMore: false })
    }
  },
})
