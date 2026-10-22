import { reactive, ref } from 'vue'
import type { AdoptionGovernanceDataSource } from './dataSource'
import type { AdoptionSearchCriteria, AdoptionStatusCounts, GovernanceAdoptionApplication } from './types'

const emptyCounts = (): AdoptionStatusCounts => ({ SUBMITTED: 0, APPROVED: 0, REJECTED: 0, WITHDRAWN: 0 })
const defaultCriteria = (): AdoptionSearchCriteria => ({ keyword: '', status: '', sort: 'UPDATED_DESC', page: 1, size: 6 })
const message = (error: unknown, fallback: string) => error instanceof Error && error.message.trim() ? error.message : fallback

export const useAdoptionCatalog = (dataSource: AdoptionGovernanceDataSource) => {
  const criteria = reactive(defaultCriteria())
  const items = ref<GovernanceAdoptionApplication[]>([])
  const total = ref(0)
  const statusCounts = ref<AdoptionStatusCounts>(emptyCounts())
  const loading = ref(false)
  const errorMessage = ref('')
  const detail = ref<GovernanceAdoptionApplication | null>(null)
  const detailVisible = ref(false)
  const detailLoading = ref(false)
  const detailError = ref('')
  let searchGeneration = 0
  let detailGeneration = 0
  let selectedApplicationId = ''

  const load = async (resetPage = false) => {
    if (resetPage) criteria.page = 1
    const generation = ++searchGeneration
    loading.value = true
    errorMessage.value = ''
    try {
      const result = await dataSource.search({ ...criteria })
      if (generation !== searchGeneration) return
      items.value = result.items
      total.value = result.total
      statusCounts.value = result.statusCounts
      criteria.page = result.page
      criteria.size = result.size
    } catch (error) {
      if (generation !== searchGeneration) return
      items.value = []
      total.value = 0
      statusCounts.value = emptyCounts()
      errorMessage.value = message(error, '领养申请加载失败，请稍后重试。')
    } finally {
      if (generation === searchGeneration) loading.value = false
    }
  }

  const reset = async () => { Object.assign(criteria, defaultCriteria()); await load() }
  const changePage = async (page: number) => { criteria.page = page; await load() }
  const changePageSize = async (size: number) => { criteria.size = size; await load(true) }
  const openDetail = async (applicationId: string) => {
    selectedApplicationId = applicationId
    const generation = ++detailGeneration
    detailVisible.value = true
    detailLoading.value = true
    detailError.value = ''
    detail.value = null
    try {
      const result = await dataSource.getDetail(applicationId)
      if (generation === detailGeneration) detail.value = result
    } catch (error) {
      if (generation === detailGeneration) detailError.value = message(error, '申请详情加载失败，请稍后重试。')
    } finally {
      if (generation === detailGeneration) detailLoading.value = false
    }
  }
  const retryDetail = async () => { if (selectedApplicationId) await openDetail(selectedApplicationId) }
  const closeDetail = () => {
    detailGeneration += 1
    detailVisible.value = false
    detail.value = null
    detailError.value = ''
    detailLoading.value = false
  }

  return {
    criteria, items, total, statusCounts, loading, errorMessage,
    detail, detailVisible, detailLoading, detailError,
    load, reset, changePage, changePageSize, openDetail, retryDetail, closeDetail,
  }
}
