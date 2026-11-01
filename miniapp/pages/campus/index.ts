import { getPublicAnimalDataSource, isAnimalOfflineDemo } from '../../services/sourceFactory'
import { getSelectedCampus, saveSelectedCampus } from '../../state/campus'
import type { UniversityResponse } from '../../types/api'
import { readableError } from '../../utils/presentation'

const dataSource = getPublicAnimalDataSource()
const pageSize = 8
// Geographic options do not imply that a community is available.
const provinceOptions = [
  '广东省', '北京市', '天津市', '河北省', '山西省', '内蒙古自治区', '辽宁省', '吉林省',
  '黑龙江省', '上海市', '江苏省', '浙江省', '安徽省', '福建省', '江西省', '山东省',
  '河南省', '湖北省', '湖南省', '广西壮族自治区', '海南省', '重庆市', '四川省',
  '贵州省', '云南省', '西藏自治区', '陕西省', '甘肃省', '青海省', '宁夏回族自治区',
  '新疆维吾尔自治区', '香港特别行政区', '澳门特别行政区', '台湾省',
]
const appendUnique = (current: UniversityResponse[], incoming: UniversityResponse[]) => {
  const ids = new Set(current.map(item => item.id))
  return [...current, ...incoming.filter(item => {
    if (ids.has(item.id)) return false
    ids.add(item.id)
    return true
  })]
}

