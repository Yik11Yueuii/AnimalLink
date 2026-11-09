import { reactive, ref } from 'vue'
import { AuthenticationApiError } from '@/api/authentication'
import type { RescueGovernanceDataSource } from './dataSource'
import type { RescueDetail, RescueSearchCriteria } from './types'
const defaultCriteria = (campusId = ''): RescueSearchCriteria => ({ keyword: '', campusId, rescueStatus: '', sort: 'UPDATED_DESC', page: 1, size: 6 })
const message = (error: unknown, fallback: string) => error instanceof Error && error.message.trim() ? error.message : fallback
export const useRescueCatalog = (dataSource: RescueGovernanceDataSource, initialCampusId = '') => {
  const criteria = reactive(defaultCriteria(initialCampusId))
  const items = ref<Awaited<ReturnType<RescueGovernanceDataSource['search']>>['items']>([])
  const total = ref(0), loading = ref(false), errorMessage = ref('')
  const detail = ref<RescueDetail | null>(null), detailVisible = ref(false), detailLoading = ref(false), detailError = ref('')
  const verifying = ref(false), verifyMessage = ref(''), verifyError = ref('')
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
    detailVisible.value = true; detailLoading.value = true; detailError.value = ''; detail.value = null; verifyMessage.value = ''; verifyError.value = ''
    try {
      const result = await dataSource.getDetail(eventId)
      if (generation === detailGeneration) detail.value = result
    } catch (error) {
      if (generation === detailGeneration) detailError.value = message(error, '救助详情加载失败，请稍后重试。')
    } finally { if (generation === detailGeneration) detailLoading.value = false }
  }
  const retryDetail = async () => { if (selectedEventId) await openDetail(selectedEventId) }
  const verify = async () => {
    const eventId = detail.value?.eventId
    if (!eventId || detail.value?.eventStatus !== 'REPORTED' || verifying.value) return
    const generation = detailGeneration
    const isCurrent = () => selectedEventId === eventId && detailVisible.value && generation === detailGeneration
    verifying.value = true; verifyMessage.value = ''; verifyError.value = ''
    try {
      const updated = await dataSource.verify(eventId)
      await load()
      if (isCurrent()) {
        detail.value = updated
        verifyMessage.value = '事件已核实，志愿者现在可以认领。'
      }
    } catch (error) {
      if (isCurrent()) verifyError.value = error instanceof AuthenticationApiError
          ? error.kind === 'conflict' ? '事件状态已变化，已刷新详情，请核对后再操作。'
            : error.kind === 'unauthenticated' ? '管理员登录已失效，请重新登录。'
              : error.kind === 'forbidden' ? '当前账号无核实权限。'
                : '操作结果暂不确定，已刷新状态，请勿盲目重复提交。'
          : '操作结果暂不确定，已刷新状态，请勿盲目重复提交。'
      await load()
      if (isCurrent()) {
        try {
          const updated = await dataSource.getDetail(eventId)
          if (isCurrent()) detail.value = updated
        } catch { if (isCurrent()) detailError.value = '状态刷新失败，请重新打开详情确认结果。' }
      }
    } finally { verifying.value = false }
  }
  const closeDetail = () => { detailGeneration += 1; selectedEventId = ''; detailVisible.value = false; detail.value = null; detailError.value = ''; verifyMessage.value = ''; verifyError.value = ''; detailLoading.value = false }
  return { criteria, items, total, loading, errorMessage, detail, detailVisible, detailLoading, detailError,
    verifying, verifyMessage, verifyError, verify, load, reset, changePage, changePageSize, openDetail, retryDetail, closeDetail }
}
