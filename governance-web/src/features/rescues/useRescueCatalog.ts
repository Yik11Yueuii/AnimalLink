import { reactive, ref } from 'vue'
import type { RescueGovernanceDataSource } from './dataSource'
import type { RescueDetail, RescueSearchCriteria } from './types'
const defaultCriteria = (campusId = ''): RescueSearchCriteria => ({ keyword: '', campusId, rescueStatus: '', sort: 'UPDATED_DESC', page: 1, size: 6 })
const message = (error: unknown, fallback: string) => error instanceof Error && error.message.trim() ? error.message : fallback
export const useRescueCatalog = (dataSource: RescueGovernanceDataSource, initialCampusId = '') => {
  const criteria = reactive(defaultCriteria(initialCampusId))
  const items = ref<Awaited<ReturnType<RescueGovernanceDataSource['search']>>['items']>([])
  const total = ref(0), loading = ref(false), errorMessage = ref('')
  const detail = ref<RescueDetail | null>(null), detailVisible = ref(false), detailLoading = ref(false), detailError = ref('')
  let searchGeneration = 0, detailGeneration = 0, selectedEventId = ''
  const load = async (resetPage = false) => {
    if (resetPage) criteria.page = 1
    const generation = ++searchGeneration
    loading.value = true; errorMessage.value = ''
    try {
      const result = await dataSource.search({ ...criteria })
      if (generation !== searchGeneration) return
      items.value = result.items; total.value = result.total; criteria.page = result.page; criteria.size = result.size
    } catch (error) {
      if (generation !== searchGeneration) return
      items.value = []; total.value = 0; errorMessage.value = message(error, '救助事件加载失败，请稍后重试。')
    } finally { if (generation === searchGeneration) loading.value = false }
  }
  const reset = async () => { Object.assign(criteria, defaultCriteria(initialCampusId)); await load() }
  const changePage = async (page: number) => { criteria.page = page; await load() }
  const changePageSize = async (size: number) => { criteria.size = size; await load(true) }
  const openDetail = async (eventId: string) => {
    selectedEventId = eventId
    const generation = ++detailGeneration
    detailVisible.value = true; detailLoading.value = true; detailError.value = ''; detail.value = null
    try {
      const result = await dataSource.getDetail(eventId)
      if (generation === detailGeneration) detail.value = result
    } catch (error) {
      if (generation === detailGeneration) detailError.value = message(error, '救助详情加载失败，请稍后重试。')
    } finally { if (generation === detailGeneration) detailLoading.value = false }
  }
  const retryDetail = async () => { if (selectedEventId) await openDetail(selectedEventId) }
  const closeDetail = () => { detailGeneration += 1; detailVisible.value = false; detail.value = null; detailError.value = ''; detailLoading.value = false }
  return { criteria, items, total, loading, errorMessage, detail, detailVisible, detailLoading, detailError,
    load, reset, changePage, changePageSize, openDetail, retryDetail, closeDetail }
}
