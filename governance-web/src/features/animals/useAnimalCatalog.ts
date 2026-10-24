import { reactive, ref } from 'vue'
import type { AnimalGovernanceDataSource } from './dataSource'
import type { AnimalSearchCriteria, GovernanceAnimal } from './types'

const defaultCriteria = (): AnimalSearchCriteria => ({
  keyword: '',
  campusId: '',
  species: '',
  identityStatus: '',
  adoptionStatus: '',
  currentContext: '',
  sort: 'UPDATED_DESC',
  page: 1,
  size: 6,
})

const errorText = (error: unknown, fallback: string) =>
  error instanceof Error && error.message.trim() ? error.message : fallback

export const useAnimalCatalog = (dataSource: AnimalGovernanceDataSource) => {
  const criteria = reactive(defaultCriteria())
  const items = ref<Awaited<ReturnType<AnimalGovernanceDataSource['search']>>['items']>([])
  const total = ref(0)
  const loading = ref(false)
  const errorMessage = ref('')
  const detail = ref<GovernanceAnimal | null>(null)
  const detailVisible = ref(false)
  const detailLoading = ref(false)
  const detailError = ref('')
  let searchGeneration = 0
  let detailGeneration = 0

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
      criteria.page = result.page
      criteria.size = result.size
    } catch (error) {
      if (generation !== searchGeneration) return
      items.value = []
      total.value = 0
      errorMessage.value = errorText(error, 'Animal 档案加载失败，请稍后重试。')
    } finally {
      if (generation === searchGeneration) loading.value = false
    }
  }

  const reset = async () => {
    Object.assign(criteria, defaultCriteria())
    await load()
  }

  const changePage = async (page: number) => {
    criteria.page = page
    await load()
  }

  const changePageSize = async (size: number) => {
    criteria.size = size
    await load(true)
  }

  const openDetail = async (animalId: string) => {
    const generation = ++detailGeneration
    detailVisible.value = true
    detailLoading.value = true
    detailError.value = ''
    detail.value = null
    try {
      const result = await dataSource.getDetail(animalId)
      if (generation === detailGeneration) detail.value = result
    } catch (error) {
      if (generation === detailGeneration) detailError.value = errorText(error, 'Animal 档案详情加载失败，请稍后重试。')
    } finally {
      if (generation === detailGeneration) detailLoading.value = false
    }
  }

  const closeDetail = () => {
    detailGeneration += 1
    detailVisible.value = false
    detail.value = null
    detailError.value = ''
    detailLoading.value = false
  }

  return {
    criteria,
    items,
    total,
    loading,
    errorMessage,
    detail,
    detailVisible,
    detailLoading,
    detailError,
    load,
    reset,
    changePage,
    changePageSize,
    openDetail,
    closeDetail,
  }
}