Page({
  universityRequestVersion: 0,
  provinceRequestVersion: 0,
  communityRequestVersion: 0,
  navigationPending: false,
  data: {
    offline: isAnimalOfflineDemo(),
    q: '',
    searchTerm: '',
    provinceOptions,
    activeProvince: provinceOptions[0],
    provincePickerOpen: false,
    provinceUniversities: [] as UniversityResponse[],
    provincePage: 0,
    provinceScrollTop: 0,
    provinceTotal: 0,
    provinceHasMore: false,
    provinceLoading: false,
    provinceLoadingMore: false,
    provinceError: '',
    universities: [] as UniversityResponse[],
    universityPage: 0,
    universityTotal: 0,
    hasMoreUniversities: false,
    selectedUniversityId: '',
    openingUniversityId: '',
    returnTo: 'circle' as 'animals' | 'circle' | 'rescue',
    loading: true,
    loadingMore: false,
    errorMessage: '',
    selectionError: '',
  },
  onLoad(options: Record<string, string | undefined>) {
    this.setData({
      selectedUniversityId: getSelectedCampus()?.universityId ?? '',
      returnTo: options.returnTo === 'animals' || options.returnTo === 'rescue' ? options.returnTo : 'circle',
    })
    void this.loadUniversities(true)
  },
  onHide() {
    if (this.navigationPending) return
    this.closeProvincePicker()
  },
  onUnload() {
    this.universityRequestVersion += 1
    this.provinceRequestVersion += 1
    this.communityRequestVersion += 1
  },
  onKeywordInput(event: WechatMiniprogram.Input) {
    this.setData({ q: event.detail.value })
  },
  onSearch() {
    if (this.navigationPending) return
    this.closeProvincePicker()
    this.setData({ searchTerm: this.data.q.trim() })
    return this.loadUniversities(true)
  },
  onRetry() {
    return this.loadUniversities(this.data.universities.length === 0)
  },
  openProvincePicker() {
    if (this.navigationPending) return
    wx.hideKeyboard()
    this.communityRequestVersion += 1
    this.setData({ provincePickerOpen: true, selectionError: '', openingUniversityId: '' })
    return this.loadProvinceUniversities(true)
  },
  closeProvincePicker() {
    if (this.navigationPending) return
    this.provinceRequestVersion += 1
    this.communityRequestVersion += 1
    this.setData({ provincePickerOpen: false, provinceLoading: false, provinceLoadingMore: false, openingUniversityId: '', selectionError: '' })
  },
  stopPropagation() {},
  onProvinceTap(event: WechatMiniprogram.TouchEvent) {
    const province = String(event.currentTarget.dataset.province ?? '')
    if (this.navigationPending || !this.data.provincePickerOpen || !provinceOptions.includes(province) || province === this.data.activeProvince) return
    this.communityRequestVersion += 1
    this.setData({ activeProvince: province, selectionError: '', openingUniversityId: '', provinceScrollTop: this.data.provinceScrollTop === 0 ? 1 : 0 })
    return this.loadProvinceUniversities(true)
  },
  retryProvince() {
    return this.loadProvinceUniversities(this.data.provinceUniversities.length === 0)
  },
  loadMoreUniversities() {
    if (!this.data.loading && !this.data.loadingMore && this.data.hasMoreUniversities) return this.loadUniversities(false)
  },
  loadMoreProvinceUniversities() {
    if (!this.data.provinceLoading && !this.data.provinceLoadingMore && this.data.provinceHasMore && !this.data.provinceError) return this.loadProvinceUniversities(false)
  },
  async chooseUniversity(event: WechatMiniprogram.TouchEvent) {
    if (this.navigationPending || this.data.openingUniversityId) return
    const fromProvince = event.currentTarget.dataset.source === 'province'
    if (fromProvince && !this.data.provincePickerOpen) return
    const items = fromProvince ? this.data.provinceUniversities : this.data.universities
    const university = items.find(item => item.id === event.currentTarget.dataset.id)
    if (!university || !university.communityAvailable) return
    const requestVersion = ++this.communityRequestVersion
    const previousUniversityId = this.data.selectedUniversityId
    let rollbackContext: (() => void) | undefined
    let navigationStarted = false
    this.setData({ openingUniversityId: university.id, selectionError: '' })
    try {
      const community = await dataSource.getUniversityCommunity(university.id)
      if (requestVersion !== this.communityRequestVersion) return
      const previousCampus = getSelectedCampus()
      saveSelectedCampus(community)
      rollbackContext = () => {
        // Do not overwrite a newer selection made outside this page.
        if (getSelectedCampus()?.id !== community.id) return
        if (previousCampus) saveSelectedCampus(previousCampus)
        else wx.removeStorageSync('animallink.selectedCampus.v1')
      }
      this.setData({ provincePickerOpen: false, selectedUniversityId: university.id })
      this.provinceRequestVersion += 1
      const destination = this.data.returnTo === 'animals' ? '/pages/animals/index'
        : this.data.returnTo === 'rescue' ? '/pages/rescue/index' : '/pages/circle/index'
      this.navigationPending = true
      navigationStarted = true
      await new Promise<void>((resolve, reject) => {
        wx.switchTab({
          url: destination,
          success: () => resolve(),
          fail: () => reject(new Error('页面跳转失败，请重新选择高校。')),
        })
      })
      rollbackContext = undefined
    } catch (error) {
      rollbackContext?.()
      if (requestVersion === this.communityRequestVersion) this.setData({ selectedUniversityId: previousUniversityId, selectionError: readableError(error) })
    } finally {
      if (navigationStarted) this.navigationPending = false
      if (requestVersion === this.communityRequestVersion) this.setData({ openingUniversityId: '' })
    }
  },
  async loadUniversities(reset: boolean) {
    if (this.navigationPending) return
    if (reset) {
      this.communityRequestVersion += 1
      this.setData({ openingUniversityId: '' })
    }
    const requestVersion = ++this.universityRequestVersion
    const page = reset ? 0 : this.data.universityPage + 1
    this.setData(reset
      ? { loading: true, loadingMore: false, errorMessage: '', selectionError: '', universities: [], universityTotal: 0, hasMoreUniversities: false }
      : { loadingMore: true, errorMessage: '' })
    try {
      const result = await dataSource.searchUniversities({ province: '', q: this.data.searchTerm, page, size: pageSize })
      if (requestVersion !== this.universityRequestVersion) return
      this.setData({
        universities: appendUnique(reset ? [] : this.data.universities, result.items),
        universityPage: result.page,
        universityTotal: result.total,
        hasMoreUniversities: result.items.length > 0 && (result.page + 1) * result.size < result.total,
      })
    } catch (error) {
      if (requestVersion === this.universityRequestVersion) this.setData({ errorMessage: readableError(error) })
    } finally {
      if (requestVersion === this.universityRequestVersion) this.setData({ loading: false, loadingMore: false })
    }
  },
  async loadProvinceUniversities(reset: boolean) {
    if (!this.data.provincePickerOpen) return
    const requestVersion = ++this.provinceRequestVersion
    const page = reset ? 0 : this.data.provincePage + 1
    this.setData(reset
      ? { provinceLoading: true, provinceLoadingMore: false, provinceError: '', provinceUniversities: [], provinceTotal: 0, provinceHasMore: false }
      : { provinceLoadingMore: true, provinceError: '' })
    try {
      const result = await dataSource.searchUniversities({ province: this.data.activeProvince, q: '', page, size: pageSize })
      if (requestVersion !== this.provinceRequestVersion) return
      this.setData({
        provinceUniversities: appendUnique(reset ? [] : this.data.provinceUniversities, result.items),
        provincePage: result.page,
        provinceTotal: result.total,
        provinceHasMore: result.items.length > 0 && (result.page + 1) * result.size < result.total,
      })
    } catch (error) {
      if (requestVersion === this.provinceRequestVersion) this.setData({ provinceError: readableError(error) })
    } finally {
      if (requestVersion === this.provinceRequestVersion) this.setData({ provinceLoading: false, provinceLoadingMore: false })
    }
  },
})
